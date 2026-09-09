package com.Film1k

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

data class CinemetaResponse(
    val meta: CinemetaMeta? = null
)

data class CinemetaMeta(
    val name: String? = null,
    val genres: List<String>? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null, 
    val description: String? = null,
    val releaseInfo: String? = null,
    val year: String? = null,
    val cast: List<String>? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val country: String? = null
)

data class StremioSubtitle(
    val id: String? = null,
    val url: String? = null,
    val lang: String? = null,
    val score: Double? = null,
    val downloads: Int? = null
)

data class StremioSubtitlesResponse(
    val subtitles: List<StremioSubtitle>? = null
)

class Film1kProvider : MainAPI() {
    override var mainUrl = "https://www.film1k.com"
    override var name = "Film1k"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Cinemeta - Stremio's official metadata addon.
    private val cinemetaBaseUrl = "https://v3-cinemeta.strem.io/meta/movie"

    // Stremio community OpenSubtitles addon
    private val openSubtitlesBaseUrl = "https://opensubtitles-v3.strem.io/subtitles/movie"
    private val openSubtitlesMaxResults = 10

    // Concurrency limit to prevent network timeouts when fetching detail IDs
    private val listingConcurrency = Semaphore(5)

    // Memory cache to completely skip HTTP requests on repeat visits
    private val imdbIdCache = mutableMapOf<String, String>()

    private val isHorizontalImages = false

    override val mainPage = mainPageOf(
        "$mainUrl/tag/usa" to "USA Movies",
        "$mainUrl/tag/1990s" to "1990s Movies"
    )

    private val titleJunkRegex = Regex(
        "full movie online|movie poster watch online|watch movie online|watch tv online|watch series online|movie poster|watch online|film1k",
        RegexOption.IGNORE_CASE
    )

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(titleJunkRegex, "")
            .replace(Regex("\\(\\d{4}\\)"), "") // Automatically strip (YYYY)
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
            .trim()
    }

    private fun getImageUrl(el: Element?): String? {
        if (el == null) return null
        return el.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun extractDetailPoster(doc: Document): String? {
        val imgEl = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
            ?: doc.selectFirst("article img")
        val manualPoster = getImageUrl(imgEl)
        val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        return manualPoster ?: ogPoster
    }

    private suspend fun getValidImageUrl(primaryUrl: String?, fallbackUrl: String?): String? {
        val primary = primaryUrl?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
        val fallback = fallbackUrl?.takeIf { it.isNotBlank() }

        if (primary == null) return fallback

        return try {
            val isValid = app.head(primary, cacheTime = 1440).code == 200
            if (isValid) primary else fallback
        } catch (e: Exception) {
            fallback
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page/"
        
        val doc = try {
            app.get(url, verify = false).document
        } catch (e: Exception) {
            return newHomePageResponse(emptyList())
        }

        // Limit items per page load to ensure instantaneous homepage startup
        val items = parseArticles(doc, limit = 8)
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = isHorizontalImages
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = try {
            app.get("$mainUrl/?s=$query", verify = false).document
        } catch (e: Exception) {
            return emptyList()
        }
        return parseArticles(doc, limit = 12)
    }

    private suspend fun parseArticles(doc: Document, limit: Int? = null): List<SearchResponse> = coroutineScope {
        val allArticles = doc.select("#Ez-Wp > div > div > div > main > section > article")
            .ifEmpty { doc.select("main > section article") }
        
        val articles = if (limit != null) allArticles.take(limit) else allArticles

        articles.map { article ->
            async {
                listingConcurrency.withPermit {
                    val aHeader = article.selectFirst("header > a") ?: article.selectFirst("a") ?: return@withPermit null
                    val mediaUrl = fixUrl(aHeader.attr("href"))
                    if (mediaUrl.isBlank()) return@withPermit null

                    val rawName = article.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
                        ?: aHeader.text().trim()
                    val manualMediaName = cleanTitle(rawName)

                    val imgEl = article.selectFirst("header > a > figure > img") ?: article.selectFirst("img")
                    val manualPosterUrl = getImageUrl(imgEl)?.let { fixUrl(it) }

                    // Lightning fast regex extraction - skips JSoup DOM building entirely
                    var imdbId = imdbIdCache[mediaUrl]
                    var fastDetailPoster: String? = null

                    if (imdbId == null) {
                        try {
                            val detailText = app.get(mediaUrl, verify = false, cacheTime = 1440).text
                            imdbId = Regex("imdb\\.com/title/(tt\\d+)").find(detailText)?.groupValues?.get(1)
                                ?: Regex("tt\\d{7,8}").find(detailText)?.value
                            
                            if (imdbId != null) {
                                imdbIdCache[mediaUrl] = imdbId
                            }
                            fastDetailPoster = Regex("property=\"og:image\"\\s+content=\"([^\"]+)\"").find(detailText)?.groupValues?.get(1)
                        } catch (e: Exception) {}
                    }

                    val cinemeta = imdbId?.let { fetchCinemetaData(it) }

                    // Priority: Cinemeta -> Manual Scrape
                    val mediaName = cinemeta?.name?.takeIf { it.isNotBlank() } ?: manualMediaName
                    
                    val yearInt = cinemeta?.year?.toIntOrNull() 
                        ?: cinemeta?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

                    val finalPosterUrl = cinemeta?.poster ?: fastDetailPoster ?: manualPosterUrl

                    newMovieSearchResponse(mediaName, mediaUrl, TvType.Movie) {
                        this.posterUrl = finalPosterUrl
                        this.year = yearInt
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchCinemetaData(imdbId: String): CinemetaMeta? {
        return try {
            val responseText = app.get("$cinemetaBaseUrl/$imdbId.json", cacheTime = 1440).text
            tryParseJson<CinemetaResponse>(responseText)?.meta
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchOpenSubtitles(imdbId: String): List<SubtitleFile> {
        val requestUrl = "$openSubtitlesBaseUrl/$imdbId.json"

        val responseText = try {
            app.get(requestUrl, cacheTime = 1440).text
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = tryParseJson<StremioSubtitlesResponse>(responseText) ?: return emptyList()

        return parsed.subtitles
            ?.filter { it.lang.equals("eng", ignoreCase = true) || it.lang.equals("en", ignoreCase = true) }
            ?.filter { !it.url.isNullOrBlank() }
            ?.sortedWith(
                compareByDescending<StremioSubtitle> { it.downloads ?: -1 }
                    .thenByDescending { it.score ?: -1.0 }
            )
            ?.take(openSubtitlesMaxResults)
            ?.mapNotNull { sub -> sub.url?.let { SubtitleFile("English", it) } }
            ?: emptyList()
    }

    private fun extractRecommendations(doc: Document): List<SearchResponse> {
        val articles = doc.select("main > section article > header > a")
        return articles.mapNotNull { aTag ->
            val href = fixUrl(aTag.attr("href"))
            if (href.isBlank()) return@mapNotNull null

            val rawTitle = aTag.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: aTag.text().trim()
            if (rawTitle.isBlank()) return@mapNotNull null
            val title = cleanTitle(rawTitle)

            val imgEl = aTag.selectFirst("figure img") ?: aTag.selectFirst("img")
            val posterUrl = getImageUrl(imgEl)?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, verify = false, cacheTime = 1440).document

        val manualPosterUrl = extractDetailPoster(doc)?.let { fixUrl(it) }

        val manualRawName = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()

        val manualMediaName = cleanTitle(manualRawName)

        var manualPlot: String? = null
        val descContainer = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div")

        fun cleanupText(text: String): String {
            return text
                .replace(Regex("\\s+([.,;:!?])"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun extractAfterLabel(label: String): String? {
            val labelEl = descContainer?.select("strong, h3, b")?.firstOrNull {
                it.text().trim().removeSuffix(":").trim().equals(label, ignoreCase = true)
            } ?: return null

            val builder = StringBuilder()
            var sibling = labelEl.nextSibling()
            while (sibling != null) {
                if (sibling is Element) {
                    val tagName = sibling.tagName().lowercase()
                    if (tagName in listOf("h1", "h2", "h3", "h4", "strong", "b")) break
                    builder.append(sibling.text()).append(" ")
                } else if (sibling is TextNode) {
                    builder.append(sibling.text()).append(" ")
                }
                sibling = sibling.nextSibling()
            }

            var extracted = cleanupText(builder.toString()).removePrefix(",").removePrefix(":").trim()
            if (extracted.isBlank()) return null

            val sentences = extracted.split(Regex("(?<=[.!?])\\s+"))
            val filtered = sentences.filterNot { it.contains("Film1k", ignoreCase = true) }
                .joinToString(" ").trim()
            extracted = filtered.ifBlank { extracted }

            return extracted.ifBlank { null }
        }

        fun extractTitleLeadParagraph(): String? {
            val firstP = descContainer?.selectFirst("p") ?: return null
            val leadStrong = firstP.selectFirst("strong") ?: return null
            var text = firstP.text()
            val titleText = leadStrong.text()
            if (text.startsWith(titleText)) {
                text = text.removePrefix(titleText)
            }
            text = cleanupText(text).removePrefix(",").removePrefix(":").trim()
            if (text.isBlank()) return null

            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            val filtered = sentences.filterNot { it.contains("Film1k", ignoreCase = true) }
                .joinToString(" ").trim()
            return filtered.ifBlank { text }.ifBlank { null }
        }

        manualPlot = extractAfterLabel("Description") ?: extractAfterLabel("Plot") ?: extractTitleLeadParagraph()

        manualPlot = manualPlot?.let { cleanupText(it) }
            ?.replace("^\\s*:\\s*".toRegex(), "")
            ?.replace("^\\s*\"|\"\\s*$".toRegex(), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags1 = doc.select("#Ez-Wp > div > div.Container > div > aside > div > p:nth-child(4) > a").map { it.text() }
        val tags2 = doc.select("#Ez-Wp > div > div.Container > div > aside > div > p:nth-child(6) > a").map { it.text() }
        val manualTags = (tags1 + tags2).filter { it.isNotBlank() }.distinct()

        val imdbId = Regex("imdb\\.com/title/(tt\\d+)").find(doc.html())?.groupValues?.get(1)
            ?: Regex("tt\\d{7,8}").find(doc.html())?.value

        val cinemeta = imdbId?.let { fetchCinemetaData(it) }

        // Guarantee fallback matches list page identically
        val mediaName = cinemeta?.name?.takeIf { it.isNotBlank() } ?: manualMediaName
        val yearInt = cinemeta?.year?.toIntOrNull() 
            ?: cinemeta?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val finalPosterUrl = getValidImageUrl(cinemeta?.poster, manualPosterUrl)
        val finalBackgroundUrl = getValidImageUrl(cinemeta?.background, finalPosterUrl)

        val plot = cinemeta?.description?.takeIf { it.isNotBlank() } ?: manualPlot
        
        val allTags = mutableListOf<String>()
        cinemeta?.genres?.takeIf { it.isNotEmpty() }?.let { allTags.addAll(it) }
        cinemeta?.country?.takeIf { it.isNotBlank() }?.let { allTags.add(it) }
        
        if (allTags.isEmpty() && manualTags.isNotEmpty()) {
            allTags.addAll(manualTags)
        }

        val allActors = mutableListOf<ActorData>()
        cinemeta?.cast?.forEach { cast -> allActors.add(ActorData(Actor(cast), roleString = "Cast")) }

        val ratingText = cinemeta?.imdbRating?.takeIf { it.isNotBlank() }
        val durationInt = cinemeta?.runtime?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val recommendations = extractRecommendations(doc)

        return newMovieLoadResponse(mediaName, url, TvType.Movie, url) {
            this.posterUrl = finalPosterUrl
            this.backgroundPosterUrl = finalBackgroundUrl
            this.logoUrl = cinemeta?.logo
            this.year = yearInt
            this.plot = plot
            this.tags = allTags.distinct()
            this.score = ratingText?.let { Score.from10(it) }
            this.duration = durationInt
            
            if (allActors.isNotEmpty()) {
                this.actors = allActors
            }
            if (recommendations.isNotEmpty()) {
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val extractedUrls = mutableSetOf<String>()
        val collectedLinks = mutableListOf<ExtractorLink>()
        val collectingCallback: (ExtractorLink) -> Unit = { link -> collectedLinks.add(link) }

        val subtitleJob = async {
            val docText = app.get(data, verify = false, cacheTime = 1440).text
            val imdbId = Regex("imdb\\.com/title/(tt\\d+)").find(docText)?.groupValues?.get(1)
                ?: Regex("tt\\d{7,8}").find(docText)?.value
            
            if (imdbId != null) {
                fetchOpenSubtitles(imdbId)
            } else emptyList()
        }

        val htmlJob = async {
            app.get(data, verify = false, cacheTime = 1440).document
        }

        val doc = htmlJob.await()

        doc.select("#my-video > source").forEach { source ->
            val src = getImageUrl(source)
            if (!src.isNullOrBlank()) extractedUrls.add(fixUrl(src))
        }

        doc.select("#video-op-a > div > iframe").forEach { iframe ->
            val src = getImageUrl(iframe)
            if (!src.isNullOrBlank()) extractedUrls.add(fixUrl(src))
        }

        doc.select("#Eroz > div > ul > li > a").forEach { aTag ->
            val href = aTag.attr("href").ifBlank { aTag.attr("data-link") }
            if (href.isNotBlank()) extractedUrls.add(fixUrl(href))
        }

        extractedUrls.map { videoUrl ->
            async {
                val isM3u8 = videoUrl.contains(".m3u8")
                val isMp4 = videoUrl.contains(".mp4")

                val isDirectMedia = (isM3u8 || isMp4) &&
                    !videoUrl.contains("/e/") &&
                    !videoUrl.contains("/embed/") &&
                    !videoUrl.contains("/v/")

                if (isDirectMedia) {
                    collectingCallback.invoke(
                        newExtractorLink(
                            name = "Film1k",
                            source = "Film1k",
                            url = videoUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    val isNativeHandled = loadExtractor(videoUrl, mainUrl, subtitleCallback, collectingCallback)
                    if (!isNativeHandled) {
                        Film1kExtractor().getUrl(videoUrl, mainUrl, subtitleCallback, collectingCallback)
                    }
                }
            }
        }.awaitAll()

        subtitleJob.await().forEach { subtitleCallback(it) }

        var emitted = false
        val sortedLinks = collectedLinks.sortedByDescending { it.type == ExtractorLinkType.M3U8 }

        for (link in sortedLinks) {
            if (!emitted) {
                callback.invoke(link)
                emitted = true
            }
        }

        return@coroutineScope extractedUrls.isNotEmpty()
    }
}

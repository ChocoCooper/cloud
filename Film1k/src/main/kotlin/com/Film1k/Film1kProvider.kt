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

// --- Cinemeta Data Classes ---
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

// --- WP-JSON Data Classes ---
data class WpPost(
    val link: String? = null,
    val title: WpRendered? = null,
    val content: WpRendered? = null,
    val meta: WpMeta? = null
)

data class WpRendered(
    val rendered: String? = null
)

data class WpMeta(
    val fifu_image_url: String? = null
)

// --- Subtitle Data Classes ---
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

    private val cinemetaBaseUrl = "https://v3-cinemeta.strem.io/meta/movie"
    private val openSubtitlesBaseUrl = "https://opensubtitles-v3.strem.io/subtitles/movie"
    private val openSubtitlesMaxResults = 10

    private val listingConcurrency = Semaphore(5)
    private val isHorizontalImages = false

    override val mainPage = mainPageOf(
        "$mainUrl/wp-json/wp/v2/posts?tags=11" to "USA Movies",
        "$mainUrl/wp-json/wp/v2/posts?tags=58" to "1990s Movies"
    )

    private val titleJunkRegex = Regex(
        "full movie online|movie poster watch online|watch movie online|watch tv online|watch series online|movie poster|watch online|film1k",
        RegexOption.IGNORE_CASE
    )

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(titleJunkRegex, "")
            .replace(Regex("\\(\\d{4}\\)"), "") 
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
        val url = "${request.data}&page=$page"
        
        val responseText = try {
            app.get(url, verify = false).text
        } catch (e: Exception) {
            return newHomePageResponse(emptyList())
        }

        val wpPosts = tryParseJson<List<WpPost>>(responseText) ?: emptyList()
        val items = parseWpPosts(wpPosts)
        
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
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$query&per_page=15"
        
        val responseText = try {
            app.get(apiUrl, verify = false).text
        } catch (e: Exception) {
            return emptyList()
        }

        val wpPosts = tryParseJson<List<WpPost>>(responseText) ?: return emptyList()
        return parseWpPosts(wpPosts)
    }

    // 🚀 ULTRA-FAST JSON PARSER (Shared by Homepage & Search)
    private suspend fun parseWpPosts(wpPosts: List<WpPost>): List<SearchResponse> = coroutineScope {
        wpPosts.map { post ->
            async {
                listingConcurrency.withPermit {
                    val mediaUrl = post.link?.takeIf { it.isNotBlank() } ?: return@withPermit null
                    val rawName = post.title?.rendered ?: return@withPermit null
                    val manualMediaName = cleanTitle(rawName)

                    var manualPosterUrl = post.meta?.fifu_image_url?.takeIf { it.isNotBlank() }
                    if (manualPosterUrl == null) {
                        manualPosterUrl = post.content?.rendered?.let { html ->
                            Regex("src=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                        }
                    }
                    manualPosterUrl = manualPosterUrl?.let { fixUrl(it) }

                    // Rip the IMDb ID straight out of the JSON HTML block
                    val imdbId = post.content?.rendered?.let { html ->
                        Regex("imdb\\.com/title/(tt\\d+)").find(html)?.groupValues?.get(1)
                            ?: Regex("tt\\d{7,8}").find(html)?.value
                    }

                    val cinemeta = imdbId?.let { fetchCinemetaData(it) }

                    // Cinemeta Priority, Manual Fallback
                    val mediaName = cinemeta?.name?.takeIf { it.isNotBlank() } ?: manualMediaName
                    val yearInt = cinemeta?.year?.toIntOrNull() 
                        ?: cinemeta?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
                        ?: Regex("\\((\\d{4})\\)").find(rawName)?.groupValues?.get(1)?.toIntOrNull()

                    val finalPosterUrl = cinemeta?.poster ?: manualPosterUrl

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

        // --- Manual Scraping ---
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

        // --- Cinemeta Enrichment ---
        val cinemeta = imdbId?.let { fetchCinemetaData(it) }

        // --- Prioritized Resolution (Cinemeta first, then Manual) ---
        val mediaName = cinemeta?.name?.takeIf { it.isNotBlank() } ?: manualMediaName
        val yearInt = cinemeta?.year?.toIntOrNull() 
            ?: cinemeta?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val finalPosterUrl = getValidImageUrl(cinemeta?.poster, manualPosterUrl)
        val finalBackgroundUrl = getValidImageUrl(cinemeta?.background, finalPosterUrl)

        val plot = cinemeta?.description?.takeIf { it.isNotBlank() } ?: manualPlot
        
        val allTags = mutableListOf<String>()
        cinemeta?.genres?.takeIf { it.isNotEmpty() }?.let { allTags.addAll(it) }
        cinemeta?.country?.takeIf { it.isNotBlank() }?.let { allTags.add(it) }
        
        // Fallback to manual tags only if Cinemeta had absolutely nothing
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
            this.logoUrl = cinemeta?.logo // Prioritized Logo mapped here
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

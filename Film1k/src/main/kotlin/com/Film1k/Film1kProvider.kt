package com.Film1k

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

data class CinemetaResponse(
    val meta: CinemetaMeta? = null
)

data class CinemetaMeta(
    val name: String? = null,
    val year: String? = null,
    val releaseInfo: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val genres: List<String>? = null,
    val director: List<String>? = null,
    val cast: List<String>? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    val language: String? = null,
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

    // Stremio community OpenSubtitles addon
    private val openSubtitlesBaseUrl = "https://opensubtitles-v3.strem.io/subtitles/movie"
    private val openSubtitlesMaxResults = 10

    override val mainPage = mainPageOf(
        "$mainUrl/tag/usa" to "USA Movies",
        "$mainUrl/tag/1990s" to "1990s Movies"
    )

    private val isHorizontalImages = false

    // Persistent disk cache for fast repeat lookups
    private val imdbIdCache = mutableMapOf<String, String>()

    private val titleJunkRegex = Regex(
        "full movie online|movie poster watch online|watch movie online|watch tv online|watch series online|movie poster|watch online",
        RegexOption.IGNORE_CASE
    )

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(titleJunkRegex, "")
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

    private suspend fun getValidPoster(cinemetaPoster: String?, manualPoster: String?): String? {
        val primary = cinemetaPoster?.takeIf { it.isNotBlank() }
        val fallback = manualPoster?.takeIf { it.isNotBlank() }

        if (primary == null) return fallback

        return try {
            val isValid = app.get(primary).code == 200
            if (isValid) primary else fallback
        } catch (e: Exception) {
            fallback
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        val usaJob = async {
            val usaDoc = app.get("$mainUrl/tag/usa", verify = false).document
            val usaTitle = usaDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "USA Movies"
            val usaItems = parseArticles(usaDoc, limit = 7)
            HomePageList(usaTitle, usaItems, isHorizontalImages = isHorizontalImages)
        }
        
        val nintiesJob = async {
            val nintiesDoc = app.get("$mainUrl/tag/1990s", verify = false).document
            val nintiesTitle = nintiesDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "1990s Movies"
            val nintiesItems = parseArticles(nintiesDoc, limit = 7)
            HomePageList(nintiesTitle, nintiesItems, isHorizontalImages = isHorizontalImages)
        }

        val homePageList = listOf(usaJob.await(), nintiesJob.await()).filter { it.list.isNotEmpty() }
        newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val page1Url = "$mainUrl/?s=$query"

        val page1Items = try {
            parseArticles(app.get(page1Url, verify = false).document)
        } catch (e: Exception) {
            emptyList()
        }

        val page2Items = if (page1Items.size < 10) {
            try {
                val page2Url = "$mainUrl/page/2?s=$query"
                parseArticles(app.get(page2Url, verify = false).document)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val seenUrls = mutableSetOf<String>()
        return (page1Items + page2Items).filter { seenUrls.add(it.url) }
    }

    private suspend fun extractImdbIdFast(mediaUrl: String): String? {
        imdbIdCache[mediaUrl]?.let { return it }

        return try {
            val rawHtml = app.get(mediaUrl, verify = false).text
            val id = Regex("tt\\d{7,8}").find(rawHtml)?.value
            if (id != null) {
                imdbIdCache[mediaUrl] = id
            }
            id
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchCinemetaData(imdbId: String): CinemetaResponse? {
        return try {
            val responseText = app.get("https://v3-cinemeta.strem.io/meta/movie/$imdbId.json").text
            // Manually parse JSON without inline to avoid JVM target mismatch in older setups
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            mapper.readValue(responseText, CinemetaResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun parseArticles(doc: Document, limit: Int? = null): List<SearchResponse> = coroutineScope {
        val allArticles = doc.select("main > section article")
        val articles = if (limit != null) allArticles.take(limit) else allArticles

        articles.map { article ->
            async {
                val aHeader = article.selectFirst("header > a") ?: article.selectFirst("a") ?: return@async null
                val mediaUrl = fixUrl(aHeader.attr("href"))
                if (mediaUrl.isBlank()) return@async null

                val rawName = article.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: aHeader.text().trim()
                val manualMediaName = cleanTitle(rawName)

                val imgEl = article.selectFirst("header > a > figure > img") ?: article.selectFirst("img")
                val manualPosterUrl = getImageUrl(imgEl)?.let { fixUrl(it) }

                val imdbId = extractImdbIdFast(mediaUrl)
                val cinemeta = imdbId?.let { fetchCinemetaData(it) }?.meta

                val mediaName = manualMediaName.takeIf { it.isNotBlank() } ?: cinemeta?.name ?: ""
                
                val yearInt = cinemeta?.year?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
                    ?: cinemeta?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

                val finalPosterUrl = getValidPoster(cinemeta?.poster, manualPosterUrl)

                newMovieSearchResponse(mediaName, mediaUrl, TvType.Movie) {
                    this.posterUrl = finalPosterUrl
                    this.year = yearInt
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchOpenSubtitles(imdbId: String): List<SubtitleFile> {
        return try {
            val requestUrl = "$openSubtitlesBaseUrl/$imdbId.json"
            val responseText = app.get(requestUrl).text
            
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val parsed = mapper.readValue(responseText, StremioSubtitlesResponse::class.java)
            
            parsed.subtitles
                ?.filter { it.lang.equals("eng", ignoreCase = true) && !it.url.isNullOrBlank() }
                ?.sortedWith(
                    compareByDescending<StremioSubtitle> { it.downloads ?: -1 }
                        .thenByDescending { it.score ?: -1.0 }
                )
                ?.take(openSubtitlesMaxResults)
                ?.mapNotNull { sub -> sub.url?.let { SubtitleFile("English", it) } }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
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
        val doc = app.get(url, verify = false).document

        val manualPosterUrl = extractDetailPoster(doc)?.let { fixUrl(it) }
        val manualRawName = doc.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title()
        val manualMediaName = cleanTitle(manualRawName)

        var manualPlot: String? = null
        val descContainer = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div")
        
        fun extractAfterLabel(label: String): String? {
            val labelEl = descContainer?.select("strong, h3, b")?.firstOrNull {
                it.text().trim().removeSuffix(":").trim().equals(label, ignoreCase = true)
            } ?: return null

            val builder = StringBuilder()
            var sibling = labelEl.nextSibling()
            while (sibling != null) {
                if (sibling is Element && sibling.tagName().lowercase() in listOf("h1", "h2", "h3", "h4", "strong", "b")) break
                if (sibling is Element) builder.append(sibling.text()).append(" ")
                else if (sibling is TextNode) builder.append(sibling.text()).append(" ")
                sibling = sibling.nextSibling()
            }
            val extracted = builder.toString().trim().removePrefix(",").removePrefix(":")
            return extracted.ifBlank { null }
        }
        manualPlot = extractAfterLabel("Description") ?: extractAfterLabel("Plot")

        val manualTags = doc.select("#Ez-Wp > div > div.Container > div > aside > div > p > a")
            .map { it.text() }.filter { it.isNotBlank() }.distinct()

        val imdbId = extractImdbIdFast(url)
        val cinemeta = imdbId?.let { fetchCinemetaData(it) }?.meta

        val mediaName = manualMediaName.takeIf { it.isNotBlank() } ?: cinemeta?.name ?: ""
        
        val yearInt = cinemeta?.year?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
            ?: cinemeta?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val finalPosterUrl = getValidPoster(cinemeta?.poster, manualPosterUrl)
        val finalBackgroundUrl = getValidPoster(cinemeta?.background, finalPosterUrl)
        
        val plot = cinemeta?.description?.takeIf { it.isNotBlank() } ?: manualPlot
        
        // Map Genres, Country, and Language into Cloudstream's Tag Chips
        val allTags = mutableListOf<String>()
        if (!cinemeta?.genres.isNullOrEmpty()) {
            allTags.addAll(cinemeta!!.genres!!)
        } else if (manualTags.isNotEmpty()) {
            allTags.addAll(manualTags)
        }
        cinemeta?.language?.split(",")?.map { it.trim() }?.forEach { if (it.isNotBlank()) allTags.add(it) }
        cinemeta?.country?.split(",")?.map { it.trim() }?.forEach { if (it.isNotBlank()) allTags.add(it) }

        val allActors = mutableListOf<ActorData>()
        cinemeta?.director?.forEach { dir -> allActors.add(ActorData(Actor(dir), roleString = "Director")) }
        cinemeta?.cast?.forEach { cast -> allActors.add(ActorData(Actor(cast), roleString = "Cast")) }

        val durationInt = cinemeta?.runtime?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        
        // Reverting back to native 'rating' property using Cloudstream's string extension
        val parsedRating = cinemeta?.imdbRating?.toRatingInt()

        val recommendations = extractRecommendations(doc)

        return newMovieLoadResponse(mediaName, url, TvType.Movie, url) {
            this.posterUrl = finalPosterUrl
            this.backgroundPosterUrl = finalBackgroundUrl
            this.year = yearInt
            this.plot = plot
            this.tags = allTags.distinct() // Prevents duplicate chips
            this.duration = durationInt
            this.rating = parsedRating
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
            val imdbId = extractImdbIdFast(data)
            if (imdbId != null) {
                fetchOpenSubtitles(imdbId)
            } else emptyList()
        }

        val htmlJob = async {
            app.get(data, verify = false).document
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

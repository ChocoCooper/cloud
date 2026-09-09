package com.Film1k

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class OmdbResponse(
    val Title: String? = null,
    val Year: String? = null,
    val Plot: String? = null,
    val Poster: String? = null,
    val Genre: String? = null,
    val Actors: String? = null,
    val Response: String? = null,
    val imdbRating: String? = null,
    val Runtime: String? = null
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

    private val omdbApiKeys = listOf(
        "4b447405",
        "eb0c0475",
        "7776cbde",
        "ff28f90b",
        "6c3a2d45",
        "b07b58c8",
        "ad04b643",
        "a95b5205",
        "777d9323",
        "2c2c3314",
        "b5cff164",
        "89a9f57d",
        "73a9858a",
        "efbd8357"
    )

    // Dead Key Registry: thread-safe map of key -> retry-after timestamp (millis).
    // A key that hits its daily limit gets parked until the next UTC midnight (when
    // OMDb's quota resets) instead of being excluded forever, and ConcurrentHashMap
    // makes this safe to read/write from many parallel coroutines at once.
    private val deadOmdbKeys = ConcurrentHashMap<String, Long>()

    private fun isKeyDead(key: String): Boolean {
        val deadUntil = deadOmdbKeys[key] ?: return false
        if (System.currentTimeMillis() >= deadUntil) {
            deadOmdbKeys.remove(key)
            return false
        }
        return true
    }

    private fun markKeyDead(key: String) {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        deadOmdbKeys[key] = cal.timeInMillis
    }

    // Caps how many listing items are processed concurrently (each item does a detail-page
    // fetch + OMDb lookup(s) + poster validation), so we don't fire dozens of simultaneous
    // connections at film1k.com/omdbapi.com/the poster CDN all at once.
    private val listingConcurrency = Semaphore(5)

    // Stremio community OpenSubtitles addon - no API key required.
    private val openSubtitlesBaseUrl = "https://opensubtitles-v3.strem.io/subtitles/movie"
    private val openSubtitlesMaxResults = 10

    override val mainPage = mainPageOf(
        "$mainUrl/tag/usa" to "USA Movies",
        "$mainUrl/tag/1990s" to "1990s Movies"
    )

    private val isHorizontalImages = false

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

    // Helper to safely extract image URLs while ignoring base64 "data:" placeholders
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

    // Validates if OMDb poster actually exists (fixing Amazon 404 issues), using a HEAD
    // request so we don't download the full image just to read a status code.
    private suspend fun getValidPoster(omdbPoster: String?, manualPoster: String?): String? {
        val primary = omdbPoster?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
        val fallback = manualPoster?.takeIf { it.isNotBlank() }

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
        val usaDoc = app.get("$mainUrl/tag/usa", verify = false).document
        val nintiesDoc = app.get("$mainUrl/tag/1990s", verify = false).document

        val homePageList = mutableListOf<HomePageList>()

        val usaTitle = usaDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "USA Movies"
        val usaItems = parseArticles(usaDoc, limit = 7)
        if (usaItems.isNotEmpty()) {
            homePageList.add(HomePageList(usaTitle, usaItems, isHorizontalImages = isHorizontalImages))
        }

        val nintiesTitle = nintiesDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "1990s Movies"
        val nintiesItems = parseArticles(nintiesDoc, limit = 7)
        if (nintiesItems.isNotEmpty()) {
            homePageList.add(HomePageList(nintiesTitle, nintiesItems, isHorizontalImages = isHorizontalImages))
        }

        return newHomePageResponse(homePageList)
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

    private suspend fun parseArticles(doc: Document, limit: Int? = null): List<SearchResponse> = coroutineScope {
        val allArticles = doc.select("#Ez-Wp > div > div > div > main > section > article")
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

                    var detailPosterUrl: String? = null
                    val omdb = try {
                        // Cached for 24 hours to prevent duplicate detail fetches for the same search
                        val detailDoc = app.get(mediaUrl, verify = false, cacheTime = 1440).document
                        detailPosterUrl = extractDetailPoster(detailDoc)?.let { fixUrl(it) }
                        extractImdbId(detailDoc)?.let { fetchOmdbData(it) }
                    } catch (e: Exception) {
                        null
                    }

                    val mediaName = getBestTitle(manualMediaName, omdb?.Title, omdb?.Year)
                    val yearInt = omdb?.Year?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

                    val bestManualPoster = detailPosterUrl ?: manualPosterUrl
                    val finalPosterUrl = getValidPoster(omdb?.Poster, bestManualPoster)

                    newMovieSearchResponse(mediaName, mediaUrl, TvType.Movie) {
                        this.posterUrl = finalPosterUrl
                        this.year = yearInt
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun extractImdbId(doc: Document): String? {
        val href = doc.selectFirst("a[href*=imdb.com/title/]")?.attr("href") ?: return null
        return Regex("tt\\d+").find(href)?.value
    }

    private suspend fun fetchOmdbData(imdbId: String): OmdbResponse? {
        // Filter out keys that are currently parked (hit their daily limit)
        val availableKeys = omdbApiKeys.filterNot { isKeyDead(it) }
        if (availableKeys.isEmpty()) return null

        // Deterministic hash based on available keys length, so the same title tends to
        // reuse the same key across calls (cache-friendly) while spreading load overall.
        val primaryKeyIndex = abs(imdbId.hashCode()) % availableKeys.size
        val primaryKey = availableKeys[primaryKeyIndex]

        fetchOmdbWithKey(imdbId, primaryKey)?.let { return it }

        // Sequential fallback: try up to 2 other available keys to avoid flooding the API.
        val fallbackKeys = availableKeys.filter { it != primaryKey && !isKeyDead(it) }.shuffled().take(2)
        for (key in fallbackKeys) {
            fetchOmdbWithKey(imdbId, key)?.let { return it }
        }

        return null
    }

    private suspend fun fetchOmdbWithKey(imdbId: String, key: String): OmdbResponse? {
        return try {
            val response = app.get(
                "https://www.omdbapi.com/?i=$imdbId&apikey=$key&plot=full",
                verify = false,
                cacheTime = 1440
            )
            val responseText = response.text

            // Check for rate limiting and park the key until the next UTC reset
            if (responseText.contains("Request limit reached", ignoreCase = true) ||
                responseText.contains("Invalid API key", ignoreCase = true)
            ) {
                markKeyDead(key)
                return null
            }

            val json = JSONObject(responseText)
            if (json.optString("Response", "").equals("True", ignoreCase = true)) {
                OmdbResponse(
                    Title = json.optString("Title").takeIf { it.isNotBlank() },
                    Year = json.optString("Year").takeIf { it.isNotBlank() },
                    Plot = json.optString("Plot").takeIf { it.isNotBlank() },
                    Poster = json.optString("Poster").takeIf { it.isNotBlank() },
                    Genre = json.optString("Genre").takeIf { it.isNotBlank() },
                    Actors = json.optString("Actors").takeIf { it.isNotBlank() },
                    Response = json.optString("Response").takeIf { it.isNotBlank() },
                    imdbRating = json.optString("imdbRating").takeIf { it.isNotBlank() },
                    Runtime = json.optString("Runtime").takeIf { it.isNotBlank() }
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // OMDb's title is the priority whenever we have one - it's the accurate, canonical
    // title. The scraped site title is only ever a fallback for items OMDb has no entry for.
    private fun getBestTitle(manualTitle: String, omdbTitle: String?, omdbYear: String?): String {
        val cleanedOmdbTitle = omdbTitle?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
        if (cleanedOmdbTitle != null) {
            val year = omdbYear?.let { Regex("\\d{4}").find(it)?.value }
            return if (year != null) "$cleanedOmdbTitle ($year)" else cleanedOmdbTitle
        }
        return manualTitle.ifBlank { "" }
    }

    private suspend fun fetchOpenSubtitles(imdbId: String): List<SubtitleFile> {
        val requestUrl = "$openSubtitlesBaseUrl/$imdbId.json"

        val responseText = try {
            app.get(requestUrl, cacheTime = 1440).text
        } catch (e: Exception) {
            return emptyList()
        }

        val subtitlesList = mutableListOf<StremioSubtitle>()
        try {
            val json = JSONObject(responseText)
            val subsArray = json.optJSONArray("subtitles")
            if (subsArray != null) {
                for (i in 0 until subsArray.length()) {
                    val subObj = subsArray.optJSONObject(i) ?: continue
                    subtitlesList.add(
                        StremioSubtitle(
                            url = subObj.optString("url").takeIf { it.isNotBlank() },
                            lang = subObj.optString("lang").takeIf { it.isNotBlank() },
                            score = if (subObj.has("score")) subObj.optDouble("score") else null,
                            downloads = if (subObj.has("downloads")) subObj.optInt("downloads") else null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        // Accept both "eng" (this addon's usual ISO 639-2 code) and "en" as a safety net,
        // in case the addon (or a future replacement) ever returns the two-letter form.
        return subtitlesList
            .filter { it.lang?.lowercase() in listOf("eng", "en") && !it.url.isNullOrBlank() }
            .sortedWith(
                compareByDescending<StremioSubtitle> { it.downloads ?: -1 }
                    .thenByDescending { it.score ?: -1.0 }
            )
            .take(openSubtitlesMaxResults)
            .mapNotNull { sub -> sub.url?.let { SubtitleFile("English", it) } }
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

        // ---- Manual extraction (used as fallback if no IMDb id / OMDb lookup fails) ----
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

        // ---- IMDb id extraction + OMDb metadata enrichment ----
        val imdbId = extractImdbId(doc)
        val omdb = imdbId?.let { fetchOmdbData(it) }

        val mediaName = getBestTitle(manualMediaName, omdb?.Title, omdb?.Year)
        val yearInt = omdb?.Year?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val finalPosterUrl = getValidPoster(omdb?.Poster, manualPosterUrl)

        val plot = omdb?.Plot?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            ?: manualPlot

        val allTags = omdb?.Genre?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: manualTags

        val actorsList = omdb?.Actors?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // imdbRating is a "X.X" string out of 10 - Cloudstream expects an Int out of 100.
        val ratingInt = omdb?.imdbRating
            ?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            ?.toDoubleOrNull()
            ?.let { (it * 10).toInt() }

        // Runtime comes back as e.g. "98 min" - Cloudstream's duration field wants minutes as an Int.
        val durationInt = omdb?.Runtime
            ?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        val recommendations = extractRecommendations(doc)

        return newMovieLoadResponse(mediaName, url, TvType.Movie, url) {
            this.posterUrl = finalPosterUrl
            this.backgroundPosterUrl = finalPosterUrl
            this.year = yearInt
            this.plot = plot
            this.tags = allTags
            this.rating = ratingInt
            this.duration = durationInt
            if (actorsList.isNotEmpty()) {
                this.actors = actorsList.map { ActorData(Actor(it)) }
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
    ): Boolean {
        val doc = app.get(data, verify = false).document
        val extractedUrls = mutableSetOf<String>()

        val imdbId = extractImdbId(doc)
        if (imdbId != null) {
            fetchOpenSubtitles(imdbId).forEach { subtitleCallback(it) }
        }

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

        val collectedLinks = mutableListOf<ExtractorLink>()
        val collectingCallback: (ExtractorLink) -> Unit = { link -> collectedLinks.add(link) }

        for (videoUrl in extractedUrls) {
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
                Film1kExtractor().getUrl(videoUrl, mainUrl, subtitleCallback, collectingCallback)
            }
        }

        var emitted = false
        val sortedLinks = collectedLinks.sortedByDescending { it.type == ExtractorLinkType.M3U8 }

        for (link in sortedLinks) {
            if (!emitted) {
                callback.invoke(
                    newExtractorLink(
                        name = "Film1k",
                        source = "Film1k",
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted = true
            }
        }

        return extractedUrls.isNotEmpty()
    }
}

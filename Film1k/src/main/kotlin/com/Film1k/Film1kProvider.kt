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

data class OmdbResponse(
    val Title: String? = null,
    val Year: String? = null,
    val Plot: String? = null,
    val Poster: String? = null,
    val Genre: String? = null,
    val Actors: String? = null,
    val Response: String? = null
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

    // Stremio community OpenSubtitles addon - no API key required.
    private val openSubtitlesBaseUrl = "https://opensubtitles-v3.strem.io/subtitles/movie"
    private val openSubtitlesMaxResults = 10

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Erotic Movies",
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homeDoc = app.get("$mainUrl/", verify = false).document
        val usaDoc = app.get("$mainUrl/tag/usa", verify = false).document
        val nintiesDoc = app.get("$mainUrl/tag/1990s", verify = false).document

        val homePageList = mutableListOf<HomePageList>()

        val latestTitle = homeDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "Latest Movies"
        val latestItems = parseArticles(homeDoc)
        if (latestItems.isNotEmpty()) {
            homePageList.add(HomePageList(latestTitle, latestItems, isHorizontalImages = isHorizontalImages))
        }

        val usaTitle = usaDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "USA Movies"
        val usaItems = parseArticles(usaDoc)
        if (usaItems.isNotEmpty()) {
            homePageList.add(HomePageList(usaTitle, usaItems, isHorizontalImages = isHorizontalImages))
        }

        val nintiesTitle = nintiesDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "1990s Movies"
        val nintiesItems = parseArticles(nintiesDoc)
        if (nintiesItems.isNotEmpty()) {
            homePageList.add(HomePageList(nintiesTitle, nintiesItems, isHorizontalImages = isHorizontalImages))
        }

        val usaUrls = usaItems.map { it.url }.toSet()
        val intersectionItems = nintiesItems.filter { usaUrls.contains(it.url) }
        if (intersectionItems.isNotEmpty()) {
            homePageList.add(HomePageList("1990s USA Movies", intersectionItems, isHorizontalImages = isHorizontalImages))
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val page1Url = "$mainUrl/?s=$query"
        val page2Url = "$mainUrl/page/2?s=$query"

        val page1Items = try {
            parseArticles(app.get(page1Url, verify = false).document)
        } catch (e: Exception) {
            emptyList()
        }

        val page2Items = try {
            parseArticles(app.get(page2Url, verify = false).document)
        } catch (e: Exception) {
            emptyList()
        }

        val seenUrls = mutableSetOf<String>()
        return (page1Items + page2Items).filter { seenUrls.add(it.url) }
    }

    private suspend fun parseArticles(doc: Document): List<SearchResponse> = coroutineScope {
        val articles = doc.select("#Ez-Wp > div > div > div > main > section > article")
        articles.map { article ->
            async {
                val aHeader = article.selectFirst("header > a") ?: return@async null
                val mediaUrl = fixUrl(aHeader.attr("href"))
                if (mediaUrl.isBlank()) return@async null

                val rawName = article.selectFirst("header > a > h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: aHeader.text().trim()
                val mediaName = cleanTitle(rawName)

                val imgEl = article.selectFirst("header > a > figure > img")

                val manualPosterUrl = imgEl?.let { el ->
                    el.attr("data-src").takeIf { it.isNotBlank() }
                        ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                        ?: el.attr("src").takeIf { it.isNotBlank() }
                }?.let { fixUrl(it) }

                // Listing pages have no IMDb link on them (only the detail page does), so this
                // matches OMDb by title/year instead of crawling every item's page just to
                // learn an id. A year-confidence check below guards against title collisions.
                val (titleOnly, year) = extractYearAndTitle(mediaName)
                val omdb = fetchOmdbByTitle(titleOnly, year)

                val posterUrl = omdb
                    ?.takeIf { yearMatches(year, it.Year) }
                    ?.Poster
                    ?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
                    ?: manualPosterUrl

                newMovieSearchResponse(mediaName, mediaUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun extractImdbId(doc: Document): String? {
        val href = doc.selectFirst("a[href*=imdb.com/title/]")?.attr("href") ?: return null
        return Regex("tt\\d+").find(href)?.value
    }

    private suspend fun fetchOmdbData(imdbId: String): OmdbResponse? {
        for (key in omdbApiKeys) {
            try {
                val responseText = app.get(
                    "https://www.omdbapi.com/?i=$imdbId&apikey=$key&plot=full",
                    verify = false
                ).text
                val parsed = tryParseJson<OmdbResponse>(responseText) ?: continue
                if (parsed.Response?.equals("True", ignoreCase = true) == true) {
                    return parsed
                }
            } catch (e: Exception) {
                // this key failed or request errored, try the next key
                continue
            }
        }
        return null
    }

    // For listing pages (homepage/search) there is no IMDb link to key off of yet,
    // so fall back to a title (+ year, if we can parse one out) lookup instead.
    private fun extractYearAndTitle(name: String): Pair<String, String?> {
        val match = Regex("\\((\\d{4})\\)\\s*$").find(name) ?: return name to null
        val year = match.groupValues[1]
        val titleOnly = name.substring(0, match.range.first).trim()
        return titleOnly.ifBlank { name } to year
    }

    // Guards against title collisions: only trust an OMDb match if its Year is within
    // 1 year of the year we parsed off the scraped title. If we couldn't parse a year
    // at all, there's nothing to check against, so the match is accepted as-is.
    private fun yearMatches(expectedYear: String?, omdbYear: String?): Boolean {
        if (expectedYear == null) return true
        val expectedYearNum = expectedYear.toIntOrNull() ?: return true
        val omdbYearNum = omdbYear?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() } ?: return false
        return kotlin.math.abs(omdbYearNum - expectedYearNum) <= 1
    }

    private suspend fun fetchOmdbByTitle(title: String, year: String? = null): OmdbResponse? {
        if (title.isBlank()) return null
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val yearParam = year?.let { "&y=$it" } ?: ""

        for (key in omdbApiKeys) {
            try {
                val responseText = app.get(
                    "https://www.omdbapi.com/?t=$encodedTitle$yearParam&apikey=$key&plot=short",
                    verify = false
                ).text
                val parsed = tryParseJson<OmdbResponse>(responseText) ?: continue
                if (parsed.Response?.equals("True", ignoreCase = true) == true) {
                    return parsed
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    private suspend fun fetchOpenSubtitles(imdbId: String): List<SubtitleFile> {
        val requestUrl = "$openSubtitlesBaseUrl/$imdbId.json"

        val responseText = try {
            app.get(requestUrl).text
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = tryParseJson<StremioSubtitlesResponse>(responseText) ?: return emptyList()

        // English only ("eng" is the ISO 639-2 code this addon uses), most popular first.
        return parsed.subtitles
            ?.filter { it.lang.equals("eng", ignoreCase = true) && !it.url.isNullOrBlank() }
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

            val imgEl = aTag.selectFirst("figure img")
            val posterUrl = imgEl?.let { el ->
                el.attr("data-src").takeIf { it.isNotBlank() }
                    ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                    ?: el.attr("src").takeIf { it.isNotBlank() }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, verify = false).document

        // ---- Manual extraction (used as fallback if no IMDb id / OMDb lookup fails) ----
        val imgEl = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
        val manualPoster = imgEl?.let { el ->
            el.attr("data-src").takeIf { it.isNotBlank() }
                ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val manualPosterUrl = (manualPoster ?: ogPoster)?.let { fixUrl(it) }

        val manualRawName = imgEl?.attr("alt")?.takeIf { it.isNotBlank() }
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

        val mediaName = omdb?.Title?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            ?: manualMediaName

        val posterUrl = omdb?.Poster?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
            ?: manualPosterUrl

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

        val recommendations = extractRecommendations(doc)

        return newMovieLoadResponse(mediaName, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = posterUrl
            this.plot = plot
            this.tags = allTags
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

        // ---- English subtitles via OpenSubtitles, sorted by popularity (download_count) ----
        val imdbId = extractImdbId(doc)
        if (imdbId != null) {
            fetchOpenSubtitles(imdbId).forEach { subtitleCallback(it) }
        }

        fun realSrc(el: Element): String? {
            return el.attr("data-src").takeIf { it.isNotBlank() }
                ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }

        doc.select("#my-video > source").forEach { source ->
            val src = realSrc(source)
            if (!src.isNullOrBlank()) extractedUrls.add(fixUrl(src))
        }

        doc.select("#video-op-a > div > iframe").forEach { iframe ->
            val src = realSrc(iframe)
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

        // Deduplicate thoroughly by only emitting ONE optimal link. 
        // Prioritizes M3U8 (which internally handles all qualities).
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
                emitted = true // Break after the first valid link is sent
            }
        }

        return extractedUrls.isNotEmpty()
    }
}

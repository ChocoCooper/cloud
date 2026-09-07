package com.JavHub

import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

private fun String.decodeHtmlEntities(): String = Parser.unescapeEntities(this, false)

// Converts direct video streams to master playlists if needed
private fun String.toPlaylistM3u8(): String {
    val perResolutionSegment = Regex("""/(?:\d{3,5}x\d{3,5}|\d{3,4}p)/[^/]+\.m3u8""")
    return when {
        perResolutionSegment.containsMatchIn(this) ->
            perResolutionSegment.replace(this, "/playlist.m3u8")
        this.contains("/video.m3u8") ->
            this.replace(Regex("""/video\.m3u8(\?.*)?$"""), "/playlist.m3u8$1")
        else -> this
    }
}

data class LoadData(
    val url: String,
    val poster: String? = null,
    val code: String? = null
)

data class JavHDAjaxResponse(
    @JsonProperty("html") val html: String? = null
)

class JavHubProvider : MainAPI() {
    override var mainUrl              = "https://javhd.today"
    override var name                 = "JavHub"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val subtitleCatUrl = "https://www.subtitlecat.com"
    private val missAvUrl      = "https://missav.ws"

    override val mainPage = mainPageOf(
        "$mainUrl/channel/madonna/" to "Madonna",
        "$mainUrl/channel/nagae-style-new/" to "Nagae Style",
        "$mainUrl/channel/attackers-new/" to "Attackers",
        "$mainUrl/channel/moodyz-new/" to "Moodyz"
    )

    private val JUNK_WORDS = Regex(
        """\b(mosaic|english\s*sub(?:title)?s?|uncensored|engsub)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun cleanTitleText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace(JUNK_WORDS, "")
            .trim('-', '_', '|', ' ', '[', ']')
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .ifBlank { null }
    }

    private fun extractCode(text: String?): String? {
        val cleanText = cleanTitleText(text) ?: return null
        val regex = Regex("""\b([a-zA-Z0-9]{2,8}(?:-[a-zA-Z0-9]{2,8})?-\d{2,6})\b""")
        return regex.find(cleanText)?.value?.uppercase()
    }

    private fun extractMissAvDescription(doc: Document): String? {
        val descEl = doc.selectFirst("div.mb-1.text-secondary.break-all")
            ?: doc.selectFirst("div.text-secondary.break-all")
            ?: doc.selectFirst("meta[property=og:description]")

        val text = if (descEl?.tagName() == "meta") {
            descEl.attr("content")
        } else {
            descEl?.text()
        }

        return text?.trim()?.decodeHtmlEntities()?.ifBlank { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url, headers = browserHeaders, cacheTime = 0).document

        val items = document.select("ul.videos div.video a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { extractCode(it.name) ?: it.url }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        
        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Referer" to "$mainUrl/"
        ) + browserHeaders

        return coroutineScope {
            (1..2).map { page ->
                async {
                    runCatching {
                        val url = "$mainUrl/search/video/?s=$encoded&page=$page&ajax=1"
                        val jsonResponse = app.get(url, headers = ajaxHeaders, timeout = 15).text
                        val ajaxData = runCatching { parseJson<JavHDAjaxResponse>(jsonResponse) }.getOrNull()
                        val htmlPayload = ajaxData?.html ?: return@runCatching emptyList()

                        val document = Jsoup.parse(htmlPayload)

                        document.select("ul.videos div.video a:has(img)")
                            .mapNotNull { it.toSearchResult() }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { extractCode(it.name) ?: it.url }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = this.attr("href")
        if (rawHref.isBlank()) return null

        val href = if (rawHref.startsWith("/")) "$mainUrl$rawHref" else rawHref
        if (href.contains("/search/") || href.contains("/channel/") || href.contains("/tag/")) return null

        val imgEl = this.selectFirst("img")

        val titleCandidate = listOfNotNull(
            this.attr("title").ifBlank { null },
            imgEl?.attr("alt")?.ifBlank { null },
            this.selectFirst(".title, h3, .video-title")?.text()?.ifBlank { null },
            this.text().ifBlank { null }
        ).firstNotNullOfOrNull { cleanTitleText(it) }

        val title = titleCandidate ?: return null
        val code = extractCode(title) ?: return null

        if (!title.uppercase().startsWith(code.uppercase())) return null

        val posterUrl = fixUrlNull(
            imgEl?.attr("src")?.ifBlank { null } ?: imgEl?.attr("data-src")
        )

        // FIX: url must be a static static string (href) for Cloudstream's Continue Watching (Watch Sync) to work. 
        // DO NOT pass the JSON string here.
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "https://javhd.today/")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // If url is somehow still JSON from an older cache, safely fall back
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull()
        var videoUrl = loadData?.url ?: url

        var document = app.get(videoUrl, headers = browserHeaders).document

        val canonicalHref = document.selectFirst("link[rel=canonical]")?.attr("href")
            ?: document.selectFirst("meta[property=og:url]")?.attr("content")

        if (!canonicalHref.isNullOrBlank() && canonicalHref != videoUrl) {
            runCatching {
                val canonicalDoc = app.get(canonicalHref, headers = browserHeaders).document
                if (canonicalDoc.selectFirst("h1") != null) {
                    document = canonicalDoc
                    videoUrl = canonicalHref
                }
            }
        }

        val rawTitle = document.selectFirst("h1")?.text()?.decodeHtmlEntities() ?: "Unknown"

        val freshTitle = cleanTitleText(rawTitle)
            ?: cleanTitleText(document.selectFirst("meta[property=og:title]")?.attr("content"))

        val title = freshTitle ?: loadData?.code ?: "Unknown"

        val code = extractCode(freshTitle) ?: loadData?.code ?: extractCode(videoUrl)
        val cleanCode = code?.lowercase()

        val verticalPoster = loadData?.poster ?: document.selectFirst("div.video-player img")?.attr("src")?.ifBlank { null }
        val horizontalPoster = cleanCode?.let { "https://fourhoi.com/$it/cover-n.jpg" }

        var fetchedDescription: String? = null
        if (!cleanCode.isNullOrBlank()) {
            val missAvSlugCandidates = listOf(
                cleanCode,
                "$cleanCode-uncensored-leak",
                "$cleanCode-english-subtitle"
            )

            for (slug in missAvSlugCandidates) {
                val found = runCatching {
                    val missAvDoc = app.get(
                        "$missAvUrl/en/$slug",
                        timeout = 10,
                        headers = browserHeaders
                    ).document
                    extractMissAvDescription(missAvDoc)
                }.getOrNull()

                if (!found.isNullOrBlank()) {
                    fetchedDescription = found
                    break
                }
            }
        }

        val plotText = fetchedDescription?.ifBlank { null } ?: rawTitle
        
        // FIX: The JSON payload is purely assigned to `dataUrl` so `loadLinks` can use it without breaking Watch Sync.
        val loadDataJson = LoadData(videoUrl, verticalPoster, code).toJson()

        return newMovieLoadResponse(title, videoUrl, TvType.Movie, loadDataJson) {
            this.posterUrl = verticalPoster
            this.posterHeaders = mapOf("User-Agent" to browserHeaders["User-Agent"]!!)
            this.backgroundPosterUrl = horizontalPoster
            this.plot = plotText
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val loadData = runCatching { parseJson<LoadData>(data) }.getOrNull()
        val videoUrl = loadData?.url ?: data

        var code = loadData?.code
        if (code == null) {
            val document = app.get(videoUrl, timeout = 15, headers = browserHeaders).document
            val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: ""
            code = extractCode(rawTitle)
        }

        if (code == null) return false
        val cleanCode = code.lowercase()
        val foundStream = AtomicBoolean(false)

        coroutineScope {

            // 1. MISSAV (Primary Extractor)
            val variantsMissAv = listOf(
                cleanCode to "MissAV",
                "$cleanCode-uncensored-leak" to "MissAV [Uncensored]",
                "$cleanCode-english-subtitle" to "MissAV [English Subtitle]"
            )

            variantsMissAv.forEach { (vCode, sourceName) ->
                launch {
                    runCatching {
                        val missAvVideoUrl = "https://missav.ws/en/$vCode"
                        val response = app.get(missAvVideoUrl, timeout = 15, headers = browserHeaders)

                        if (response.code == 200) {
                            val unpackedText = getAndUnpack(response.text)
                            var finalLink = Regex("""source\s*[:=]\s*['"](.*?)['"]""").find(unpackedText)?.groupValues?.get(1)

                            if (finalLink?.startsWith("aHR0c") == true) {
                                finalLink = String(Base64.decode(finalLink, Base64.DEFAULT))
                            }

                            if (finalLink.isNullOrBlank()) {
                                val b64Match = Regex("""['"](aHR0c[a-zA-Z0-9+/=]+)['"]""").find(unpackedText)?.groupValues?.get(1)
                                if (b64Match != null) {
                                    val decoded = String(Base64.decode(b64Match, Base64.DEFAULT))
                                    if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                                        finalLink = decoded
                                    }
                                }
                            }

                            if (finalLink.isNullOrBlank()) {
                                finalLink = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(unpackedText.replace("\\/", "/"))?.value
                            }

                            if (!finalLink.isNullOrBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        sourceName,
                                        sourceName,
                                        finalLink.toPlaylistM3u8(),
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://missav.ws/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundStream.set(true)
                            }
                        }
                    }
                }
            }

            // 2. JABLE (Secondary Extractor)
            launch {
                runCatching {
                    val urlJable = "https://jable.tv/videos/$cleanCode/?lang=en"
                    val responseText = app.get(urlJable, timeout = 15, headers = browserHeaders).text

                    val hlsUrl = Regex("""hlsUrl\s*=\s*['"](https?://[^'"]+\.m3u8)['"]""").find(responseText)?.groupValues?.get(1)
                        ?: Regex("""(https?://[^\s'\"<>]+?\.m3u8[^\s'\"<>]*)""").find(responseText.replace("\\/", "/"))?.groupValues?.get(1)

                    if (!hlsUrl.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "Jable",
                                "Jable",
                                hlsUrl.toPlaylistM3u8(),
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://jable.tv/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundStream.set(true)
                    }
                }
            }

            // 3. SUBTITLES
            launch {
                runCatching {
                    val searchDoc = app.get("$subtitleCatUrl/index.php?search=$code", timeout = 15, headers = browserHeaders).document
                    val foundSubs = mutableListOf<String>()

                    val subtitlePageLinks = searchDoc.select("table.sub-table > tbody > tr > td > a[href^=\"subs/\"]")
                        .filter { it.text().contains(code, ignoreCase = true) }
                        .mapNotNull { el ->
                            el.attr("href").let { href ->
                                if (href.startsWith("http")) href else "$subtitleCatUrl/$href"
                            }
                        }
                        .distinct()

                    subtitlePageLinks.forEach { subPageUrl ->
                        runCatching {
                            val subPageDoc = app.get(subPageUrl, timeout = 10, headers = browserHeaders).document
                            val enHref = subPageDoc.selectFirst("div.sub-single > span > a#download_en")?.attr("href")

                            if (!enHref.isNullOrBlank()) {
                                val fullEnUrl = if (enHref.startsWith("http")) enHref else "$subtitleCatUrl/$enHref"
                                if (!foundSubs.contains(fullEnUrl)) {
                                    subtitleCallback(SubtitleFile("English", fullEnUrl))
                                    foundSubs.add(fullEnUrl)
                                }
                            }
                        }
                    }
                }
            }
        }

        return foundStream.get()
    }
}

package com.hexated

import com.hexated.SoraStream.Companion.baymovies
import com.hexated.SoraStream.Companion.consumetCrunchyrollAPI
import com.hexated.SoraStream.Companion.filmxyAPI
import com.hexated.SoraStream.Companion.gdbot
import com.hexated.SoraStream.Companion.kamyrollAPI
import com.hexated.SoraStream.Companion.tvMoviesAPI
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getCaptchaToken
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.RequestBodyTypes
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URL

data class FilmxyCookies(
    val phpsessid: String? = null,
    val wLog: String? = null,
    val wSec: String? = null,
)

fun String.filterIframe(seasonNum: Int?, lastSeason: Int?, year: Int?): Boolean {
    return if (seasonNum != null) {
        if (lastSeason == 1) {
            this.contains(Regex("(?i)(S0?$seasonNum)|(Season\\s0?$seasonNum)|([0-9]{3,4}p)")) && !this.contains(
                "Download",
                true
            )
        } else {
            this.contains(Regex("(?i)(S0?$seasonNum)|(Season\\s0?$seasonNum)")) && !this.contains(
                "Download",
                true
            )
        }
    } else {
        this.contains("$year", true) && !this.contains("Download", true)
    }
}

fun String.filterMedia(title: String?, yearNum: Int?, seasonNum: Int?): Boolean {
    return if (seasonNum != null) {
        when {
            seasonNum > 1 -> this.contains(Regex("(?i)(Season\\s0?1-0?$seasonNum)|(S0?1-S?0?$seasonNum)")) && this.contains(
                "$title",
                true
            )
            else -> this.contains(Regex("(?i)(Season\\s0?1)|(S0?1)")) && this.contains(
                "$title",
                true
            ) && this.contains("$yearNum")
        }
    } else {
        this.contains("$title", true) && this.contains("$yearNum")
    }
}

fun Document.getMirrorLink(): String? {
    return this.select("div.mb-4 a").randomOrNull()
        ?.attr("href")
}

fun Document.getMirrorServer(server: Int): String {
    return this.select("div.text-center a:contains(Server $server)").attr("href")
}

suspend fun extractMirrorUHD(url: String, ref: String): String? {
    var baseDoc = app.get(fixUrl(url, ref)).document
    var downLink = baseDoc.getMirrorLink()
    run lit@{
        (1..2).forEach {
            if(downLink != null) return@lit
            val server = baseDoc.getMirrorServer(it.plus(1))
            baseDoc = app.get(fixUrl(server, ref)).document
            downLink = baseDoc.getMirrorLink()
        }
    }
    if(downLink?.contains(".mkv") == true || downLink?.contains(".mp4") == true) return downLink
    val downPage = app.get(downLink ?: return null).document
    return downPage.selectFirst("form[method=post] a.btn.btn-success")
        ?.attr("onclick")?.substringAfter("Openblank('")?.substringBefore("')") ?: run {
        val mirror = downPage.selectFirst("form[method=post] a.btn.btn-primary")
            ?.attr("onclick")?.substringAfter("Openblank('")?.substringBefore("')")
        app.get(
            mirror ?: return null
        ).document.selectFirst("script:containsData(input.value =)")
            ?.data()?.substringAfter("input.value = '")?.substringBefore("';")
    }
}

suspend fun extractBackupUHD(url: String): String? {
    val resumeDoc = app.get(url)

    val script = resumeDoc.document.selectFirst("script:containsData(FormData.)")?.data()

    val ssid = resumeDoc.cookies["PHPSESSID"]
    val baseIframe = getBaseUrl(url)
    val fetchLink =
        script?.substringAfter("fetch('")?.substringBefore("',")?.let { fixUrl(it, baseIframe) }
    val token = script?.substringAfter("'token', '")?.substringBefore("');")

    val body = FormBody.Builder()
        .addEncoded("token", "$token")
        .build()
    val cookies = mapOf("PHPSESSID" to "$ssid")

    val result = app.post(
        fetchLink ?: return null,
        requestBody = body,
        headers = mapOf(
            "Accept" to "*/*",
            "Origin" to baseIframe,
            "Sec-Fetch-Site" to "same-origin"
        ),
        cookies = cookies,
        referer = url
    ).text
    return tryParseJson<UHDBackupUrl>(result)?.url
}

suspend fun extractGdbot(url: String): String? {
    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    )
    val res = app.get(
        "$gdbot/", headers = headers
    )
    val token = res.document.selectFirst("input[name=_token]")?.attr("value")
    val cookiesSet = res.headers.filter { it.first == "set-cookie" }
    val xsrf =
        cookiesSet.find { it.second.contains("XSRF-TOKEN") }?.second?.substringAfter("XSRF-TOKEN=")
            ?.substringBefore(";")
    val session =
        cookiesSet.find { it.second.contains("gdtot_proxy_session") }?.second?.substringAfter("gdtot_proxy_session=")
            ?.substringBefore(";")

    val cookies = mapOf(
        "gdtot_proxy_session" to "$session",
        "XSRF-TOKEN" to "$xsrf"
    )
    val requestFile = app.post(
        "$gdbot/file", data = mapOf(
            "link" to url,
            "_token" to "$token"
        ), headers = headers, referer = "$gdbot/", cookies = cookies
    ).document

    return requestFile.selectFirst("div.mt-8 a.float-right")?.attr("href")
}

suspend fun extractDirectDl(url: String): String? {
    val iframe = app.get(url).document.selectFirst("li.flex.flex-col.py-6 a:contains(Direct DL)")
        ?.attr("href")
    val request = app.get(iframe ?: return null)
    val driveDoc = request.document
    val token = driveDoc.select("section#generate_url").attr("data-token")
    val uid = driveDoc.select("section#generate_url").attr("data-uid")

    val ssid = request.cookies["PHPSESSID"]
    val body =
        """{"type":"DOWNLOAD_GENERATE","payload":{"uid":"$uid","access_token":"$token"}}""".toRequestBody(
            RequestBodyTypes.JSON.toMediaTypeOrNull()
        )

    val json = app.post(
        "https://rajbetmovies.com/action", requestBody = body, headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Cookie" to "PHPSESSID=$ssid",
            "X-Requested-With" to "xmlhttprequest"
        ), referer = request.url
    ).text
    return tryParseJson<DirectDl>(json)?.download_url
}

suspend fun extractDrivebot(url: String): String? {
    val iframeDrivebot =
        app.get(url).document.selectFirst("li.flex.flex-col.py-6 a:contains(Drivebot)")
            ?.attr("href") ?: return null
    return getDrivebotLink(iframeDrivebot)
}

suspend fun extractGdflix(url: String): String? {
    val iframeGdflix =
        app.get(url).document.selectFirst("li.flex.flex-col.py-6 a:contains(GDFlix Direct)")
            ?.attr("href") ?: return null
    val base = getBaseUrl(iframeGdflix)

    val gdfDoc = app.get(iframeGdflix).document.selectFirst("script:containsData(replace)")?.data()
        ?.substringAfter("replace(\"")
        ?.substringBefore("\")")?.let {
            app.get(fixUrl(it, base)).document
        }
    val iframeDrivebot2 = gdfDoc?.selectFirst("a.btn.btn-outline-warning")?.attr("href")

    return getDrivebotLink(iframeDrivebot2)
}

suspend fun getDrivebotLink(url: String?): String? {
    val driveDoc = app.get(url ?: return null)

    val ssid = driveDoc.cookies["PHPSESSID"]
    val script = driveDoc.document.selectFirst("script:containsData(var formData)")?.data()

    val baseUrl = getBaseUrl(url)
    val token = script?.substringAfter("'token', '")?.substringBefore("');")
    val link =
        script?.substringAfter("fetch('")?.substringBefore("',").let { "$baseUrl$it" }

    val body = FormBody.Builder()
        .addEncoded("token", "$token")
        .build()
    val cookies = mapOf("PHPSESSID" to "$ssid")

    val result = app.post(
        link,
        requestBody = body,
        headers = mapOf(
            "Accept" to "*/*",
            "Origin" to baseUrl,
            "Sec-Fetch-Site" to "same-origin"
        ),
        cookies = cookies,
        referer = url
    ).text
    return tryParseJson<DriveBotLink>(result)?.url
}

suspend fun extractOiya(url: String, quality: String): String? {
    val doc = app.get(url).document
    return doc.selectFirst("div.wp-block-button a:matches((?i)$quality)")?.attr("href")
        ?: doc.selectFirst("div.wp-block-button a")?.attr("href")
}

suspend fun extractCovyn(url: String?): Pair<String?, String?>? {
    val request = session.get(url ?: return null, referer = "${tvMoviesAPI}/")
    val filehosting = session.baseClient.cookieJar.loadForRequest(url.toHttpUrl())
        .find { it.name == "filehosting" }?.value
    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Connection" to "keep-alive",
        "Cookie" to "filehosting=$filehosting",
    )

    val iframe = request.document.findTvMoviesIframe()
    delay(10500)
    val request2 = session.get(
        iframe ?: return null, referer = url, headers = headers
    )

    val iframe2 = request2.document.findTvMoviesIframe()
    delay(10500)
    val request3 = session.get(
        iframe2 ?: return null, referer = iframe, headers = headers
    )

    val response = request3.document
    val videoLink = response.selectFirst("button.btn.btn--primary")?.attr("onclick")
        ?.substringAfter("location = '")?.substringBefore("';")?.let {
            app.get(
                it, referer = iframe2, headers = headers
            ).url
        }
    val size = response.selectFirst("ul.row--list li:contains(Filesize) span:last-child")
        ?.text()

    return Pair(videoLink, size)
}

suspend fun invokeSmashy1(
    player: String,
    url: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val doc = app.get(url ?: return).document
    val script = doc.selectFirst("script:containsData(secret)")?.data() ?: return
    val secret = script.substringAfter("secret = \"").substringBefore("\";").let { base64Decode(it) }
    val key = script.substringAfter("token = \"").substringBefore("\";")
    val source = app.get(
        "$secret$key",
        headers = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )
    ).parsedSafe<Smashy1Source>() ?: return

    val videoUrl = base64Decode(source.file ?: return)
    if(videoUrl.contains(".m3u8")) {
        M3u8Helper.generateM3u8(
            "Smashy ($player)",
            videoUrl,
            ""
        ).forEach(callback)
        source.tracks?.map { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    sub.label ?: return@map null,
                    sub.file ?: return@map null
                )
            )
        }
    } else {
        return
    }
}

suspend fun invokeSmashy2(
    player: String,
    url: String?,
    callback: (ExtractorLink) -> Unit,
) {
    val base = getBaseUrl(url ?: return)
    val doc = app.get(url).document
    val script = doc.selectFirst("script:containsData(playlist:)")?.data() ?: return
    Regex("""file:[\n\s]+?"(\S+?.m3u8)",[\n\s]+label:"(\S+)",""").findAll(script).map {
        it.groupValues[1] to it.groupValues[2]
    }.toList().map { (link, quality) ->
        callback.invoke(
            ExtractorLink(
                "Smashy ($player)",
                "Smashy ($player)",
                link,
                base,
                quality.toIntOrNull() ?: Qualities.Unknown.value,
                isM3u8 = link.contains(".m3u8"),
            )
        )
    }
}

fun getDirectGdrive(url: String): String {
    return if (url.contains("&export=download")) {
        url
    } else {
        "https://drive.google.com/uc?id=${
            url.substringAfter("/d/").substringBefore("/")
        }&export=download"
    }
}

suspend fun bypassOuo(url: String?): String? {
    var res = session.get(url ?: return null)
    run lit@{
        (1..2).forEach { _ ->
            if (res.headers["location"] != null) return@lit
            val document = res.document
            val nextUrl = document.select("form").attr("action")
            val data = document.select("form input").mapNotNull {
                it.attr("name") to it.attr("value")
            }.toMap().toMutableMap()
            val captchaKey =
                document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                    .attr("src").substringAfter("render=")
            val token = getCaptchaToken(url, captchaKey)
            data["x-token"] = token ?: ""
            res = session.post(
                nextUrl,
                data = data,
                headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                allowRedirects = false
            )
        }
    }

    return res.headers["location"]
}

suspend fun fetchingKaizoku(
    domain: String,
    postId: String,
    data: List<String>,
    ref: String
): NiceResponse {
    return app.post(
        "$domain/wp-admin/admin-ajax.php",
        data = mapOf(
            "action" to "DDL",
            "post_id" to postId,
            "div_id" to data.first(),
            "tab_id" to data[1],
            "num" to data[2],
            "folder" to data.last()
        ),
        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        referer = ref
    )
}

fun String.splitData(): List<String> {
    return this.substringAfterLast("DDL(").substringBefore(")").split(",")
        .map { it.replace("'", "").trim() }
}

suspend fun bypassFdAds(url: String?): String? {
    val directUrl =
        app.get(url ?: return null, verify = false).document.select("a#link").attr("href")
            .substringAfter("/go/")
            .let { base64Decode(it) }
    val doc = app.get(directUrl, verify = false).document
    val lastDoc = app.post(
        doc.select("form#landing").attr("action"),
        data = mapOf("go" to doc.select("form#landing input").attr("value")),
        verify = false
    ).document
    val json = lastDoc.select("form#landing input[name=newwpsafelink]").attr("value")
        .let { base64Decode(it) }
    val finalJson =
        tryParseJson<FDAds>(json)?.linkr?.substringAfter("redirect=")?.let { base64Decode(it) }
    return tryParseJson<Safelink>(finalJson)?.safelink
}

suspend fun bypassHrefli(url: String): String? {
    var res = app.get(url.removePrefix("https://href.li/?"))
    (1..2).forEach { _ ->
        val document = res.document
        val nextUrl = document.select("form").attr("action")
        val data = document.select("form input").mapNotNull {
            it.attr("name") to it.attr("value")
        }.toMap()
        res = app.post(
            nextUrl,
            data = data,
        )
    }
    val script = res.document.selectFirst("script:containsData(verify_button)")?.data()
    val goUrl = script?.substringAfter("\"href\",\"")?.substringBefore("\")")
    val cookies =
        Regex("sumitbot_\\('(\\S+?)',\n|.?'(\\S+?)',").findAll(script ?: return null).map {
            it.groupValues[2]
        }.toList().let {
            mapOf(it.first() to it.last())
        }.ifEmpty { return null }

    return app.get(
        goUrl ?: return null,
        cookies = cookies
    ).document.selectFirst("meta[http-equiv=refresh]")?.attr("content")?.substringAfter("url=")
}

suspend fun getTvMoviesServer(url: String, season: Int?, episode: Int?): Pair<String, String?>? {

    val req = app.get(url)
    if (!req.isSuccessful) return null
    val doc = req.document

    return if (season == null) {
        doc.select("table.wp-block-table tr:last-child td:first-child").text() to
                doc.selectFirst("table.wp-block-table tr a")?.attr("href").let { link ->
                    app.get(link ?: return null).document.select("div#text-url a")
                        .mapIndexed { index, element ->
                            element.attr("href") to element.parent()?.textNodes()?.getOrNull(index)
                                ?.text()
                        }.filter { it.second?.contains("Subtitles", true) == false }
                        .map { it.first }
                }.lastOrNull()
    } else {
        doc.select("div.vc_tta-panels div#Season-$season table.wp-block-table tr:last-child td:first-child")
            .text() to
                doc.select("div.vc_tta-panels div#Season-$season table.wp-block-table tr a")
                    .mapNotNull { ele ->
                        app.get(ele.attr("href")).document.select("div#text-url a")
                            .mapIndexed { index, element ->
                                element.attr("href") to element.parent()?.textNodes()
                                    ?.getOrNull(index)?.text()
                            }.find { it.second?.contains("Episode $episode", true) == true }?.first
                    }.lastOrNull()
    }
}

suspend fun getFilmxyCookies(imdbId: String? = null, season: Int? = null): FilmxyCookies? {

    val url = if (season == null) {
        "${filmxyAPI}/movie/$imdbId"
    } else {
        "${filmxyAPI}/tv/$imdbId"
    }
    val cookieUrl = "${filmxyAPI}/wp-admin/admin-ajax.php"

    val res = session.get(
        url,
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        ),
    )

    if (!res.isSuccessful) return FilmxyCookies()

    val userNonce =
        res.document.select("script").find { it.data().contains("var userNonce") }?.data()?.let {
            Regex("var\\suserNonce.*?\"(\\S+?)\";").find(it)?.groupValues?.get(1)
        }

    var phpsessid = session.baseClient.cookieJar.loadForRequest(url.toHttpUrl())
        .first { it.name == "PHPSESSID" }.value

    session.post(
        cookieUrl,
        data = mapOf(
            "action" to "guest_login",
            "nonce" to "$userNonce",
        ),
        headers = mapOf(
            "Cookie" to "PHPSESSID=$phpsessid; G_ENABLED_IDPS=google",
            "X-Requested-With" to "XMLHttpRequest",
        )
    )

    val cookieJar = session.baseClient.cookieJar.loadForRequest(cookieUrl.toHttpUrl())
    phpsessid = cookieJar.first { it.name == "PHPSESSID" }.value
    val wLog =
        cookieJar.first { it.name == "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" }.value
    val wSec = cookieJar.first { it.name == "wordpress_sec_8bf9d5433ac88cc9a3a396d6b154cd01" }.value

    return FilmxyCookies(phpsessid, wLog, wSec)
}

fun Document.findTvMoviesIframe(): String? {
    return this.selectFirst("script:containsData(var seconds)")?.data()?.substringAfter("href='")
        ?.substringBefore("'>")
}

suspend fun searchKamyrollAnimeId(title: String): String? {
    return app.get(
        "$kamyrollAPI/content/v1/search",
        headers = getCrunchyrollToken(),
        params = mapOf(
            "query" to title,
            "channel_id" to "crunchyroll",
            "limit" to "10",
        )
    ).parsedSafe<KamyrollSearch>()?.items?.find { item ->
        item.items.any {
            (it.title?.contains(title, true) == true || it.slugTitle?.contains(
                "${title.fixTitle()}",
                true
            ) == true) && it.mediaType == "series"
        }
    }?.items?.firstOrNull()?.id
}

suspend fun searchCrunchyrollAnimeId(title: String): String? {
    val res = app.get("${consumetCrunchyrollAPI}/$title")
        .parsedSafe<ConsumetSearchResponse>()?.results
    return (if (res?.size == 1) {
        res.firstOrNull()
    } else {
        res?.find {
            (it.title?.contains(
                title,
                true
            ) == true || it.title.fixTitle()
                ?.contains("${title.fixTitle()}", true) == true) && it.type.equals("series")
        }
    })?.id
}

suspend fun getCrunchyrollToken(): Map<String, String> {
    val res = app.get(
        "$kamyrollAPI/auth/v1/token",
        params = mapOf(
            "device_id" to "com.service.data",
            "device_type" to "sorastream",
            "access_token" to "HMbQeThWmZq4t7w",
        )
    ).parsedSafe<KamyrollToken>()
    return mapOf(
        "Authorization" to "${res?.token_type} ${res?.access_token}"
    )
}

fun CrunchyrollDetails.findCrunchyrollId(
    title: String?,
    season: Int?,
    episode: Int?,
    epsTitle: String?
): List<Pair<String?, String?>?> {
    val sub = when (title) {
        "One Piece" -> this.episodes?.get("subbed13")?.matchingEpisode(episode) to "Raw"
        "Hunter x Hunter" -> this.episodes?.get("subbed5")?.matchingEpisode(episode) to "Raw"
        else -> this.episodes?.get("subbed$season")?.matchingEpisode(episode) to "Raw"
    }
    val dub = this.episodes?.get("English Dub$season")?.matchingEpisode(episode) to "English Dub"

    return listOf(sub, dub)
}

fun List<HashMap<String, String>>?.matchingEpisode(episode: Int?): String? {
    return this?.find {
        it["episode_number"] == "$episode" || indexOf(it).plus(1) == episode
    }?.get("id")
}

fun getEpisodeSlug(
    season: Int? = null,
    episode: Int? = null,
): Pair<String, String> {
    return if (season == null && episode == null) {
        "" to ""
    } else {
        (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
    }
}

fun getTitleSlug(title: String? = null): Pair<String?, String?> {
    return title.fixTitle()?.replace("-", ".") to title.fixTitle()?.replace("-", " ")
}

fun getIndexQuery(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null
): String {
    val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
    return if (season == null) {
        "$title $year"
    } else {
        "$title S${seasonSlug}E${episodeSlug}"
    }
}

fun searchIndex(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    year: Int? = null,
    response: String
): List<IndexMedia>? {
    val (dotSlug, spaceSlug) = getTitleSlug(title)
    val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
    val mimeType = arrayOf(
        "video/x-matroska",
        "video/mp4",
        "video/x-msvideo"
    )
    return tryParseJson<IndexSearch>(response)?.data?.files?.filter { media ->
        (if (season == null) {
            media.name?.contains("$year") == true
        } else {
            media.name?.contains(Regex("(?i)S${seasonSlug}.?E${episodeSlug}")) == true
        }) && media.name?.contains(
            "720p",
            true
        ) == false && (media.mimeType in mimeType) && (media.name.replace(
            "-",
            "."
        ).contains(
            "$dotSlug",
            true
        ) || media.name.replace(
            "-",
            " "
        ).contains("$spaceSlug", true))
    }?.distinctBy { it.name }
}

suspend fun getConfig(): BaymoviesConfig {
    val regex = """const country = "(.*?)";
const downloadtime = "(.*?)";
var arrayofworkers = (.*)""".toRegex()
    val js = app.get(
        "https://geolocation.zindex.eu.org/api.js",
        referer = "$baymovies/",
    ).text
    val match = regex.find(js) ?: throw ErrorLoadingException()
    val country = match.groupValues[1]
    val downloadTime = match.groupValues[2]
    val workers = tryParseJson<List<String>>(match.groupValues[3])
        ?: throw ErrorLoadingException()

    return BaymoviesConfig(country, downloadTime, workers)
}

// taken from https://github.com/821938089/cloudstream-extensions/blob/6e41697cbf816d2f57d9922d813c538e3192f708/PiousIndexProvider/src/main/kotlin/com/horis/cloudstreamplugins/PiousIndexProvider.kt#L175-L179
fun decodeIndexJson(json: String): String {
    val slug = json.reversed().substring(24)
    return base64Decode(slug.substring(0, slug.length - 20))
}
fun String?.fixTitle(): String? {
    return this?.replace(Regex("[!%:'?,]|( &)"), "")?.replace(" ", "-")?.lowercase()
        ?.replace("-–-", "-")
}

fun getLanguage(str: String): String {
    return if (str.contains("(in_ID)")) "Indonesian" else str
}

fun bytesToGigaBytes( number: Double ): Double = number / 1024000000

fun getKisskhTitle(str: String?): String? {
    return str?.replace(Regex("[^a-zA-Z0-9]"), "-")
}

fun getQuality(str: String): Int {
    return when (str) {
        "360p" -> Qualities.P240.value
        "480p" -> Qualities.P360.value
        "720p" -> Qualities.P480.value
        "1080p" -> Qualities.P720.value
        "1080p Ultra" -> Qualities.P1080.value
        else -> getQualityFromName(str)
    }
}

fun getGMoviesQuality(str: String): Int {
    return when {
        str.contains("480P", true) -> Qualities.P480.value
        str.contains("720P", true) -> Qualities.P720.value
        str.contains("1080P", true) -> Qualities.P1080.value
        str.contains("4K", true) -> Qualities.P2160.value
        else -> Qualities.Unknown.value
    }
}

fun getSoraQuality(quality: String): Int {
    return when (quality) {
        "GROOT_FD" -> Qualities.P360.value
        "GROOT_LD" -> Qualities.P480.value
        "GROOT_SD" -> Qualities.P720.value
        "GROOT_HD" -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }
}

fun getFDoviesQuality(str: String): String {
    return when {
        str.contains("1080P", true) -> "1080P"
        str.contains("4K", true) -> "4K"
        else -> ""
    }
}

fun getVipLanguage(str: String): String {
    return when (str) {
        "in_ID" -> "Indonesian"
        "pt" -> "Portuguese"
        else -> str.split("_").first().let {
            SubtitleHelper.fromTwoLettersToLanguage(it).toString()
        }
    }
}

fun getDbgoLanguage(str: String): String {
    return when (str) {
        "Русский" -> "Russian"
        "Українська" -> "Ukrainian"
        else -> str
    }
}

fun String.encodeUrl() : String {
    val url = URL(this)
    val uri = URI(url.protocol, url.userInfo, url.host, url.port, url.path, url.query, url.ref)
    return uri.toURL().toString()
}

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

fun decryptStreamUrl(data: String): String {

    fun getTrash(arr: List<String>, item: Int): List<String> {
        val trash = ArrayList<List<String>>()
        for (i in 1..item) {
            trash.add(arr)
        }
        return trash.reduce { acc, list ->
            val temp = ArrayList<String>()
            acc.forEach { ac ->
                list.forEach { li ->
                    temp.add(ac.plus(li))
                }
            }
            return@reduce temp
        }
    }

    val trashList = listOf("@", "#", "!", "^", "$")
    val trashSet = getTrash(trashList, 2) + getTrash(trashList, 3)
    var trashString = data.replace("#2", "").split("//_//").joinToString("")

    trashSet.forEach {
        val temp = base64Encode(it.toByteArray())
        trashString = trashString.replace(temp, "")
    }

    return base64Decode(trashString)

}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

suspend fun loadLinksWithWebView(
    url: String,
    callback: (ExtractorLink) -> Unit
) {
    val foundVideo = WebViewResolver(
        Regex("""\.m3u8|i7njdjvszykaieynzsogaysdgb0hm8u1mzubmush4maopa4wde\.com""")
    ).resolveUsingWebView(
        requestCreator(
            "GET", url, referer = "https://olgply.com/"
        )
    ).first ?: return

    callback.invoke(
        ExtractorLink(
            "Olgply",
            "Olgply",
            foundVideo.url.toString(),
            "",
            Qualities.P1080.value,
            true
        )
    )
}
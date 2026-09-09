package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "vixsrc.to"
    override val name = "VixCloud"
    override val requiresReferer = false
    val TAG = "VixSrcExtractor"
    private var referer: String? = null

    companion object {
        const val SITE_URL = "https://vixsrc.to"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        this.referer = referer
        Log.d(TAG, "REFERER: $referer  URL: $url")
        val playlistUrl = getPlaylistLink(url)
        Log.w(TAG, "FINAL URL: $playlistUrl")

        val linkReferer = referer ?: "$SITE_URL/"

        callback.invoke(
            newExtractorLink(
                source = "VixSrc",
                name = "Streaming Community - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = linkReferer
            }
        )


    }

    private suspend fun getPlaylistLink(url: String): String {
        Log.d(TAG, "Item url: $url")

        val script = getScript(url)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val masterPlaylistParams = masterPlaylist.getJSONObject("params")
        val token = masterPlaylistParams.getString("token")
        val expires = masterPlaylistParams.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        var masterPlaylistUrl: String
        val params = "token=${token}&expires=${expires}"
        masterPlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$params"
        } else {
            "${playlistUrl}?$params"
        }
        Log.d(TAG, "masterPlaylistUrl: $masterPlaylistUrl")

        if (script.optBoolean("canPlayFHD")) {
            masterPlaylistUrl += "&h=1"
        }

        Log.d(TAG, "Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private fun buildHeaders(url: String): Map<String, String> {
        return mutableMapOf(
            "Accept" to "*/*",
            "Alt-Used" to url.toHttpUrl().host,
            "Connection" to "keep-alive",
            "Referer" to (referer ?: "$SITE_URL/"),
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/133.0",
        )
    }

    private fun toApiUrl(url: String): String {
        val httpUrl = url.toHttpUrl()
        val path = httpUrl.encodedPath.trim('/')
        if (path.startsWith("api/")) return url
        return httpUrl.newBuilder().encodedPath("/api/$path").build().toString()
    }

    private suspend fun resolveEmbedUrl(url: String): String {
        val apiUrl = toApiUrl(url)
        Log.d(TAG, "Api url: $apiUrl")

        val payload = app.get(apiUrl, headers = buildHeaders(apiUrl)).text
        NetworkBlock.check("vixsrc.to", payload)
        val src = JSONObject(payload).getString("src")
        return if (src.startsWith("http")) src else SITE_URL + src
    }

    private suspend fun getScript(url: String): JSONObject {
        Log.d(TAG, "Item url: $url")
        val embedUrl = resolveEmbedUrl(url)
        Log.d(TAG, "Embed url: $embedUrl")

        val resp = app.get(embedUrl, headers = buildHeaders(embedUrl)).document
//        Log.d(TAG, resp.toString())

//        Log.d(TAG, iframe.document.toString())
        NetworkBlock.check("vixsrc.to", resp.html())

        val scripts = resp.select("script")
        val script = scripts.find { it.data().contains("masterPlaylist") }?.data()
            ?.replace("\n", "\t")
            ?: throw ErrorLoadingException("VixSrc: masterPlaylist not found in $embedUrl")

        val scriptJson = getSanitisedScript(script)
        Log.d(TAG, "Script Json: $scriptJson")
        return JSONObject(scriptJson)
    }

    private fun getSanitisedScript(script: String): String {
        // Split by top-level assignments like window.xxx =
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1) // first split part is empty before first assignment

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            // Clean up the value
            val cleaned = value
                .replace(";", "")
                // Quote keys only inside objects
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                // Remove trailing commas before } or ]
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }
        val finalObject =
            "{\n${jsonObjects.joinToString(",\n")}\n}"
                .replace("'", "\"")

        return finalObject
    }
}

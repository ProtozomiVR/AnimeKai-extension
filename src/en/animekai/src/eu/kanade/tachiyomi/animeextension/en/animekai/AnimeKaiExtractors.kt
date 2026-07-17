package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Extracts playable video URLs from the various embed servers that
 * AnimeKai (and its mirrors) serve.
 *
 * Verified flow for AnimeKai's primary embed hosts (vidtube.site,
 * megaplay.buzz, and any other host that serves the same "megaplay-player"
 * markup):
 *
 *   1. AnimeKai's `/ajax/server?get=<link-id>` returns
 *      `{result: {url: "https://<host>/stream/<base64>/<sub|dub>"}}`.
 *   2. The embed page contains `<div id="megaplay-player"
 *      data-id="<numeric>">`.
 *   3. Fetching `<host>/stream/getSourcesNew?id=<data-id>&type=<sub|dub>`
 *      returns `{sources: {file: "<m3u8-url>"}, tracks: [...]}`.
 *
 * The m3u8 is the master playlist and can be passed straight to Aniyomi's
 * player. The dispatcher [videosFromEmbed] routes to this extractor for
 * any embed URL whose host serves the megaplay-player markup, and falls
 * back to legacy / generic extractors for other embed types.
 */
class AnimeKaiExtractors(
    private val client: OkHttpClient,
    private val baseHeaders: Headers
) {

    /**
     * Main entry point.
     *
     * @param embedUrl  The URL returned by AnimeKai's `/ajax/server?get=`.
     * @param label     The server label from the server list (e.g. "VidPlay-1").
     * @param type      "sub", "hsub", or "dub".
     */
    fun videosFromEmbed(embedUrl: String, label: String, type: String): List<Video> {
        val host = runCatching { embedUrl.toHttpUrl().host }.getOrNull()
            ?: return emptyList()
        return runCatching {
            when {
                // AnimeKai's primary embeds (vidtube.site, megaplay.buzz,
                // and any other host serving the megaplay-player markup).
                host.contains("vidtube") ||
                host.contains("megaplay") ||
                host.contains("nekostream") -> extractMegaplay(embedUrl, label, type)

                host.contains("vidstreaming") || host.contains("goload") ||
                    host.contains("gogo") -> extractVidstreaming(embedUrl, label)

                host.contains("streamtape") -> extractStreamtape(embedUrl, label)

                host.contains("doodstream") || host.contains("dood.") ||
                    host.contains("ds2play") -> extractDoodstream(embedUrl, label)

                host.contains("mixdrop") -> extractMixDrop(embedUrl, label)

                host.contains("mp4upload") -> extractMp4Upload(embedUrl, label)

                host.contains("filemoon") || host.contains("moonplayer") ->
                    extractFileMoon(embedUrl, label)

                host.contains("streamwish") || host.contains("streamsb") ||
                    host.contains("sbplay") -> extractStreamWish(embedUrl, label)

                host.contains("kwik") -> extractKwik(embedUrl, label)

                host.contains("xstream") -> extractXStream(embedUrl, label)

                else -> extractGeneric(embedUrl, label)
            }
        }.getOrElse { emptyList() }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun req(url: String, headers: Headers? = null): Request =
        GET(url, headers ?: baseHeaders)

    private fun fetch(url: String, headers: Headers? = null): Response =
        client.newCall(req(url, headers)).execute()

    // -----------------------------------------------------------------
    // MegaPlay / VidTube (AnimeKai's primary embed)
    // -----------------------------------------------------------------

    /**
     * Extract the m3u8 master URL from a MegaPlay / VidTube embed page.
     *
     * The embed page lives at `<host>/stream/<base64>/<sub|dub>` and
     * contains `<div id="megaplay-player" data-id="<numeric>">`. We
     * then call `<host>/stream/getSourcesNew?id=<data-id>&type=<type>`
     * which returns `{sources: {file: "<m3u8-url>"}}`.
     */
    private fun extractMegaplay(embedUrl: String, label: String, type: String): List<Video> {
        val doc = fetch(embedUrl, baseHeaders.newBuilder()
            .set("Referer", "https://animekaitv.to/")
            .build()).asJsoup()

        val dataId = doc.select("#megaplay-player").firstOrNull()
            ?.attr("data-id")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        // The type ("sub" or "dub") is the last path segment of the embed
        // URL — fall back to the [type] argument the caller passed if the
        // URL doesn't end with a recognisable type.
        val embedType = embedUrl.toHttpUrl().pathSegments.lastOrNull()
            ?.takeIf { it == "sub" || it == "dub" }
            ?: type.takeIf { it == "sub" || it == "dub" }
            ?: "sub"

        val host = embedUrl.toHttpUrl().run { "$scheme://$host" }
        val apiUrl = "$host/stream/getSourcesNew".toHttpUrl().newBuilder()
            .addQueryParameter("id", dataId)
            .addQueryParameter("type", embedType)
            .build()

        val apiResp = fetch(apiUrl.toString(), baseHeaders.newBuilder()
            .set("Referer", embedUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build())
        val body = apiResp.body!!.string()

        // Find the "file" field inside sources. The JSON looks like:
        //   {"sources":{"file":"https://.../master.m3u8"},"tracks":[...],"t":1,...}
        val m3u8 = Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
            .find(body)?.groupValues?.get(1)
            ?: return emptyList()

        val videoHeaders = baseHeaders.newBuilder()
            .set("Referer", embedUrl)
            .set("Origin", host)
            .build()
        // The master playlist auto-negotiates quality, so we expose one
        // Video labelled "Default". Aniyomi's player will offer all
        // variants from the m3u8.
        return listOf(Video(m3u8, "$label - Default", m3u8, headers = videoHeaders))
    }

    // -----------------------------------------------------------------
    // Vidstreaming / Gogo
    // -----------------------------------------------------------------

    private fun extractVidstreaming(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val script = doc.select("script").html()
        val sourcePattern = Regex("""file\s*:\s*"(https?://[^"]+)"""")
        val qualityPattern = Regex("""label\s*:\s*"([^"]+)"""")
        val sources = sourcePattern.findAll(script).map { it.groupValues[1] }.toList()
        val labels = qualityPattern.findAll(script).map { it.groupValues[1] }.toList()
        return sources.mapIndexed { idx, src ->
            val q = labels.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "Default"
            Video(src, "$tag - $q", src, headers = baseHeaders.newBuilder()
                .set("Referer", embedUrl).build())
        }
    }

    // -----------------------------------------------------------------
    // Streamtape
    // -----------------------------------------------------------------

    private fun extractStreamtape(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val link = doc.select("div#oolink a[href*=getvideo], a#downloadbtn").firstOrNull()
            ?: return emptyList()
        val href = link.absUrl("href").ifEmpty { link.attr("href") }
        val resp = fetch(href)
        val finalUrl = resp.request.url.toString()
        return listOf(Video(finalUrl, "$tag - Default", finalUrl,
            headers = baseHeaders.newBuilder().set("Referer", embedUrl).build()))
    }

    // -----------------------------------------------------------------
    // Doodstream
    // -----------------------------------------------------------------

    private fun extractDoodstream(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val passMd5Path = Regex("""'(/pass_md5/[^']+)""").find(doc.html())?.groupValues?.get(1)
            ?: return emptyList()
        val doodHost = embedUrl.toHttpUrl().host
        val md5Page = fetch("https://$doodHost$passMd5Path", baseHeaders.newBuilder()
            .set("Referer", embedUrl).build()).body!!.string()
        val token = passMd5Path.substringAfterLast('/')
        val randomString = (1..10).map {
            ('a'..'z').random()
        }.joinToString("")
        val finalUrl = "https://$doodHost/md5/$md5Page$randomString?token=$token&expiry=" +
            (System.currentTimeMillis() / 1000)
        return listOf(Video(finalUrl, "$tag - Default", finalUrl,
            headers = baseHeaders.newBuilder()
                .set("Referer", "https://$doodHost/").build()))
    }

    // -----------------------------------------------------------------
    // MixDrop
    // -----------------------------------------------------------------

    private fun extractMixDrop(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val html = doc.html()
        Regex("""eval\(""").find(html) ?: return emptyList()
        val url = Regex("""https://[^"']+\.mp4""").find(html)?.value ?: return emptyList()
        return listOf(Video(url, "$tag - Default", url,
            headers = baseHeaders.newBuilder().set("Referer", embedUrl).build()))
    }

    // -----------------------------------------------------------------
    // MP4Upload
    // -----------------------------------------------------------------

    private fun extractMp4Upload(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val html = doc.html()
        val url = Regex("""src:\s*"(https://[^"']+\.mp4[^"]*)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex(""""file"\s*:\s*"(https://[^"']+\.mp4[^"]*)"""")
                .find(html)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(Video(url, "$tag - Default", url,
            headers = baseHeaders.newBuilder().set("Referer", embedUrl).build()))
    }

    // -----------------------------------------------------------------
    // FileMoon
    // -----------------------------------------------------------------

    private fun extractFileMoon(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val html = doc.html()
        val sources = Regex("""sources:\s*\[(\{.*?})]""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1] }.toList()
        val videos = mutableListOf<Video>()
        for (src in sources) {
            val url = Regex(""""?file"?\s*:\s*"(https?://[^"]+)"""").find(src)?.groupValues?.get(1)
            val label = Regex(""""?label"?\s*:\s*"([^"]+)"""").find(src)?.groupValues?.get(1)
                ?: "Default"
            if (url != null) {
                videos += Video(url, "$tag - $label", url,
                    headers = baseHeaders.newBuilder().set("Referer", embedUrl).build())
            }
        }
        if (videos.isEmpty()) {
            Regex("""file:'([^']+\.mp4[^']*)'""").find(html)?.groupValues?.get(1)?.let { url ->
                videos += Video(url, "$tag - Default", url,
                    headers = baseHeaders.newBuilder().set("Referer", embedUrl).build())
            }
        }
        return videos
    }

    // -----------------------------------------------------------------
    // StreamWish / StreamSB
    // -----------------------------------------------------------------

    private fun extractStreamWish(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val html = doc.html()
        val pattern = Regex("""\{\s*file\s*:\s*"(https?://[^"]+)"\s*,\s*label\s*:\s*"([^"]+)"\s*}""")
        val initial = pattern.findAll(html).map { m ->
            Video(m.groupValues[1], "$tag - ${m.groupValues[2]}", m.groupValues[1],
                headers = baseHeaders.newBuilder().set("Referer", embedUrl).build())
        }.toMutableList()
        if (initial.isEmpty()) {
            val host = embedUrl.toHttpUrl().host
            val api = "https://$host/sources43"
            runCatching {
                val apiResp = fetch(api, baseHeaders.newBuilder().set("Referer", embedUrl).build())
                val body = apiResp.body!!.string()
                Regex(""""file"\s*:\s*"(https?://[^"]+)"""").findAll(body).forEach { m ->
                    val u = m.groupValues[1]
                    if (u.endsWith(".m3u8") || u.endsWith(".mp4")) {
                        initial += Video(u, "$tag - Default", u,
                            headers = baseHeaders.newBuilder().set("Referer", embedUrl).build())
                    }
                }
            }
        }
        return initial
    }

    // -----------------------------------------------------------------
    // Kwik
    // -----------------------------------------------------------------

    private fun extractKwik(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val html = doc.html()
        val key = Regex("""\bconst\s+keys\s*=\s*\['([^']+)','([^']+)'\]""")
            .find(html) ?: return emptyList()
        val keyA = key.groupValues[1]
        val keyB = key.groupValues[2]
        val cipher = Regex("""const\s+cipher\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val decrypted = runCatching { decryptAesEcb(cipher, keyA + keyB) }.getOrNull() ?: return emptyList()
        val url = Regex("""(https?://[^'"]+\.mp4[^'"]*)""").find(decrypted)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(Video(url, "$tag - Default", url,
            headers = baseHeaders.newBuilder().set("Referer", embedUrl).build()))
    }

    // -----------------------------------------------------------------
    // XStream
    // -----------------------------------------------------------------

    private fun extractXStream(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val html = doc.html()
        val urls = Regex("""(https?://[^'"]+\.mp4[^'"]*)""").findAll(html)
            .map { it.groupValues[1] }.distinct().toList()
        return urls.map {
            Video(it, "$tag - Default", it,
                headers = baseHeaders.newBuilder().set("Referer", embedUrl).build())
        }
    }

    // -----------------------------------------------------------------
    // Generic fallback: scan the page for any mp4/m3u8.
    // -----------------------------------------------------------------

    private fun extractGeneric(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        val html = doc.html()
        val urls = Regex("""(https?://[^'"\s]+\.(?:mp4|m3u8)[^'"\s]*)""")
            .findAll(html).map { it.groupValues[1] }.distinct().toList()
        return urls.map {
            Video(it, "$tag - Default", it,
                headers = baseHeaders.newBuilder().set("Referer", embedUrl).build())
        }
    }

    // -----------------------------------------------------------------
    // Crypto helper for Kwik
    // -----------------------------------------------------------------

    private fun decryptAesEcb(payload: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        val decoded = Base64.getDecoder().decode(payload)
        return String(cipher.doFinal(decoded), Charsets.UTF_8)
    }
}

package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extracts playable video URLs from the various embed servers that
 * AnimeKai (and its mirrors) serve. Each extractor returns standard
 * [Video] objects, which means Aniyomi's download manager can persist
 * them automatically — no extra plumbing needed.
 *
 * The dispatcher [videosFromEmbed] inspects the embed URL/host and routes
 * it to the correct extractor. New servers can be added by appending a
 * case to the `when` block below.
 */
class AnimeKaiExtractors(
    private val client: OkHttpClient,
    private val baseHeaders: Headers
) {

    /**
     * Main entry point. Given an embed URL and a server id, return the
     * list of playable videos. Errors from individual extractors are
     * swallowed so a single broken server does not break the whole list.
     */
    fun videosFromEmbed(embedUrl: String, serverId: String): List<Video> {
        val host = runCatching { embedUrl.toHttpUrl().host }.getOrNull() ?: return emptyList()
        val tag = serverLabel(serverId)
        return runCatching {
            when {
                host.contains("vidstreaming") || host.contains("goload") ||
                    host.contains("gogo") -> extractVidstreaming(embedUrl, tag)

                host.contains("streamtape") -> extractStreamtape(embedUrl, tag)

                host.contains("doodstream") || host.contains("dood.") ||
                    host.contains("ds2play") -> extractDoodstream(embedUrl, tag)

                host.contains("mixdrop") -> extractMixDrop(embedUrl, tag)

                host.contains("mp4upload") -> extractMp4Upload(embedUrl, tag)

                host.contains("filemoon") || host.contains("moonplayer") ->
                    extractFileMoon(embedUrl, tag)

                host.contains("streamwish") || host.contains("streamsb") ||
                    host.contains("sbplay") -> extractStreamWish(embedUrl, tag)

                host.contains("kwik") -> extractKwik(embedUrl, tag)

                host.contains("xstream") -> extractXStream(embedUrl, tag)

                // AnimeKai internal HD servers usually return a direct mp4
                // URL behind a JSON endpoint.
                serverId.startsWith("hd-") -> extractInternal(embedUrl, tag)

                else -> extractGeneric(embedUrl, tag)
            }
        }.getOrElse { emptyList() }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun serverLabel(id: String): String = when {
        id.equals("vidstreaming", ignoreCase = true) -> "Vidstreaming"
        id.equals("vidstream",    ignoreCase = true) -> "VidStream"
        id.equals("gogo",         ignoreCase = true) -> "Gogo"
        id.equals("streamtape",   ignoreCase = true) -> "Streamtape"
        id.equals("doodstream",   ignoreCase = true) -> "Doodstream"
        id.equals("mixdrop",      ignoreCase = true) -> "MixDrop"
        id.equals("mp4upload",    ignoreCase = true) -> "MP4Upload"
        id.equals("filemoon",     ignoreCase = true) -> "FileMoon"
        id.equals("streamwish",   ignoreCase = true) -> "StreamWish"
        id.equals("kwik",         ignoreCase = true) -> "Kwik"
        id.equals("xstream",      ignoreCase = true) -> "XStream"
        id.startsWith("hd-")                       -> "AnimeKai ${id.uppercase()}"
        else                                        -> id
    }

    private fun req(url: String, headers: Headers? = null): Request =
        GET(url, headers ?: baseHeaders)

    private fun fetch(url: String, headers: Headers? = null): Response =
        client.newCall(req(url, headers)).execute()

    // -----------------------------------------------------------------
    // Vidstreaming / Gogo
    // -----------------------------------------------------------------

    private fun extractVidstreaming(embedUrl: String, tag: String): List<Video> {
        val doc = fetch(embedUrl).asJsoup()
        // Gogo / Vidstreaming exposes a "sources" JSON in a <script> tag.
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
        // The actual streamable URL is one redirect away.
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
            .set("Referer", embedUrl).build()).body.string()
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
        val m = Regex("""eval\(""").find(html) ?: return emptyList()
        // Look for the stream URL inside the obfuscated eval'd payload.
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
            // Some FileMoon pages hide the URL behind an AES-encrypted blob.
            // Try the common pattern.
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
            // API-style: /sourcesN where N is 41 or 43.
            val host = embedUrl.toHttpUrl().host
            val api = "https://$host/sources43"
            runCatching {
                val apiResp = fetch(api, baseHeaders.newBuilder().set("Referer", embedUrl).build())
                val body = apiResp.body.string()
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
    // AnimeKai internal HD servers (direct mp4 behind ajax)
    // -----------------------------------------------------------------

    private fun extractInternal(embedUrl: String, tag: String): List<Video> {
        val resp = fetch(embedUrl)
        val body = resp.body.string()
        val url = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").find(body)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(Video(url, "$tag - Default", url,
            headers = baseHeaders.newBuilder().set("Referer", embedUrl).build()))
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

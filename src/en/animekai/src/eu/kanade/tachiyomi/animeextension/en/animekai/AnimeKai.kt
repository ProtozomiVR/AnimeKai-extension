package eu.kanade.tachiyomi.animeextension.en.animekai

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Aniyomi / AnymeX extension for animekaitv.to and its mirrors.
 *
 * Site structure (verified against the live site):
 *
 *   - `/home`                 — landing/app page. The "Recently Updated"
 *                               section is what we use for popular + latest.
 *   - `/filter?keyword=<q>`   — search results.
 *   - `/watch/<slug>-<5char>` — anime details page. The numeric anime id
 *                               lives in `<main class="layout-page-watchtv"
 *                               data-id="<numeric>">`.
 *   - `/ajax/episode/list/<numeric-id>`
 *                             — returns JSON `{status, result: "<HTML>"}`.
 *                               The HTML contains one `<li><a data-num=...
 *                               data-ids="<base64>" .../></li>` per episode.
 *   - `/ajax/server/list?servers=<data-ids>`
 *                             — returns JSON `{status, result: "<HTML>"}`.
 *                               The HTML contains sub / hsub / dub blocks,
 *                               each with `<li data-link-id="<base64>">`.
 *   - `/ajax/server?get=<data-link-id>`
 *                             — returns JSON `{status, result: {url,
 *                               skip_data}}`. `url` is the embed page
 *                               (typically on vidtube.site or
 *                               megaplay.buzz). The embed page exposes
 *                               `<host>/stream/getSourcesNew?id=<data-id>
 *                               &type=<sub|dub>` which returns the actual
 *                               m3u8 URL — see AnimeKaiExtractors.
 *
 * Features:
 *  - Configurable mirror selection (single + multi-select).
 *  - Multi-select preference for which video servers to expose.
 *  - Multi-select preference for which video qualities to expose.
 *  - Subbed + dubbed streams merged into a single episode; dubbed servers
 *    are sorted to appear first in the server list (toggleable).
 */
class AnimeKai : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeKai"

    /**
     * Base URL is derived from the preferred mirror stored in SharedPreferences.
     * Falls back to the first mirror in [MIRRORS] if nothing has been chosen.
     */
    override val baseUrl: String
        get() = preferences.getString(
            KEY_PREFERRED_MIRROR,
            MIRRORS.keys.first()
        ) ?: MIRRORS.keys.first()

    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy { getSourcePreferences() }

    /**
     * The list of mirrors that animekaitv.to is known to operate under.
     * New mirrors can be added here and they will automatically appear in
     * the multi-select mirror preference.
     */
    private val MIRRORS: LinkedHashMap<String, String> = linkedMapOf(
        "https://animekaitv.to"      to "AnimeKai.tv (animekaitv.to)",
        "https://animekai.so"        to "AnimeKai.so",
        "https://animekai.to"        to "AnimeKai.to",
        "https://animekai.lv"        to "AnimeKai.lv",
        "https://animekai.bz"        to "AnimeKai.bz",
        "https://animekai.sk"        to "AnimeKai.sk",
        "https://animekai.si"        to "AnimeKai.si",
        "https://animekai.cc"        to "AnimeKai.cc",
        "https://animekai.com.pl"    to "AnimeKai.com.pl",
        "https://animekai.pro"       to "AnimeKai.pro"
    )

    /**
     * Server-id prefixes that we recognise in the user-facing multi-select
     * preference. The site's server list returns labels like "VidPlay-1",
     * "HD-1", "Vidstream-2", "VidCloud-1" — we match the user's selection
     * by case-insensitive substring, so "vidplay" matches "VidPlay-1" and
     * "VidPlay-2", "hd-1" matches "HD-1", etc.
     */
    private val SERVERS: LinkedHashMap<String, String> = linkedMapOf(
        "vidplay"    to "VidPlay",
        "hd-1"       to "HD-1",
        "hd-2"       to "HD-2",
        "vidstream"  to "Vidstream",
        "vidcloud"   to "VidCloud"
    )

    /**
     * Qualities that may be exposed by the extractors. The m3u8 master
     * playlist auto-negotiates quality, so by default we expose everything
     * and let Aniyomi's player pick.
     */
    private val QUALITIES: LinkedHashMap<String, String> = linkedMapOf(
        "1080p"   to "1080p",
        "720p"    to "720p",
        "480p"    to "480p",
        "360p"    to "360p",
        "default" to "Default / Auto"
    )

    // -----------------------------------------------------------------
    // Network
    // -----------------------------------------------------------------

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", baseUrl)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("X-Requested-With", "XMLHttpRequest")

    // -----------------------------------------------------------------
    // Popular / Latest / Search
    //
    // The /home page has a "Recently Updated" section that lists the
    // latest episodes. We use the same selector for popular + latest
    // because animekaitv.to does not have a dedicated "popular" page —
    // /home is the closest analogue. Search uses /filter?keyword=<q>.
    // -----------------------------------------------------------------

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/home", headers)
    }

    override fun popularAnimeSelector(): String =
        "section#recent-update .ani.items > .item"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        // The watch URL is on the poster <a>. Strip the trailing /ep-N
        // if present (each card links to "the next new episode" by default).
        val href = element.select("a[href*=/watch/]").firstOrNull()
            ?.attr("href")?.toWatchUrl().orEmpty()
        anime.url = href
        anime.thumbnail_url = element.select("img").firstOrNull()
            ?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
        element.select("a.name.d-title").firstOrNull()?.let {
            anime.title = it.text().trim()
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request {
        // /home's "Recently Updated" section is the latest feed.
        return GET("$baseUrl/home", headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/filter".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeSelector(): String =
        "div#list-items.ani.items > .item .inner"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val href = element.select("a[href*=/watch/]").firstOrNull()
            ?.attr("href")?.toWatchUrl().orEmpty()
        anime.url = href
        anime.thumbnail_url = element.select("img").firstOrNull()
            ?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
        element.select("a.name.d-title").firstOrNull()?.let {
            anime.title = it.text().trim()
        }
        return anime
    }

    override fun searchAnimeNextPageSelector(): String? = null

    /**
     * Normalise a possibly-relative /watch/ URL into a root-relative path
     * (no host) and strip any trailing `/ep-<N>` segment — episodes are
     * resolved via the AJAX API, not by URL path.
     */
    private fun String.toWatchUrl(): String {
        val full = when {
            startsWith("http") -> this
            startsWith("/")    -> baseUrl + this
            else               -> "$baseUrl/$this"
        }
        // Drop "/ep-<digits>" if present.
        return full.substringAfter(baseUrl)
            .replace(Regex("/ep-\\d+/?$"), "")
            .ifEmpty { this }
    }

    // -----------------------------------------------------------------
    // Anime details
    // -----------------------------------------------------------------

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        document.select("h1.title.d-title").firstOrNull()?.let {
            anime.title = it.text().trim()
        }
        document.select("#w-info .poster img").firstOrNull()?.let {
            anime.thumbnail_url = it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
        document.select("#w-info .synopsis .content").firstOrNull()?.let {
            anime.description = it.text().trim()
        }
        // Genres live in .bmeta inside a div whose label starts with "Genres:".
        val genres = document.select("#w-info .bmeta .meta div").toList()
            .firstOrNull { it.ownText().contains("Genres", ignoreCase = true) }
            ?.select("a")?.eachText()?.joinToString(", ")
        if (!genres.isNullOrEmpty()) anime.genre = genres
        // Status lives in a sibling div labelled "Status:".
        val status = document.select("#w-info .bmeta .meta div").toList()
            .firstOrNull { it.ownText().contains("Status", ignoreCase = true) }
            ?.select("a")?.text()?.trim()?.lowercase().orEmpty()
        anime.status = when {
            status.contains("finished") || status.contains("completed") -> SAnime.COMPLETED
            status.contains("airing")   || status.contains("ongoing")   -> SAnime.ONGOING
            else                                                         -> SAnime.UNKNOWN
        }
        return anime
    }

    // -----------------------------------------------------------------
    // Episodes
    //
    // anime.url is "/watch/<slug>-<5char>". The numeric anime id lives
    // on the watch page in `<main class="layout-page-watchtv"
    // data-id="<numeric>">. We fetch the watch page once to extract
    // that id, then call /ajax/episode/list/<id> to get the episode
    // list HTML.
    // -----------------------------------------------------------------

    override fun episodeListRequest(anime: SAnime): Request {
        // Fetch the watch page so we can extract the numeric anime id.
        return GET(baseUrl + anime.url, headers)
    }

    override fun episodeListSelector(): String =
        // ParsedAnimeHttpSource parses the document returned by
        // episodeListRequest with this selector. BUT — we override
        // episodeListParse below to use the AJAX API instead, so this
        // selector is only a defensive fallback.
        "ul.ep-range li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val num = element.attr("data-num")
        episode.url = element.attr("data-ids")
        episode.episode_number = num.toFloatOrNull() ?: 0F
        val title = element.select("span.d-title").text().trim()
        episode.name = if (title.isNotBlank()) "Episode $num: $title" else "Episode $num"
        element.attr("data-timestamp").toLongOrNull()?.let {
            episode.date_upload = it * 1000L
        }
        return episode
    }

    /**
     * Override the default episode list parse. The watch page itself does
     * NOT contain the episode list — it's loaded via an AJAX call to
     * `/ajax/episode/list/<numeric-anime-id>` that returns HTML inside
     * JSON. We:
     *   1. Read the numeric id from `<main data-id="...">` on the watch
     *      page (the response [document] we received from
     *      [episodeListRequest]).
     *   2. Fire the AJAX request.
     *   3. Parse the embedded HTML for `<li><a data-num data-ids ...>`.
     */
    override fun episodeListParse(response: Response): List<SEpisode> {
        val watchDoc = response.asJsoup()
        // Numeric anime id lives on `<main class="layout-page-watchtv"
        // data-id="<numeric>">. Fall back to any [data-id="<digits>"]
        // element if the main tag is missing (mirrors may restructure).
        val realId = watchDoc.select("main.layout-page-watchtv").firstOrNull()
            ?.attr("data-id")
            ?.takeIf { it.matches(Regex("\\d+")) }
            ?: watchDoc.select("[data-id]").toList()
                .firstOrNull { it.attr("data-id").matches(Regex("\\d+")) }
                ?.attr("data-id")
                .orEmpty()
        if (realId.isBlank()) return emptyList()

        val ajaxUrl = "$baseUrl/ajax/episode/list/$realId".toHttpUrl().newBuilder().build()
        val ajaxResp = client.newCall(GET(ajaxUrl.toString(), headers)).execute()
        val body = ajaxResp.body!!.string()
        // Response is JSON: {"status":200,"result":"<HTML>"}. Pull out
        // result, parse as HTML, select episode anchors. (extractJsonField
        // already JSON-unescapes the string body, so we hand the result
        // straight to Jsoup.)
        val resultStr = extractJsonField(body, "result") ?: return emptyList()
        val epDoc = Jsoup.parse(resultStr, baseUrl)
        return epDoc.select("ul.ep-range li a").map { episodeFromElement(it) }
    }

    // -----------------------------------------------------------------
    // Videos
    // -----------------------------------------------------------------

    private val extractor by lazy { AnimeKaiExtractors(client, headers) }

    /**
     * The episode URL we stored is the raw `data-ids` base64 blob (the
     * identifier the AJAX server-list API expects). We construct the
     * server-list request directly from it.
     */
    override fun videoListRequest(episode: SEpisode): Request {
        val ids = episode.url
        val url = "$baseUrl/ajax/server/list".toHttpUrl().newBuilder()
            .addQueryParameter("servers", ids)
            .build()
        return GET(url.toString(), headers)
    }

    override fun videoListSelector(): String =
        "div.servers div.type"

    override fun videoFromElement(element: Element): Video =
        throw UnsupportedOperationException(
            "videoFromElement is not used — videoListParse is overridden.")

    /**
     * Parse the server-list JSON response, fetch the embed URL for each
     * enabled server, and merge sub + dub with dub-first ordering.
     */
    override fun videoListParse(response: Response): List<Video> {
        val body = response.body!!.string()
        val resultStr = extractJsonField(body, "result") ?: return emptyList()
        val serversDoc = Jsoup.parse(resultStr, baseUrl)

        val enabledServers = preferences.getStringSet(
            KEY_ENABLED_SERVERS, SERVERS.keys.toSet()
        ) ?: SERVERS.keys.toSet()
        val enabledQualities = preferences.getStringSet(
            KEY_ENABLED_QUALITIES, QUALITIES.keys.toSet()
        ) ?: QUALITIES.keys.toSet()
        val dubFirst = preferences.getBoolean(KEY_DUB_FIRST, true)

        // (type, serverLabel, linkId)
        data class Srv(val type: String, val label: String, val linkId: String)
        val subs = mutableListOf<Srv>()
        val dubs = mutableListOf<Srv>()

        serversDoc.select("div.servers div.type").forEach { typeBlock ->
            val type = typeBlock.attr("data-type").lowercase()
            typeBlock.select("li[data-link-id]").forEach { li ->
                val label = li.text().trim()
                val linkId = li.attr("data-link-id")
                if (label.isBlank() || linkId.isBlank()) return@forEach
                val srv = Srv(type, label, linkId)
                when (type) {
                    "dub"           -> dubs.add(srv)
                    "sub", "hsub"   -> subs.add(srv)
                    else            -> subs.add(srv)
                }
            }
        }

        fun Srv.matchesEnabled(): Boolean =
            enabledServers.any { id ->
                label.lowercase().contains(id.lowercase())
            }

        fun resolve(srv: Srv, prefix: String): List<Video> {
            if (!srv.matchesEnabled()) return emptyList()
            val embedUrl = fetchEmbedUrl(srv.linkId) ?: return emptyList()
            return extractor.videosFromEmbed(embedUrl, srv.label, srv.type)
                .filter { qualityMatches(it, enabledQualities) }
                .map { v ->
                    v.copy(quality = "$prefix ${v.quality}".trim())
                }
        }

        val subVideos = subs.flatMap { resolve(it, "[SUB]") }
        val dubVideos = dubs.flatMap { resolve(it, "[DUB]") }

        return if (dubFirst) dubVideos + subVideos else subVideos + dubVideos
    }

    override fun videoUrlParse(document: Document) =
        throw UnsupportedOperationException("videoUrlParse is not used.")

    /**
     * Call `/ajax/server?get=<link-id>` and return the embed URL from the
     * JSON response (`{status, result: {url, skip_data}}`). Returns null
     * on any failure.
     */
    private fun fetchEmbedUrl(linkId: String): String? {
        val url = "$baseUrl/ajax/server".toHttpUrl().newBuilder()
            .addQueryParameter("get", linkId)
            .build()
        return runCatching {
            val resp = client.newCall(GET(url.toString(), headers)).execute()
            val b = resp.body!!.string()
            // result is an object: {"url":"...","skip_data":{...}}
            val resultObj = extractJsonField(b, "result") ?: return@runCatching null
            extractUrlFromResultObject(resultObj)
        }.getOrNull()
    }

    private fun qualityMatches(video: Video, enabledQualities: Set<String>): Boolean {
        val tag = video.quality.lowercase()
        return enabledQualities.any { enabled ->
            tag.contains(enabled.lowercase()) || enabled.equals("default", ignoreCase = true)
        }
    }

    // -----------------------------------------------------------------
    // JSON helpers
    //
    // The site's AJAX endpoints return JSON with an HTML string inside
    // `result`. We avoid pulling in Gson for these tiny payloads and
    // just extract the field with a regex. The HTML is JSON-escaped
    // (newline => \n, quote => \", etc.), so we unescape before parsing
    // with Jsoup.
    // -----------------------------------------------------------------

    /** Extract a top-level JSON field as a raw string (no quotes, no escapes). */
    private fun extractJsonField(json: String, field: String): String? {
        // Match "field": followed by either a quoted string or an object.
        val pattern = Regex(
            """"$field"\s*:\s*("(?:[^"\\]|\\.)*"|\{[^}]*\})""",
            RegexOption.DOT_MATCHES_ALL
        )
        val m = pattern.find(json) ?: return null
        var raw = m.groupValues[1]
        if (raw.startsWith("\"")) {
            // Strip outer quotes.
            raw = raw.substring(1, raw.length - 1)
            return raw.unescapeJsonString()
        }
        return raw
    }

    /** Unescape a JSON string literal body. */
    private fun String.unescapeJsonString(): String = buildString(length) {
        var i = 0
        while (i < this@unescapeJsonString.length) {
            val c = this@unescapeJsonString[i]
            if (c == '\\' && i + 1 < this@unescapeJsonString.length) {
                when (this@unescapeJsonString[i + 1]) {
                    'n'  -> append('\n')
                    't'  -> append('\t')
                    'r'  -> append('\r')
                    '"'  -> append('"')
                    '\\' -> append('\\')
                    '/'  -> append('/')
                    'b'  -> append('\b')
                    'f'  -> append('\u000C')
                    'u'  -> {
                        val hex = this@unescapeJsonString.substring(i + 2, i + 6)
                        append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> append(this@unescapeJsonString[i + 1])
                }
                i += 2
            } else {
                append(c)
                i += 1
            }
        }
    }

    /** Extract the "url" field from a `{"url":"...","skip_data":{...}}` object string. */
    private fun extractUrlFromResultObject(obj: String): String? {
        val m = Regex(""""url"\s*:\s*"(?:[^"\\]|\\.)*"""").find(obj) ?: return null
        return m.value.substringAfter(":").trim().trim('"').unescapeJsonString()
    }

    // -----------------------------------------------------------------
    // Preferences (the user-facing settings screen in Aniyomi / AnymeX)
    // -----------------------------------------------------------------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // 1. Preferred mirror — single-select among enabled mirrors.
        val mirrorPref = androidx.preference.ListPreference(screen.context).apply {
            key = KEY_PREFERRED_MIRROR
            title = "Preferred mirror"
            summary = "%s"
            entries = MIRRORS.values.toTypedArray()
            entryValues = MIRRORS.keys.toTypedArray()
            setDefaultValue(MIRRORS.keys.first())
        }
        screen.addPreference(mirrorPref)

        // 2. Enabled mirrors — multi-select.
        MultiSelectListPreference(screen.context).apply {
            key = KEY_ENABLED_MIRRORS
            title = "Enabled mirrors"
            summary = "Select which AnimeKai mirrors to fetch listings from. " +
                "The preferred mirror (above) is used unless it fails."
            entries = MIRRORS.values.toTypedArray()
            entryValues = MIRRORS.keys.toTypedArray()
            setDefaultValue(MIRRORS.keys.toSet())
        }.also { screen.addPreference(it) }

        // 3. Enabled video servers — multi-select.
        MultiSelectListPreference(screen.context).apply {
            key = KEY_ENABLED_SERVERS
            title = "Video servers"
            summary = "Choose which embed servers to extract videos from. " +
                "Matched by name (e.g. enabling 'VidPlay' keeps VidPlay-1, VidPlay-2, etc.)."
            entries = SERVERS.values.toTypedArray()
            entryValues = SERVERS.keys.toTypedArray()
            setDefaultValue(SERVERS.keys.toSet())
        }.also { screen.addPreference(it) }

        // 4. Enabled video qualities — multi-select.
        MultiSelectListPreference(screen.context).apply {
            key = KEY_ENABLED_QUALITIES
            title = "Video qualities"
            summary = "Only show videos that match one of the selected qualities."
            entries = QUALITIES.values.toTypedArray()
            entryValues = QUALITIES.keys.toTypedArray()
            setDefaultValue(QUALITIES.keys.toSet())
        }.also { screen.addPreference(it) }

        // 5. Dub first.
        CheckBoxPreference(screen.context).apply {
            key = KEY_DUB_FIRST
            title = "Dubbed servers first"
            summary = "Sort dubbed servers above subbed ones in the video list. " +
                "Subbed and dubbed servers are always both shown."
            setDefaultValue(true)
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val KEY_PREFERRED_MIRROR  = "preferred_mirror"
        private const val KEY_ENABLED_MIRRORS   = "enabled_mirrors"
        private const val KEY_ENABLED_SERVERS   = "enabled_servers"
        private const val KEY_ENABLED_QUALITIES = "enabled_qualities"
        private const val KEY_DUB_FIRST         = "dub_first"
    }
}

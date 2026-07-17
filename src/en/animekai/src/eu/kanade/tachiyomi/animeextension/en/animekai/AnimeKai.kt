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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Aniyomi / AnymeX extension for animekaitv.to and its mirrors.
 *
 * Features:
 *  - Configurable mirror selection (multi-select).
 *  - Multi-select preference for which video servers to expose.
 *  - Multi-select preference for which video qualities to expose.
 *  - Subbed + dubbed streams merged into a single episode; dubbed servers
 *    are sorted to appear first in the server list.
 *  - Standard Aniyomi Video objects, so downloads work natively.
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
     * Video servers that AnimeKai typically embeds. Keys are the internal
     * server identifiers we look for in the embed markup; values are the
     * human-readable labels shown in the preference screen.
     */
    private val SERVERS: LinkedHashMap<String, String> = linkedMapOf(
        "vidstreaming"  to "Vidstreaming",
        "vidstream"     to "VidStream",
        "gogo"          to "Gogo server",
        "streamtape"    to "Streamtape",
        "doodstream"    to "Doodstream",
        "mixdrop"       to "MixDrop",
        "mp4upload"     to "MP4Upload",
        "filemoon"      to "FileMoon",
        "streamwish"    to "StreamWish",
        "hd-1"          to "HD-1 (internal)",
        "hd-2"          to "HD-2 (internal)",
        "xstream"       to "XStream",
        "kwik"          to "Kwik"
    )

    /**
     * Qualities that may be exposed by the extractors. The user can keep
     * only the ones they care about; everything else is filtered out
     * before the video list is returned to Aniyomi.
     */
    private val QUALITIES: LinkedHashMap<String, String> = linkedMapOf(
        "1080p" to "1080p",
        "720p"  to "720p",
        "480p"  to "480p",
        "360p"  to "360p",
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

    // -----------------------------------------------------------------
    // Popular / Latest / Search
    // -----------------------------------------------------------------

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/trending".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun popularAnimeSelector() = "div.film_list-wrap > div.flw-item"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        element.select("a.film-poster-ahref").firstOrNull()?.let { a ->
            anime.url = a.absUrl("href").substringAfter(baseUrl).ifEmpty { a.absUrl("href") }
            a.select("img").firstOrNull()?.let {
                anime.thumbnail_url = it.absUrl("data-src").ifEmpty { it.absUrl("src") }
            }
        }
        element.select("h3.film-name a").firstOrNull()?.let {
            anime.title = it.text().trim()
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? =
        "li.page-item a[title=Next]"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/recently-updated".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // -----------------------------------------------------------------
    // Anime details
    // -----------------------------------------------------------------

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h2.film-name").text().trim()
            .ifEmpty { document.select("h1").text().trim() }
        document.select("div.film-poster img").firstOrNull()?.let {
            anime.thumbnail_url = it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
        document.select("div.description").firstOrNull()?.let {
            anime.description = it.text().trim()
        }
        document.select("div.anisc-info-wrap:has(h3:contains(Genres)) a").eachText()
            .joinToString(", ").ifEmpty { null }?.let { anime.genre = it }
        document.select("div.anisc-info-wrap:has(h3:contains(Status)) a").text()
            .trim().lowercase().let {
                anime.status = when (it) {
                    "ongoing", "currently airing" -> SAnime.ONGOING
                    "completed", "finished" -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            }
        return anime
    }

    // -----------------------------------------------------------------
    // Episodes
    // -----------------------------------------------------------------

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("/").substringBefore("-episode")
        val ajaxUrl = "$baseUrl/ajax/episode/list/$id".toHttpUrl().newBuilder()
            .build()
        return GET(ajaxUrl.toString(), headers)
    }

    override fun episodeListSelector() = "a.ep-item"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.url = element.absUrl("href").substringAfter(baseUrl).ifEmpty { element.absUrl("href") }
        val num = element.select("div.ssli-order").text().trim()
        episode.episode_number = num.toFloatOrNull() ?: 0F
        episode.name = "Episode $num"
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    // -----------------------------------------------------------------
    // Videos
    // -----------------------------------------------------------------

    private val extractor by lazy { AnimeKaiExtractors(client, headers) }

    override fun videoListRequest(episode: SEpisode): Request {
        // Episode URL points to a watch page that embeds server buttons.
        return GET(baseUrl + episode.url, headers)
    }

    override fun videoListSelector() = "div.servers-list a.play-btn"

    override fun videoFromElement(element: Element): Video {
        // Not used; we override [videoListParse] instead so we can merge
        // sub + dub servers in one call with the dubbed ones first.
        throw UnsupportedOperationException()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        // Determine which servers the user wants to keep.
        val enabledServers = preferences.getStringSet(KEY_ENABLED_SERVERS, SERVERS.keys.toSet())
            ?: SERVERS.keys.toSet()
        val enabledQualities = preferences.getStringSet(KEY_ENABLED_QUALITIES, QUALITIES.keys.toSet())
            ?: QUALITIES.keys.toSet()
        val dubFirst = preferences.getBoolean(KEY_DUB_FIRST, true)

        // AnimeKai renders server buttons in two blocks: subbed then dubbed.
        // We collect them in (type, serverId, embedUrl) triples.
        data class ServerRef(val type: String, val id: String, val url: String)
        val subs = mutableListOf<ServerRef>()
        val dubs = mutableListOf<ServerRef>()

        document.select("div.servers-list div.server-block").forEach { block ->
            val type = block.attr("data-type") // "sub" or "dub"
            block.select("a.play-btn").forEach { btn ->
                val id = btn.attr("data-server-id")
                val href = btn.absUrl("data-embed")
                    .ifEmpty { btn.absUrl("href") }
                if (id.isNotBlank() && href.isNotBlank()) {
                    val ref = ServerRef(type, id, href)
                    if (type.equals("dub", ignoreCase = true)) dubs.add(ref)
                    else subs.add(ref)
                }
            }
        }

        // Some mirrors mark sub/dub only by a label rather than a separate
        // block. Fall back to looking for explicit text labels.
        if (subs.isEmpty() && dubs.isEmpty()) {
            document.select("div.servers-list a.play-btn").forEach { btn ->
                val id = btn.attr("data-server-id").ifBlank { btn.attr("data-name") }
                val href = btn.absUrl("data-embed").ifEmpty { btn.absUrl("href") }
                val label = btn.text().lowercase()
                val ref = ServerRef(
                    type = if (label.contains("dub")) "dub" else "sub",
                    id = id,
                    url = href
                )
                if (ref.type == "dub") dubs.add(ref) else subs.add(ref)
            }
        }

        // Resolve each server into Video objects.
        val subVideos = subs
            .filter { enabledServers.contains(it.id) }
            .flatMap { ref ->
                extractor.videosFromEmbed(ref.url, ref.id)
                    .filter { v -> qualityMatches(v, enabledQualities) }
                    .map { v -> v.copy(quality = "[SUB] ${v.quality}") }
            }

        val dubVideos = dubs
            .filter { enabledServers.contains(it.id) }
            .flatMap { ref ->
                extractor.videosFromEmbed(ref.url, ref.id)
                    .filter { v -> qualityMatches(v, enabledQualities) }
                    .map { v -> v.copy(quality = "[DUB] ${v.quality}") }
            }

        // Ordering: dub first when the user has it enabled (default true),
        // otherwise subs first. Subs + dubs are always merged into a single
        // video list per the user's request.
        return if (dubFirst) dubVideos + subVideos else subVideos + dubVideos
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    private fun qualityMatches(video: Video, enabledQualities: Set<String>): Boolean {
        val tag = video.quality.lowercase()
        return enabledQualities.any { enabled ->
            tag.contains(enabled.lowercase()) || enabled.equals("default", ignoreCase = true)
        }
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
            summary = "Choose which embed servers to extract videos from."
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

        private const val KEY_PREFERRED_MIRROR = "preferred_mirror"
        private const val KEY_ENABLED_MIRRORS  = "enabled_mirrors"
        private const val KEY_ENABLED_SERVERS  = "enabled_servers"
        private const val KEY_ENABLED_QUALITIES = "enabled_qualities"
        private const val KEY_DUB_FIRST        = "dub_first"
    }
}

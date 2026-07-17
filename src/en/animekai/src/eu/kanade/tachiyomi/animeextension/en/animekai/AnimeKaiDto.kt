package eu.kanade.tachiyomi.animeextension.en.animekai

import com.google.gson.annotations.SerializedName
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Data Transfer Objects used by [AnimeKai]. Most of the listing endpoints
 * on AnimeKai return JSON through ajax routes, so we model those payloads
 * here and fall back to HTML scraping when needed.
 */

data class AnimeKaiSearchResult(
    @SerializedName("title")  val title: String,
    @SerializedName("url")    val url: String,
    @SerializedName("poster") val poster: String? = null,
    @SerializedName("type")   val type: String? = null,
    @SerializedName("sub")    val sub: Int? = null,
    @SerializedName("dub")    val dub: Int? = null
)

data class AnimeKaiSearchResponse(
    @SerializedName("result")     val result: List<AnimeKaiSearchResult> = emptyList(),
    @SerializedName("type")       val type: String? = null,
    @SerializedName("currentPage") val currentPage: Int = 1,
    @SerializedName("totalPage")  val totalPage: Int = 1
)

data class AnimeKaiEpisode(
    @SerializedName("number")     val number: String,
    @SerializedName("id")         val id: String,
    @SerializedName("title")      val title: String? = null,
    @SerializedName("url")        val url: String? = null,
    @SerializedName("thumbnail")  val thumbnail: String? = null,
    @SerializedName("releasedAt") val releasedAt: String? = null
)

data class AnimeKaiEpisodeResponse(
    @SerializedName("html")  val html: String? = null,
    @SerializedName("result") val result: List<AnimeKaiEpisode> = emptyList()
)

data class AnimeKaiServer(
    @SerializedName("id")       val id: String,
    @SerializedName("name")     val name: String,
    @SerializedName("type")     val type: String, // "sub" or "dub"
    @SerializedName("url")      val url: String,
    @SerializedName("quality")  val quality: String? = null
)

data class AnimeKaiServerResponse(
    @SerializedName("result") val result: List<AnimeKaiServer> = emptyList()
)

/**
 * Mirrors the structure of `SAnime` for cached listings. We do not need to
 * extend SAnime here; we just convert via [toSAnime] when needed.
 */
data class AnimeKaiAnimeMeta(
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = SAnime.UNKNOWN
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        this.title = this@AnimeKaiAnimeMeta.title
        setUrlWithoutDomain(url)
        thumbnail_url = thumbnailUrl
        description = this@AnimeKaiAnimeMeta.description
        genre = this@AnimeKaiAnimeMeta.genre
        status = this@AnimeKaiAnimeMeta.status
    }
}

data class AnimeKaiEpisodeMeta(
    val number: Float,
    val name: String,
    val url: String,
    val date: Long = 0L
) {
    fun toSEpisode(): SEpisode = SEpisode.create().apply {
        episode_number = number
        this.name = this@AnimeKaiEpisodeMeta.name
        setUrlWithoutDomain(url)
        date_upload = date
    }
}

internal object AnimeKaiDateParser {
    private val FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "MMM d, yyyy",
        "MMMM d, yyyy"
    )

    fun parse(input: String?): Long {
        if (input.isNullOrBlank()) return 0L
        for (fmt in FORMATS) {
            runCatching {
                return SimpleDateFormat(fmt, Locale.US).parse(input)?.time ?: 0L
            }
        }
        return 0L
    }
}

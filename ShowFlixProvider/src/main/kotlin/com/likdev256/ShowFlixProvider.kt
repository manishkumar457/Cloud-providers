package com.likdev256

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ShowFlixProvider : MainAPI() {
    override var mainUrl = "https://showflix.xyz"
    override var name = "ShowFlix"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val movieApiUrl = "https://parse.showflix.shop/parse/classes/movies"
    private val tvApiUrl = "https://parse.showflix.shop/parse/classes/series"
    private val applicationId = "SHOWFLIXAPPID"
    private val javaScriptKey = "SHOWFLIXMASTERKEY"
    private val clientVersion = "js3.4.1"
    private val installationId = "e26c34d7-8f79-4161-92d8-36d19023fc60"

    private data class MovieResult(
        @JsonProperty("objectId") val id: String,
        @JsonProperty("movieName") val title: String,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("category") val category: String?,
        @JsonProperty("streamlink") val streamLink: String?,
        @JsonProperty("backdrop") val backdrop: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("storyline") val plot: String?
    )

    private data class TvResult(
        @JsonProperty("objectId") val id: String,
        @JsonProperty("seriesName") val title: String,
        @JsonProperty("seriesPoster") val poster: String?,
        @JsonProperty("seriesCategory") val category: String?,
        @JsonProperty("seriesBackdrop") val backdrop: String?,
        @JsonProperty("seriesRating") val rating: String?,
        @JsonProperty("seriesStoryline") val plot: String?,
        @JsonProperty("Seasons") val seasons: Map<String, List<String>>
    )

    override val mainPage = mainPageOf(
        "\\QTamil\\E" to "Tamil",
        "\\QTamil Dubbed\\E" to "Dubbed",
        "\\QEnglish\\E" to "English",
        "\\QTelugu\\E" to "Telugu",
        "\\QHindi\\E" to "Hindi",
        "\\QMalayalam\\E" to "Malayalam"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val category = request.data
        val movies = getMovies(category, 10)
        val tvShows = getTvShows(category, 10)

        val home = (movies.map { result ->
            newMovieSearchResponse(
                result.title,
                LoadData(result.id, TvType.Movie).toJson(),
                TvType.Movie
            ) {
                this.posterUrl = result.poster
                this.quality = SearchQuality.HD
            }
        } + tvShows.map { result ->
            newTvSeriesSearchResponse(
                result.title,
                LoadData(result.id, TvType.TvSeries).toJson(),
                TvType.TvSeries
            ) {
                this.posterUrl = result.poster
                this.quality = SearchQuality.HD
            }
        }).shuffled()

        return HomePageResponse(listOf(HomePageList(request.name, home)), hasNext = true)
    }

    private suspend fun getMovies(category: String, limit: Int): List<MovieResult> {
        return makeRequest(movieApiUrl, category, limit)
    }

    private suspend fun getTvShows(category: String, limit: Int): List<TvResult> {
        return makeRequest(tvApiUrl, category, limit)
    }

    private suspend inline fun <reified T> makeRequest(url: String, category: String, limit: Int): List<T> {
        val requestBody = JSONObject().apply {
            put("where", JSONObject().put(if (T::class == MovieResult::class) "category" else "seriesCategory", JSONObject().put("\$regex", category)))
            put("limit", limit)
            put("order", "-createdAt")
            put("_method", "GET")
            put("_ApplicationId", applicationId)
            put("_JavaScriptKey", javaScriptKey)
            put("_ClientVersion", clientVersion)
            put("_InstallationId", installationId)
        }.toString()

        val response = app.post(
            url,
            requestBody = requestBody.toRequestBody("application/json".toMediaTypeOrNull()),
            referer = "$mainUrl/"
        )

        return parseJson<Map<String, List<T>>>(response.text)["results"] ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)
        return when (data.type) {
            TvType.Movie -> {
                val movie = getMovies(".*", 1).first { it.id == data.id }
                newMovieLoadResponse(
                    movie.title,
                    url,
                    TvType.Movie,
                    LoadData(movie.id, TvType.Movie)
                ) {
                    this.posterUrl = movie.poster
                    this.backgroundPosterUrl = movie.backdrop
                    this.rating = movie.rating?.toRatingInt()
                    this.plot = movie.plot
                    this.tags = listOfNotNull(movie.category)
                }
            }
            TvType.TvSeries -> {
                val show = getTvShows(".*", 1).first { it.id == data.id }
                val episodes = show.seasons.flatMap { (season, episodes) ->
                    episodes.mapIndexed { index, episode ->
                        Episode(
                            data = LoadData(show.id, TvType.TvSeries, season, index).toJson(),
                            name = episode,
                            season = season.removePrefix("Season ").toIntOrNull(),
                            episode = index + 1
                        )
                    }
                }
                newTvSeriesLoadResponse(
                    show.title,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    this.posterUrl = show.poster
                    this.backgroundPosterUrl = show.backdrop
                    this.rating = show.rating?.toRatingInt()
                    this.plot = show.plot
                    this.tags = listOfNotNull(show.category)
                }
            }
            else -> throw ErrorLoadingException("Unsupported content type")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        when (loadData.type) {
            TvType.Movie -> {
                val movie = getMovies(".*", 1).first { it.id == loadData.id }
                movie.streamLink?.let { link ->
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            link,
                            this.mainUrl,
                            Qualities.Unknown.value,
                            false
                        )
                    )
                }
            }
            TvType.TvSeries -> {
                val show = getTvShows(".*", 1).first { it.id == loadData.id }
                val episodeLink = show.seasons[loadData.season]?.getOrNull(loadData.episode ?: 0)
                episodeLink?.let { link ->
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            link,
                            this.mainUrl,
                            Qualities.Unknown.value,
                            false
                        )
                    )
                }
            }
            else -> return false
        }
        return true
    }

    data class LoadData(
        val id: String,
        val type: TvType,
        val season: String? = null,
        val episode: Int? = null
    )
}
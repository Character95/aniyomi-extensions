package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Jellyfin(private val suffix: String) : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Jellyfin$suffix"

    override val lang = "all"

    override val supportsLatest = true

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        // Create a trust manager that does not validate certificate chains
        val trustAllCerts = arrayOf<TrustManager>(
            @SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                }

                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            },
        )

        // Install the all-trusting trust manager
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        // Create an ssl socket factory with our all-trusting manager
        val sslSocketFactory = sslContext.socketFactory

        return network.client.newBuilder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }.build()
    }

    override val client by lazy {
        if (preferences.getTrustCert) {
            getUnsafeOkHttpClient()
        } else {
            network.client
        }.newBuilder()
            .dns(Dns.SYSTEM)
            .build()
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override var baseUrl = preferences.getHostUrl

    private var username = preferences.getUserName
    private var password = preferences.getPassword
    private var parentId = preferences.getMediaLibId
    private var apiKey = preferences.getApiKey
    private var userId = preferences.getUserId

    init {
        login(false)
    }

    private fun login(new: Boolean, context: Context? = null): Boolean? {
        if (apiKey == null || userId == null || new) {
            baseUrl = preferences.getHostUrl
            username = preferences.getUserName
            password = preferences.getPassword
            if (username.isEmpty() || password.isEmpty()) {
                if (username != "demo") return null
            }
            val (newKey, newUid) = runBlocking {
                withContext(Dispatchers.IO) {
                    JellyfinAuthenticator(preferences, baseUrl, client)
                        .login(username, password)
                }
            }
            if (newKey != null && newUid != null) {
                apiKey = newKey
                userId = newUid
            } else {
                context?.let { Toast.makeText(it, "Login failed.", Toast.LENGTH_LONG).show() }
                return false
            }
        }
        return true
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        require(parentId.isNotEmpty()) { "Select library in the extension settings." }
        val startIndex = (page - 1) * SEASONS_LIMIT

        val url = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", apiKey)
            addQueryParameter("StartIndex", startIndex.toString())
            addQueryParameter("Limit", SEASONS_LIMIT.toString())
            addQueryParameter("Recursive", "true")
            addQueryParameter("SortBy", "SortName")
            addQueryParameter("SortOrder", "Ascending")
            addQueryParameter("IncludeItemTypes", "Movie,Season")
            addQueryParameter("ImageTypeLimit", "1")
            addQueryParameter("ParentId", parentId)
            addQueryParameter("EnableImageTypes", "Primary")
        }.build()

        return GET(url)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("StartIndex")!!.toInt() / SEASONS_LIMIT + 1
        val data = response.parseAs<ItemsDto>()
        val animeList = data.items.map { it.toSAnime(baseUrl, userId!!, apiKey!!) }
        return AnimesPage(animeList, SEASONS_LIMIT * page < data.itemCount)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = popularAnimeRequest(page).url.newBuilder().apply {
            setQueryParameter("SortBy", "DateCreated,SortName")
            setQueryParameter("SortOrder", "Descending")
        }.build()

        return GET(url)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage =
        popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = popularAnimeRequest(page).url.newBuilder().apply {
            // Search for series, rather than seasons, since season names can just be "Season 1"
            setQueryParameter("IncludeItemTypes", "Movie,Series")
            setQueryParameter("Limit", SERIES_LIMIT.toString())
            setQueryParameter("SearchTerm", query)
        }.build()

        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val page = response.request.url.queryParameter("StartIndex")!!.toInt() / SERIES_LIMIT + 1
        val data = response.parseAs<ItemsDto>()

        // Get all seasons from series
        val animeList = data.items.flatMap { series ->
            val seasonsUrl = popularAnimeRequest(1).url.newBuilder().apply {
                setQueryParameter("ParentId", series.id)
                removeAllQueryParameters("StartIndex")
                removeAllQueryParameters("Limit")
            }.build()

            val seasonsData = client.newCall(
                GET(seasonsUrl),
            ).execute().parseAs<ItemsDto>()

            seasonsData.items.map { it.toSAnime(baseUrl, userId!!, apiKey!!) }
        }

        return AnimesPage(animeList, SERIES_LIMIT * page < data.itemCount)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        if (!anime.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")
        return GET(anime.url)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<ItemDto>()
        val infoData = if (preferences.useSeriesData && data.seriesId != null) {
            val url = response.request.url.let { url ->
                url.newBuilder().apply {
                    removePathSegment(url.pathSize - 1)
                    addPathSegment(data.seriesId)
                }.build()
            }

            client.newCall(
                GET(url),
            ).execute().parseAs<ItemDto>()
        } else {
            data
        }

        return infoData.toSAnime(baseUrl, userId!!, apiKey!!)
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        if (!anime.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")
        val httpUrl = anime.url.toHttpUrl()
        val fragment = httpUrl.fragment!!

        // Get episodes of season
        val url = if (fragment.startsWith("seriesId")) {
            httpUrl.newBuilder().apply {
                encodedPath("/")
                encodedQuery(null)
                fragment(null)

                addPathSegment("Shows")
                addPathSegment(fragment.split(",").last())
                addPathSegment("Episodes")
                addQueryParameter("api_key", apiKey)
                addQueryParameter("seasonId", httpUrl.pathSegments.last())
                addQueryParameter("userId", userId)
                addQueryParameter("Fields", "Overview,MediaSources")
            }.build()
        } else if (fragment.startsWith("movie")) {
            httpUrl.newBuilder().fragment(null).build()
        } else {
            httpUrl
        }

        return GET(url)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val epDetails = preferences.getEpDetails

        val episodeList = if (response.request.url.toString().startsWith("$baseUrl/Users/")) {
            val data = response.parseAs<ItemDto>()
            listOf(data.toSEpisode(baseUrl, userId!!, apiKey!!, epDetails, EpisodeType.MOVIE))
        } else {
            val data = response.parseAs<ItemsDto>()
            data.items.map {
                it.toSEpisode(baseUrl, userId!!, apiKey!!, epDetails, EpisodeType.EPISODE)
            }
        }

        return episodeList.reversed()
    }

    enum class EpisodeType {
        EPISODE,
        MOVIE,
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        if (!episode.url.startsWith("http")) throw Exception("Migrate from jellyfin to jellyfin")
        return GET(episode.url)
    }

    override fun videoListParse(response: Response): List<Video> {
        val id = response.parseAs<ItemDto>().id

        val sessionData = client.newCall(
            GET("$baseUrl/Items/$id/PlaybackInfo?userId=$userId&api_key=$apiKey"),
        ).execute().parseAs<SessionDto>()

        val videoList = mutableListOf<Video>()
        val subtitleList = mutableListOf<Track>()
        val externalSubtitleList = mutableListOf<Track>()

        val prefSub = preferences.getSubPref
        val prefAudio = preferences.getAudioPref

        var audioIndex = 1
        var subIndex: Int? = null
        var width = 1920
        var height = 1080

        sessionData.mediaSources.first().mediaStreams.forEach { media ->
            when (media.type) {
                "Video" -> {
                    width = media.width!!
                    height = media.height!!
                }
                "Audio" -> {
                    if (media.lang != null && media.lang == prefAudio) {
                        audioIndex = media.index
                    }
                }
                "Subtitle" -> {
                    if (media.supportsExternalStream) {
                        val subtitleUrl = "$baseUrl/Videos/$id/$id/Subtitles/${media.index}/0/Stream.${media.codec}?api_key=$apiKey"
                        if (media.lang != null) {
                            if (media.lang == prefSub) {
                                try {
                                    if (media.isExternal) {
                                        externalSubtitleList.add(0, Track(subtitleUrl, media.displayTitle!!))
                                    }
                                    subtitleList.add(0, Track(subtitleUrl, media.displayTitle!!))
                                } catch (e: Exception) {
                                    subIndex = media.index
                                }
                            } else {
                                if (media.isExternal) {
                                    externalSubtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                                }
                                subtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                            }
                        } else {
                            if (media.isExternal) {
                                externalSubtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                            }
                            subtitleList.add(Track(subtitleUrl, media.displayTitle!!))
                        }
                    }
                }
            }
        }

        // Loop over qualities
        JellyfinConstants.QUALITIES_LIST.forEach { quality ->
            if (width < quality.width && height < quality.height) {
                val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
                videoList.add(Video(url, "Source", url, subtitleTracks = externalSubtitleList))

                return videoList.reversed()
            } else {
                val url = "$baseUrl/videos/$id/main.m3u8".toHttpUrl().newBuilder().apply {
                    addQueryParameter("api_key", apiKey)
                    addQueryParameter("VideoCodec", "h264")
                    addQueryParameter("AudioCodec", "aac,mp3")
                    addQueryParameter("AudioStreamIndex", audioIndex.toString())
                    subIndex?.let { addQueryParameter("SubtitleStreamIndex", it.toString()) }
                    addQueryParameter("VideoCodec", "h264")
                    addQueryParameter("VideoCodec", "h264")
                    addQueryParameter(
                        "VideoBitrate",
                        quality.videoBitrate.toString(),
                    )
                    addQueryParameter(
                        "AudioBitrate",
                        quality.audioBitrate.toString(),
                    )
                    addQueryParameter("PlaySessionId", sessionData.playSessionId)
                    addQueryParameter("TranscodingMaxAudioChannels", "6")
                    addQueryParameter("RequireAvc", "false")
                    addQueryParameter("SegmentContainer", "ts")
                    addQueryParameter("MinSegments", "1")
                    addQueryParameter("BreakOnNonKeyFrames", "true")
                    addQueryParameter("h264-profile", "high,main,baseline,constrainedbaseline")
                    addQueryParameter("h264-level", "51")
                    addQueryParameter("h264-deinterlace", "true")
                    addQueryParameter("TranscodeReasons", "VideoCodecNotSupported,AudioCodecNotSupported,ContainerBitrateExceedsLimit")
                }
                videoList.add(Video(url.toString(), quality.description, url.toString(), subtitleTracks = subtitleList))
            }
        }

        val url = "$baseUrl/Videos/$id/stream?static=True&api_key=$apiKey"
        videoList.add(Video(url, "Source", url, subtitleTracks = externalSubtitleList))

        return videoList.reversed()
    }

    // ============================= Utilities ==============================

    companion object {
        const val APIKEY_KEY = "api_key"
        const val USERID_KEY = "user_id"

        private const val HOSTURL_KEY = "host_url"
        private const val HOSTURL_DEFAULT = "http://127.0.0.1:8096"

        private const val USERNAME_KEY = "username"
        private const val USERNAME_DEFAULT = ""

        private const val PASSWORD_KEY = "password"
        private const val PASSWORD_DEFAULT = ""

        private const val MEDIALIB_KEY = "library_pref"
        private const val MEDIALIB_DEFAULT = ""

        private const val SEASONS_LIMIT = 20
        private const val SERIES_LIMIT = 5

        private const val PREF_EP_DETAILS_KEY = "pref_episode_details_key"
        private val PREF_EP_DETAILS = arrayOf("Overview", "Runtime", "Size")
        private val PREF_EP_DETAILS_DEFAULT = emptySet<String>()

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_DEFAULT = "eng"

        private const val PREF_AUDIO_KEY = "preferred_audioLang"
        private const val PREF_AUDIO_DEFAULT = "jpn"

        private const val PREF_INFO_TYPE = "preferred_meta_type"
        private const val PREF_INFO_DEFAULT = false

        private const val PREF_TRUST_CERT_KEY = "preferred_trust_all_certs"
        private const val PREF_TRUST_CERT_DEFAULT = false
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mediaLibPref = medialibPreference(screen)
        screen.addPreference(
            screen.editTextPreference(
                HOSTURL_KEY,
                "Host URL",
                HOSTURL_DEFAULT,
                baseUrl,
                false,
                "",
                mediaLibPref,
            ),
        )
        screen.addPreference(
            screen.editTextPreference(
                USERNAME_KEY,
                "Username",
                USERNAME_DEFAULT,
                username,
                false,
                "The account username",
                mediaLibPref,
            ),
        )
        screen.addPreference(
            screen.editTextPreference(
                PASSWORD_KEY,
                "Password",
                PASSWORD_DEFAULT,
                password,
                true,
                "••••••••",
                mediaLibPref,
            ),
        )
        screen.addPreference(mediaLibPref)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EP_DETAILS_KEY
            title = "Additional details for episodes"
            summary = "Show additional details about an episode in the scanlator field"
            entries = PREF_EP_DETAILS
            entryValues = PREF_EP_DETAILS
            setDefaultValue(PREF_EP_DETAILS_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = "Preferred sub language"
            entries = JellyfinConstants.PREF_ENTRIES
            entryValues = JellyfinConstants.PREF_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred audio language"
            entries = JellyfinConstants.PREF_ENTRIES
            entryValues = JellyfinConstants.PREF_VALUES
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_INFO_TYPE
            title = "Retrieve metadata from series"
            summary = """Enable this to retrieve metadata from series instead of season when applicable.""".trimMargin()
            setDefaultValue(PREF_INFO_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TRUST_CERT_KEY
            title = "Trust all certificates"
            summary = "Requires app restart to take effect."
            setDefaultValue(PREF_TRUST_CERT_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)
    }

    private val SharedPreferences.getApiKey
        get() = getString(APIKEY_KEY, null)

    private val SharedPreferences.getUserId
        get() = getString(USERID_KEY, null)

    private val SharedPreferences.getHostUrl
        get() = getString(HOSTURL_KEY, HOSTURL_DEFAULT)!!

    private val SharedPreferences.getUserName
        get() = getString(USERNAME_KEY, USERNAME_DEFAULT)!!

    private val SharedPreferences.getPassword
        get() = getString(PASSWORD_KEY, PASSWORD_DEFAULT)!!

    private val SharedPreferences.getMediaLibId
        get() = getString(MEDIALIB_KEY, MEDIALIB_DEFAULT)!!

    private val SharedPreferences.getEpDetails
        get() = getStringSet(PREF_EP_DETAILS_KEY, PREF_EP_DETAILS_DEFAULT)!!

    private val SharedPreferences.getSubPref
        get() = getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

    private val SharedPreferences.getAudioPref
        get() = getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)!!

    private val SharedPreferences.useSeriesData
        get() = getBoolean(PREF_INFO_TYPE, PREF_INFO_DEFAULT)

    private val SharedPreferences.getTrustCert
        get() = getBoolean(PREF_TRUST_CERT_KEY, PREF_TRUST_CERT_DEFAULT)

    private abstract class MediaLibPreference(context: Context) : ListPreference(context) {
        abstract fun reload()
    }

    private fun medialibPreference(screen: PreferenceScreen) =
        object : MediaLibPreference(screen.context) {
            override fun reload() {
                this.apply {
                    key = MEDIALIB_KEY
                    title = "Select Media Library"
                    summary = "%s"

                    Thread {
                        try {
                            val mediaLibsResponse = client.newCall(
                                GET("$baseUrl/Users/$userId/Items?api_key=$apiKey"),
                            ).execute()
                            val mediaJson = mediaLibsResponse.parseAs<ItemsDto>().items

                            val entriesArray = mediaJson.map { it.name }
                            val entriesValueArray = mediaJson.map { it.id }

                            entries = entriesArray.toTypedArray()
                            entryValues = entriesValueArray.toTypedArray()
                        } catch (ex: Exception) {
                            entries = emptyArray()
                            entryValues = emptyArray()
                        }
                    }.start()

                    setOnPreferenceChangeListener { _, newValue ->
                        val selected = newValue as String
                        val index = findIndexOfValue(selected)
                        val entry = entryValues[index] as String
                        parentId = entry
                        preferences.edit().putString(key, entry).commit()
                    }
                }
            }
        }.apply { reload() }

    private fun getSummary(isPassword: Boolean, value: String, placeholder: String) = when {
        isPassword && value.isNotEmpty() || !isPassword && value.isEmpty() -> placeholder
        else -> value
    }

    private fun PreferenceScreen.editTextPreference(key: String, title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String, mediaLibPref: MediaLibPreference): EditTextPreference {
        return EditTextPreference(context).apply {
            this.key = key
            this.title = title
            summary = getSummary(isPassword, value, placeholder)
            this.setDefaultValue(default)
            dialogTitle = title

            setOnBindEditTextListener {
                it.inputType = if (isPassword) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val newValueString = newValue as String
                    val res = preferences.edit().putString(key, newValueString).commit()
                    summary = getSummary(isPassword, newValueString, placeholder)
                    val loginRes = login(true, context)
                    if (loginRes == true) {
                        mediaLibPref.reload()
                    }
                    res
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
}

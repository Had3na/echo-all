package fr.nacre.media

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// Search, playlists, channels and playback through NewPipeExtractor. Downloads go through yt-dlp
// in YouTubeDownloads.kt, which reaches better video qualities because it can merge separate tracks.

private const val BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"

/** NewPipeExtractor speaks to the network only through this class. */
private class EchoDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val data = request.dataToSend()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), data?.toRequestBody(null, 0, data.size))
            .url(request.url())
            .addHeader("User-Agent", BROWSER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        val response = client.newCall(builder.build()).execute()
        // 429 means YouTube wants a captcha: there is nothing useful to read in the body.
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("YouTube demande une vérification anti-robot.", request.url())
        }
        val body = response.body?.string()
        return Response(response.code, response.message, response.headers.toMultimap(), body, response.request.url.toString())
    }
}

data class ResolvedMedia(
    val watchUrl: String,
    val streamUrl: String,
    val title: String,
    val artist: String,
    val uploader: String,
    val durationMs: Long,
    val thumbnail: String,
    val video: Boolean,
    val bitrateKbps: Int = 0,
    val height: Int = 0,
)

object YouTube {
    val FILTER_MUSIC: String = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
    val FILTER_VIDEOS: String = YoutubeSearchQueryHandlerFactory.VIDEOS
    val FILTER_PLAYLISTS: String = YoutubeSearchQueryHandlerFactory.PLAYLISTS
    val FILTER_CHANNELS: String = YoutubeSearchQueryHandlerFactory.CHANNELS

    /** YouTube hands out stream URLs that expire after a few hours, so they are held in memory only. */
    private const val CACHE_MS = 60L * 60 * 1000

    private class Cached(val media: ResolvedMedia, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    @Volatile private var started = false

    private val service: StreamingService get() = ServiceList.YouTube

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        NewPipe.init(EchoDownloader(), Localization("fr", "FR"), ContentCountry("FR"))
        started = true
    }

    suspend fun prepare() = withContext(Dispatchers.IO) { ensureStarted() }

    suspend fun search(query: String, filter: String): List<YouTubeResult> = withContext(Dispatchers.IO) {
        ensureStarted()
        val handler = service.searchQHFactory.fromQuery(query.trim(), listOf(filter), "")
        SearchInfo.getInfo(service, handler).relatedItems.mapNotNull { it.toResult() }
    }

    suspend fun playlist(url: String): YouTubePage = withContext(Dispatchers.IO) {
        ensureStarted()
        val info = PlaylistInfo.getInfo(service, url)
        val subtitle = listOf(cleanChannelName(info.uploaderName.orEmpty()),
            if (info.streamCount > 0) "${info.streamCount} titres" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")
        YouTubePage(info.name.orEmpty(), subtitle, info.thumbnails.pick(), YouTubeKind.PLAYLIST,
            info.relatedItems.mapNotNull { it.toResult() })
    }

    suspend fun channel(url: String): YouTubePage = withContext(Dispatchers.IO) {
        ensureStarted()
        val info = ChannelInfo.getInfo(service, url)
        // Music channels expose a "Tracks" tab; everything else falls back to plain videos.
        val tab = info.tabs.firstOrNull { ChannelTabs.TRACKS in it.contentFilters }
            ?: info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
            ?: info.tabs.firstOrNull()
        val items = tab?.let { handler ->
            ChannelTabInfo.getInfo(service, handler).relatedItems.mapNotNull { it.toResult() }
        }.orEmpty()
        YouTubePage(info.name.orEmpty(), formatSubscribers(info.subscriberCount), info.avatars.pick(), YouTubeKind.CHANNEL, items)
    }

    suspend fun resolve(watchUrl: String, video: Boolean, quality: AudioQuality, videoQuality: VideoQuality): ResolvedMedia =
        withContext(Dispatchers.IO) { resolveBlocking(watchUrl, video, quality, videoQuality) }

    /** Also called from the player's loading thread through [YouTubeStreamResolver], where blocking is expected. */
    fun resolveBlocking(watchUrl: String, video: Boolean, quality: AudioQuality, videoQuality: VideoQuality): ResolvedMedia {
        val id = youtubeVideoId(watchUrl) ?: throw IOException("Lien YouTube non reconnu.")
        val key = id + if (video) "|v" else "|a"
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let { return it.media }
        ensureStarted()
        val info = StreamInfo.getInfo(service, youtubeWatchUrl(id))
        val uploader = cleanChannelName(info.uploaderName.orEmpty())
        val (artist, title) = youtubeArtistTitle(info.name.orEmpty(), info.uploaderName.orEmpty())
        val media = if (video) {
            val stream = videoPick(info, videoQuality)
                ?: throw IOException("Aucune piste vidéo lisible directement pour cette vidéo.")
            ResolvedMedia(youtubeWatchUrl(id), stream.content, title, artist, uploader,
                info.duration * 1000, info.thumbnails.pick(), true, height = streamHeight(stream))
        } else {
            val stream = audioPick(info, quality)
                ?: throw IOException("Aucune piste audio lisible directement pour cette vidéo.")
            ResolvedMedia(youtubeWatchUrl(id), stream.content, title, artist, uploader,
                info.duration * 1000, info.thumbnails.pick(), false, bitrateKbps = normalizeKbps(stream.averageBitrate))
        }
        cache[key] = Cached(media, System.currentTimeMillis())
        return media
    }

    fun forget(watchUrl: String) {
        val id = youtubeVideoId(watchUrl) ?: return
        cache.remove("$id|a")
        cache.remove("$id|v")
    }

    // Only progressive HTTP entries play straight from a URL; DASH and HLS ones would need a manifest.
    private fun audioPick(info: StreamInfo, quality: AudioQuality): AudioStream? {
        val usable = info.audioStreams.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
        if (usable.isEmpty()) return null
        val rates = usable.map { normalizeKbps(it.averageBitrate) }
        val target = pickBitrate(rates, quality.ceilingKbps) ?: return usable.first()
        return usable.firstOrNull { normalizeKbps(it.averageBitrate) == target } ?: usable.first()
    }

    /** videoStreams carry their sound; videoOnlyStreams would need a second source merged in, so 720p is the ceiling here. */
    private fun videoPick(info: StreamInfo, quality: VideoQuality): VideoStream? {
        val usable = info.videoStreams.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }
        if (usable.isEmpty()) return null
        return usable.filter { streamHeight(it) in 1..quality.maxHeight }.maxByOrNull { streamHeight(it) }
            ?: usable.minByOrNull { val h = streamHeight(it); if (h > 0) h else Int.MAX_VALUE }
    }

    private val RESOLUTION = Regex("""(\d{3,4})p""")

    private fun streamHeight(stream: VideoStream): Int {
        val exact = stream.height
        if (exact > 0) return exact
        return RESOLUTION.find(stream.getResolution().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun List<Image>?.pick(): String {
        val images = this.orEmpty()
        return images.filter { it.height > 0 }.minByOrNull { abs(it.height - 480) }?.url
            ?: images.firstOrNull()?.url.orEmpty()
    }

    private fun InfoItem.toResult(): YouTubeResult? {
        val result = when (this) {
            is StreamInfoItem -> YouTubeResult(url.orEmpty(), name.orEmpty(), cleanChannelName(uploaderName.orEmpty()),
                YouTubeKind.VIDEO, duration * 1000, thumbnails.pick(), viewCount,
                live = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM)
            is PlaylistInfoItem -> YouTubeResult(url.orEmpty(), name.orEmpty(), cleanChannelName(uploaderName.orEmpty()),
                YouTubeKind.PLAYLIST, thumbnail = thumbnails.pick(), itemCount = streamCount)
            is ChannelInfoItem -> YouTubeResult(url.orEmpty(), name.orEmpty(), "",
                YouTubeKind.CHANNEL, thumbnail = thumbnails.pick(), itemCount = subscriberCount)
            else -> null
        }
        return result?.takeIf { it.url.isNotBlank() && it.title.isNotBlank() }
    }
}

/**
 * Rewrites a YouTube watch URL into the real stream URL as the player opens it.
 * Doing it here rather than when the queue is built keeps every entry lazy: a 200-track
 * playlist costs one extraction per track actually played, and an expired URL is fetched again on retry.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeStreamResolver(
    private val audioQuality: () -> AudioQuality,
    private val videoQuality: () -> VideoQuality,
    private val blocked: () -> Boolean = { false },
) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri.toString()
        if (youtubeVideoId(uri) == null) return dataSpec
        // Last line of defence: a track resumed from the library never reaches the screens that check.
        if (blocked()) throw IOException("Streaming YouTube limité au Wi-Fi dans les réglages.")
        val media = try {
            YouTube.resolveBlocking(cleanWatchUrl(uri), wantsVideoStream(uri), audioQuality(), videoQuality())
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            // The player only understands IOException; anything else would surface as a crash.
            throw IOException(error.message ?: "Lecture YouTube impossible.", error)
        }
        return dataSpec.withUri(Uri.parse(media.streamUrl))
    }
}

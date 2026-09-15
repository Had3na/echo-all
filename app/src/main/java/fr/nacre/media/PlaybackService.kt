package fr.nacre.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val rack by lazy { SoundRack(this) }
    private fun releaseDeck(player: ExoPlayer) { rack.detach(player); player.release() }
    private var session: MediaSession? = null
    private lateinit var active: ExoPlayer
    private var incoming: ExoPlayer? = null
    private var outgoing: ExoPlayer? = null
    private var manualHold = false
    private val timeline = MixTimeline()
    private var lastMarked = ""
    private var djMessage = ""
    private val studio by lazy { StudioStore(this) }
    private val trackCache = HashMap<String, TrackTools>()
    private var stopStudioEvents: () -> Unit = {}
    private fun track(id: String?) = trackCache.getOrPut(id.orEmpty()) { studio.track(id.orEmpty()) }
    private fun mixMs() = prefs.getInt("mixSeconds", 3).coerceIn(0, 60) * 1000L
    private var automatic = false
    private var failedAutoSource: String? = null
    private var pendingSince = 0L
    private var lastSave = 0L
    private var baseVolume = 1f
    private var committing = false
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val positions by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private val pulse = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, if (active.isPlaying || incoming != null || outgoing != null) 50L else 1000L) }
    }

    private fun deck(focus: Boolean): ExoPlayer = SoundChain().let { chain -> ExoPlayer.Builder(this, rack.renderers(chain)).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
        setAudioAttributes(attributes, focus)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_NETWORK)
        rack.attach(this, chain)
        val self = this
        addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (self === active && !playWhenReady && !committing) { cancelMix(); savePosition(); if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) prefs.edit().putBoolean("sleepEnd", false).apply() }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                if (self === active && playing) {
                    val id = active.currentMediaItem?.mediaId.orEmpty()
                    if (id != lastMarked && !prefs.getBoolean("private", false)) { studio.markPlayed(id); lastMarked = id }
                }
            }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                if (self === active && reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE && !committing) cancelMix()
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (self !== active || committing) return
                cancelMix()
                old.mediaItem?.mediaId?.let { id ->
                    if (id != new.mediaItem?.mediaId && !prefs.getBoolean("private", false)) positions.edit().putLong(id,
                        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) 0 else old.positionMs.coerceAtLeast(0)).apply()
                }
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (self === active && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !committing) { cancelMix(); saveQueue() }
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { if (self === active && !committing) cancelIncoming() }
            override fun onRepeatModeChanged(mode: Int) { if (self === active && !committing) cancelIncoming() }
            override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) { if (self === active && !committing) cancelMix() }
            override fun onPlayerError(error: PlaybackException) {
                if (self === incoming) fallbackPending()
                if (self === active) cancelMix()
            }
        })
    } }

    // Route session, headset and notification skip commands through the same transition path.
    private fun controls(player: ExoPlayer): Player = object : ForwardingPlayer(player) {
        override fun seekToNextMediaItem() { next() }
        override fun seekToNext() { next() }
        override fun seekToPreviousMediaItem() { previous() }
        override fun seekToPrevious() {
            if (active.currentPosition > 3000) { cancelMix(); active.seekTo(0) } else previous()
        }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex != active.currentMediaItemIndex) requestTransition(mediaItemIndex, positionMs, false)
            else { cancelMix(); active.seekTo(positionMs) }
        }
        override fun setVolume(volume: Float) { cancelMix(); active.volume = volume }
    }

    override fun onCreate() {
        super.onCreate()
        active = deck(true)
        stopStudioEvents = StudioEvents.listen { key -> if (key.startsWith("track:")) trackCache.remove(key.removePrefix("track:")) }
        restoreQueue()
        prefs.edit().putLong("sleepUntil", 0).apply()
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, controls(active)).setSessionActivity(launch).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand("studio", Bundle.EMPTY)).build()).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (command.customAction != "studio") return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
                return try { Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, studioOperation(args))) }
                    catch (_: Exception) { Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE)) }
            }
        }).build()
        handler.post(pulse)
    }

    private fun next() {
        if (active.hasNextMediaItem()) requestTransition(active.nextMediaItemIndex, 0, false)
    }
    private fun previous() {
        if (active.hasPreviousMediaItem()) requestTransition(active.previousMediaItemIndex, 0, false)
        else { cancelMix(); active.seekTo(0) }
    }

    private fun requestTransition(index: Int, position: Long, auto: Boolean) {
        if (index !in 0 until active.mediaItemCount) return
        if (incoming != null || outgoing != null) cancelMix()
        val duration = mixMs()
        if (duration == 0L || !active.isPlaying || active.isCurrentMediaItemLive || index == active.currentMediaItemIndex) {
            if (!auto) active.seekTo(index, position)
            return
        }
        automatic = auto
        pendingSince = SystemClock.elapsedRealtime()
        val candidate = deck(false)
        incoming = candidate
        candidate.volume = 0f
        candidate.repeatMode = active.repeatMode
        candidate.shuffleModeEnabled = active.shuffleModeEnabled
        candidate.playbackParameters = active.playbackParameters
        if (prefs.getBoolean("tempoSync", false)) {
            val match = matchedSpeed(track(active.currentMediaItem?.mediaId).bpm, active.playbackParameters.speed, track(active.getMediaItemAt(index).mediaId).bpm)
            if (match != null) candidate.setPlaybackSpeed(match) else djMessage = "SYNC ignoré : renseigne les BPM, avec un écart de tempo inférieur à 25 %."
        }
        candidate.setMediaItems(List(active.mediaItemCount) { active.getMediaItemAt(it) }, index, if (position == 0L) track(active.getMediaItemAt(index).mediaId).cueIn else position)
        candidate.prepare()
    }

    private fun commit(manual: Boolean = false) {
        val candidate = incoming ?: return
        if (!active.isPlaying || candidate.playbackState != Player.STATE_READY) return
        if (candidate.isCurrentMediaItemLive) { manualHold = false; fallbackPending(); return }
        incoming = null
        val old = active
        baseVolume = old.volume
        val window = mixWindow(mixMs(), old.currentPosition, track(old.currentMediaItem?.mediaId).cueOut, old.duration, old.playbackParameters.speed)
        committing = true
        try {
            if (!prefs.getBoolean("private", false)) positions.edit().putLong(old.currentMediaItem?.mediaId.orEmpty(), if (automatic) 0 else old.currentPosition).apply()
            old.setPauseAtEndOfMediaItems(true)
            old.setAudioAttributes(attributes, false)
            active = candidate
            outgoing = old
            candidate.setAudioAttributes(attributes, true)
            session?.setPlayer(controls(candidate))
            candidate.play()
            timeline.begin(SystemClock.elapsedRealtime(), window, manual)
            if (!prefs.getBoolean("private", false)) { studio.markPlayed(candidate.currentMediaItem?.mediaId.orEmpty()); lastMarked = candidate.currentMediaItem?.mediaId.orEmpty() }
            saveQueue()
        } finally { committing = false }
    }

    private fun tick() {
        rack.update()
        val now = SystemClock.elapsedRealtime()
        val sleepUntil = prefs.getLong("sleepUntil", 0)
        if (sleepUntil > 0 && now >= sleepUntil) {
            cancelMix(); active.pause(); prefs.edit().putLong("sleepUntil", 0).apply()
        }
        active.setPauseAtEndOfMediaItems(prefs.getBoolean("sleepEnd", false))
        val tools = track(active.currentMediaItem?.mediaId)
        if (outgoing == null && incoming == null && active.isPlaying && !prefs.getBoolean("sleepEnd", false) && tools.loop && tools.loopOut > tools.loopIn && active.currentPosition >= tools.loopOut) {
            active.seekTo(tools.loopIn)
        }
        val fading = outgoing
        if (fading != null) {
            val exit = track(fading.currentMediaItem?.mediaId).cueOut
            if (timeline.manual && ((exit > 0 && fading.currentPosition >= exit) || fading.playbackState == Player.STATE_ENDED || !fading.playWhenReady)) { finishOutgoing() }
            else if (!active.playWhenReady || active.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || active.playbackState == Player.STATE_BUFFERING) {
                cancelMix()
            } else {
                val frame = timeline.frame(now, prefs.getString("mixStyle", "smooth").orEmpty())
                applyMix(fading, frame.outgoing)
                applyMix(active, frame.incoming)
                if (frame.done) finishOutgoing()
            }
        } else {
            val seconds = (mixMs() / 1000).toInt()
            val remaining = remainingMs(tools, active.currentPosition, active.duration, active.playbackParameters.speed)
            if (!tools.loop && tools.cueOut > 0 && remaining <= 0 && incoming == null && active.isPlaying) {
                if (prefs.getBoolean("sleepEnd", false)) { active.pause(); prefs.edit().putBoolean("sleepEnd", false).apply() }
                else if (active.repeatMode == Player.REPEAT_MODE_ONE) active.seekTo(tools.cueIn)
                else if (active.hasNextMediaItem()) active.seekTo(active.nextMediaItemIndex, track(active.getMediaItemAt(active.nextMediaItemIndex).mediaId).cueIn)
                else active.pause()
            }
            if (seconds == 0) cancelIncoming()
            if (incoming != null) {
                if (!active.playWhenReady) cancelIncoming()
                else if (incoming?.playbackState != Player.STATE_READY && now - pendingSince > 10_000) fallbackPending()
                else if (incoming?.playbackState == Player.STATE_READY && !manualHold && (!automatic || remaining <= seconds * 1000L)) commit()
            } else if (!tools.loop && !prefs.getBoolean("sleepEnd", false) && active.currentMediaItem?.mediaId != failedAutoSource && active.isPlaying && !active.isCurrentMediaItemLive && active.repeatMode != Player.REPEAT_MODE_ONE && active.hasNextMediaItem()
                && autoMixDue(remaining, seconds)) {
                requestTransition(active.nextMediaItemIndex, 0, true)
            }
        }
        if (now - lastSave >= 5000) { savePosition(); lastSave = now }
    }

    private fun fallbackPending() {
        val candidate = incoming ?: return
        val index = candidate.currentMediaItemIndex
        val position = candidate.currentPosition
        val manual = !automatic
        if (automatic) failedAutoSource = active.currentMediaItem?.mediaId
        cancelIncoming()
        if (manual && index in 0 until active.mediaItemCount) {
            active.seekTo(index, position)
            active.prepare()
        }
    }

    private fun cancelIncoming() {
        val candidate = incoming
        incoming = null
        candidate?.let { releaseDeck(it) }
    }
    private fun applyMix(player: ExoPlayer, mix: DeckMix) {
        player.volume = baseVolume * mix.gain
        rack.shape(player, mix.shape)
    }
    // Single exit of every transition (end, pause, skip): the remaining track gets its volume and usual sound back.
    private fun finishOutgoing() {
        val old = outgoing
        outgoing = null
        timeline.stop()
        old?.let { releaseDeck(it) }
        applyMix(active, MixTimeline.SETTLED)
    }
    private fun cancelMix() { manualHold = false; timeline.stop(); cancelIncoming(); if (outgoing != null) finishOutgoing() }

    private fun studioOperation(args: Bundle): Bundle {
        when (args.getString("op", "status")) {
            "arm" -> {
                if (!active.isPlaying) djMessage = "Lance une piste avant de préparer le mix."
                else if (!active.hasNextMediaItem()) djMessage = "Ajoute un prochain média à la file."
                else { requestTransition(active.nextMediaItemIndex, 0, false); manualHold = incoming != null; djMessage = "Préparation du prochain média…" }
            }
            "manual" -> { if (incoming?.playbackState == Player.STATE_READY) { manualHold = false; commit(manual = true); djMessage = "Deux pistes en lecture : déplace le curseur A → B." } else djMessage = "Le prochain média n’est pas encore prêt." }
            "blend" -> { if (timeline.manual) timeline.move(args.getFloat("value")) }
            "finish" -> { manualHold = false; if (timeline.manual) timeline.release(SystemClock.elapsedRealtime()) else if (incoming?.playbackState == Player.STATE_READY) commit() }
            "releaseUi" -> { if (timeline.manual) timeline.release(SystemClock.elapsedRealtime()) else if (manualHold) cancelMix() }
            "cancel" -> { cancelMix(); djMessage = "Préparation annulée." }
            "cue" -> { cancelMix(); active.seekTo(track(active.currentMediaItem?.mediaId).cueIn) }
        }
        return Bundle().apply {
            putString("message", djMessage); putBoolean("ready", incoming?.playbackState == Player.STATE_READY)
            putBoolean("manual", timeline.manual); putBoolean("mixing", outgoing != null); putFloat("blend", if (outgoing != null) timeline.progress(SystemClock.elapsedRealtime()) else 0f)
            putString("a", (outgoing ?: active).mediaMetadata.title?.toString().orEmpty())
            putString("b", (if (outgoing != null) active else incoming)?.mediaMetadata?.title?.toString().orEmpty())
            putString("id", active.currentMediaItem?.mediaId.orEmpty())
        }
    }

    private fun saveQueue() {
        if (prefs.getBoolean("private", false)) return
        val queue = JSONArray()
        for (i in 0 until active.mediaItemCount) {
            val item = active.getMediaItemAt(i)
            queue.put(JSONObject().apply { put("id", item.mediaId); put("uri", item.localConfiguration?.uri?.toString() ?: item.mediaId)
                put("title", item.mediaMetadata.title); put("artist", item.mediaMetadata.artist); put("video", item.mediaMetadata.extras?.getBoolean("video") == true) })
        }
        positions.edit().putString("queue", queue.toString()).apply()
    }
    private fun restoreQueue() {
        runCatching {
            val queue = JSONArray(positions.getString("queue", "[]"))
            if (queue.length() == 0) return
            val items = List(queue.length()) { i -> val j = queue.getJSONObject(i)
                MediaItem.Builder().setMediaId(j.getString("id")).setUri(j.getString("uri"))
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(j.optString("title")).setArtist(j.optString("artist"))
                        .setExtras(Bundle().apply { putBoolean("video", j.optBoolean("video")) }).build()).build() }
            val index = items.indexOfFirst { it.mediaId == positions.getString("last", "") }.coerceAtLeast(0)
            active.setMediaItems(items, index, positions.getLong(items[index].mediaId, 0))
            active.shuffleModeEnabled = positions.getBoolean("shuffle", false); active.repeatMode = positions.getInt("repeat", 0)
        }
    }
    private fun savePosition() {
        if (prefs.getBoolean("private", false)) return
        val id = active.currentMediaItem?.mediaId ?: return
        val position = if (active.playbackState == Player.STATE_ENDED) 0 else active.currentPosition.coerceAtLeast(0)
        positions.edit().putBoolean("shuffle", active.shuffleModeEnabled).putInt("repeat", active.repeatMode).putString("last", id).putLong(id, position).apply()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        handler.removeCallbacks(pulse)
        savePosition(); saveQueue(); cancelMix()
        stopStudioEvents()
        releaseDeck(active); session?.release(); session = null
        super.onDestroy()
    }
}

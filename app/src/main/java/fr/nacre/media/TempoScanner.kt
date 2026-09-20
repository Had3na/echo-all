package fr.nacre.media

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Measures a track's tempo without being asked.
 *
 * The DJ studio could already do it, but only for the one track you opened it on, which made tempo
 * matching something you had to prepare by hand. The player now asks for the track it starts and
 * for the one after it, so an automatic transition has both figures by the time it needs them.
 *
 * Only local files can be measured: a stream is not decoded here, so YouTube tracks keep no tempo.
 */
/**
 * The order the shuffle button hands to the player. With the setting on, each track follows the one
 * closest to it in tempo, then in genre, while pushing the same artist apart; with it off, or before
 * anything has been measured, it falls back to the plain shuffle it replaced.
 */
suspend fun shuffleForPlayback(context: Context, items: List<LibraryItem>): List<LibraryItem> =
    withContext(Dispatchers.IO) {
        if (items.size <= 2) return@withContext items.shuffled()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("smartShuffle", true)) return@withContext items.shuffled()
        val store = StudioStore(context)
        val profiles = items.map { TrackProfile(it.uri, store.track(it.uri).bpm, it.artist, it.genre) }
        val byUri = items.associateBy { it.uri }
        smartOrder(profiles, seed = System.currentTimeMillis()).mapNotNull { byUri[it.uri] }
    }

object TempoScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One at a time: decoding is heavy, and a queue of them would fight the playback itself.
    private val gate = Mutex()
    private val asked = Collections.synchronizedSet(mutableSetOf<String>())

    /** Measures a whole selection ahead of time, so a smart shuffle has figures to work with. */
    fun requestAll(context: Context, uris: List<String>) {
        uris.take(40).forEach { request(context, it) }
    }

    fun request(context: Context, uri: String?) {
        if (uri.isNullOrBlank() || !uri.startsWith("content://")) return
        if (!asked.add(uri)) return
        val app = context.applicationContext
        scope.launch {
            gate.withLock {
                val store = StudioStore(app)
                val known = store.track(uri)
                if (known.bpm > 0f) return@withLock
                val measured = runCatching { analyzeAudio(app, uri) }.getOrNull() ?: return@withLock
                if (measured.bpm <= 0f) return@withLock
                // Keep the cue points and loop the user set; only the measured figures are new.
                store.saveTrack(uri, known.copy(bpm = measured.bpm, confidence = measured.confidence,
                    wave = if (known.wave.isEmpty()) measured.wave else known.wave))
            }
        }
    }
}

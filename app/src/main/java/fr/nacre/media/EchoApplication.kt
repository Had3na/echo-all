package fr.nacre.media

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

class EchoApplication : Application() {
    /**
     * Android's pressure levels are not ordered the way they read: RUNNING_LOW (10) and
     * RUNNING_CRITICAL (15) sit *below* UI_HIDDEN (20). Freeing only at 20 and above meant nothing
     * was ever released while the application was on screen — exactly when the system is deciding
     * whether to kill it. The foreground levels now halve the caches, and leaving the screen still
     * clears them outright.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val hidden = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        val squeezed = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (hidden) {
            trimMediaThumbnails()
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        } else if (squeezed) {
            halveMediaThumbnails()
            runCatching {
                // Coil's memory cache exposes no partial trim, so dropping half its keys is the nearest thing.
                coil.Coil.imageLoader(this).memoryCache?.let { cache ->
                    cache.keys.take(cache.keys.size / 2).forEach { key -> cache.remove(key) }
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last-crash.txt").writeText(
                    "Date : ${java.util.Date()}\nThread : ${thread.name}\n" + crashStack(error))
            }
            // Preserve Android's normal termination; never continue a crashed process.
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
}

/** Exception messages may contain URLs or API keys. Keep only types and stack frames. */
internal fun crashStack(error: Throwable): String = buildString {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    repeat(8) {
        val cause = current ?: return@buildString
        if (!seen.add(cause)) return@buildString
        appendLine(cause.javaClass.name)
        cause.stackTrace.take(35).forEach { appendLine("  $it") }
        current = cause.cause
    }
}.take(24_000)

internal fun torrentCrashReport(context: Context): String = buildString {
    appendLine("Echo-All ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
    appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Architecture : ${Build.SUPPORTED_ABIS.joinToString()}")
    val file = File(context.filesDir, "last-crash.txt")
    if (file.exists()) {
        appendLine("\nDernière exception enregistrée :")
        appendLine(file.inputStream().bufferedReader().use { it.readText().take(24_000) })
    }
    if (Build.VERSION.SDK_INT >= 30) {
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 5)
        appendLine("\nDerniers arrêts signalés par Android :")
        exits.forEach { exit ->
            val reason = when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "Exception Java"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Plantage natif"
                android.app.ApplicationExitInfo.REASON_ANR -> "Application bloquée (ANR)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android a libéré de la mémoire en arrêtant ce processus"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "Arrêt demandé par l’utilisateur"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "Arrêt par signal"
                android.app.ApplicationExitInfo.REASON_OTHER -> "Arrêt décidé par le système"
                android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "Mise à jour de l’application"
                else -> "Code ${exit.reason}"
            }
            appendLine("${java.util.Date(exit.timestamp)} · $reason · statut ${exit.status} · processus ${exit.processName} · importance ${exit.importance}")
        }
        if (exits.isEmpty()) appendLine("Aucun arrêt conservé par Android.")
    } else if (!file.exists()) appendLine("Aucun rapport disponible. Réessaie un torrent puis rouvre cet écran si l’application se ferme.")
}

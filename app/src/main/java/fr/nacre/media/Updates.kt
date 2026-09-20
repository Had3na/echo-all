package fr.nacre.media

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What a release manifest announces. Metadata only: the file itself is fetched separately, so a
 * manifest that cannot be parsed can never start a download.
 */
data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

/**
 * Reads a manifest of the shape
 * `{"versionCode": 24, "versionName": "0.24.0", "apk": "https://…/Echo-All-0.24.apk", "notes": "…"}`.
 * Returns null rather than throwing: a malformed manifest is a reason to stay on this version, not
 * to show an error.
 */
fun parseUpdateManifest(json: JSONObject, abi: String = ""): UpdateInfo? {
    val code = json.optInt("versionCode", 0)
    val url = json.optJSONObject("apks")?.optString(abi).orEmpty().ifBlank { json.optString("apk") }.trim()
    // The same guard media links get: HTTPS, a real host, and no credentials smuggled in the URL.
    if (code <= 0 || !validStreamUrl(url)) return null
    return UpdateInfo(code, json.optString("versionName").trim().ifBlank { code.toString() },
        url, plainText(json.optString("notes")).take(2_000))
}

fun updateAvailable(currentCode: Int, info: UpdateInfo?): Boolean = info != null && info.versionCode > currentCode

/** Installed version, as the number the manifest is compared against. */
fun installedVersionCode(context: Context): Int = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
    else @Suppress("DEPRECATION") info.versionCode
}.getOrDefault(0)

object Updates {
    const val DEFAULT_URL = "https://github.com/Had3na/echo-all/releases/latest/download/version.json"
    fun manifestUrl(prefs: android.content.SharedPreferences): String =
        prefs.getString("updateUrl", "").orEmpty().ifBlank { DEFAULT_URL }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /** A day between automatic looks: often enough to notice, rare enough to ignore. */
    private const val EVERY_MS = 24L * 60 * 60 * 1000

    fun dueForCheck(prefs: android.content.SharedPreferences): Boolean =
        System.currentTimeMillis() - prefs.getLong("updateCheckedAt", 0) >= EVERY_MS

    suspend fun check(context: Context, url: String): UpdateInfo? = withContext(Dispatchers.IO) {
        require(validStreamUrl(url)) { "Indique une adresse HTTPS." }
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Echo-All").build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Le serveur a répondu ${response.code}.")
            val source = response.body?.source() ?: throw IOException("Réponse vide.")
            // A manifest is a few lines; anything larger is not one.
            if (source.request(65_537)) throw IOException("Ce n’est pas un manifeste de version.")
            source.readUtf8()
        }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putLong("updateCheckedAt", System.currentTimeMillis()).apply()
        parseUpdateManifest(JSONObject(body), Build.SUPPORTED_ABIS.firstOrNull().orEmpty()) ?: throw IOException("Manifeste de version invalide.")
    }

    /** Hands the file to Android's download manager, which survives leaving the application. */
    fun download(context: Context, info: UpdateInfo): Long {
        val manager = context.getSystemService(DownloadManager::class.java)
        val name = "Echo-All-${safeFileName(info.versionName)}.apk"
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Echo-All ${info.versionName}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        return manager.enqueue(request)
    }

    fun downloadState(context: Context, id: Long): Int =
        context.getSystemService(DownloadManager::class.java)
            .query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                else DownloadManager.STATUS_FAILED
            } ?: DownloadManager.STATUS_FAILED

    /**
     * The installer screen for a finished download. Android asks for confirmation itself, and the
     * package it shows must match this application's signature or it refuses the update.
     */
    fun installer(context: Context, id: Long): Intent? {
        val uri = context.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id) ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

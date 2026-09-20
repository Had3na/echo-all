package fr.nacre.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun scanPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    buildList {
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_IMAGES)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }.toTypedArray()
} else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

fun Context.hasPermission(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.canScan(kind: MediaKind): Boolean {
    if (Build.VERSION.SDK_INT < 33) return hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    val full = hasPermission(when (kind) {
        MediaKind.MUSIC -> Manifest.permission.READ_MEDIA_AUDIO
        MediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
        MediaKind.PHOTO -> Manifest.permission.READ_MEDIA_IMAGES
    })
    return full || (kind != MediaKind.MUSIC && Build.VERSION.SDK_INT >= 34 && hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
}

data class ScanResult(val items: List<LibraryItem>, val successfulKinds: Set<MediaKind>, val failed: Boolean)

suspend fun scanPhone(context: Context): ScanResult = withContext(Dispatchers.IO) {
    val result = mutableListOf<LibraryItem>()
    val successful = mutableSetOf<MediaKind>()
    var failed = false
    MediaKind.entries.forEach { kind ->
        if (!context.canScan(kind)) return@forEach
        try {
            val collection = when (kind) {
                MediaKind.MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                MediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val projection = buildList {
                add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.DATE_ADDED)
                if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
                if (kind != MediaKind.PHOTO) add("duration")
                if (kind == MediaKind.MUSIC) { add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ALBUM)
                    // The index only exposes a genre column from Android 11 on.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Audio.Media.GENRE) }
            }.toTypedArray()
            val kindItems = mutableListOf<LibraryItem>()
            context.contentResolver.query(collection, projection, null, null, "date_added DESC")?.use { cursor ->
                fun string(column: String): String = cursor.getColumnIndex(column).let { if (it >= 0) cursor.getString(it).orEmpty() else "" }
                fun number(column: String): Long = cursor.getColumnIndex(column).let { if (it >= 0) cursor.getLong(it) else 0 }
                while (cursor.moveToNext()) {
                    val display = string(MediaStore.MediaColumns.DISPLAY_NAME)
                    val title = if (kind == MediaKind.MUSIC) string(MediaStore.Audio.Media.TITLE).ifBlank { display } else display
                    kindItems += LibraryItem(ContentUris.withAppendedId(collection, number("_id")).toString(), title,
                        kind, "Téléphone", scanned = true, durationMs = number("duration"),
                        artist = if (kind == MediaKind.MUSIC) string("artist").takeUnless { it == "<unknown>" }.orEmpty() else "",
                        folder = if (Build.VERSION.SDK_INT >= 29) string("relative_path").trimEnd('/') else "Téléphone",
                        addedAt = number("date_added") * 1000, album = if (kind == MediaKind.MUSIC) string("album") else "",
                        genre = if (kind == MediaKind.MUSIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) string("genre") else "")
                }
            } ?: error("Catalogue indisponible")
            result += kindItems
            successful += kind
        } catch (_: Exception) { failed = true }
    }
    ScanResult(result, successful, failed)
}

// Failed categories keep their entries; a revoked grant hides only scanned entries.
fun mergeScan(old: List<LibraryItem>, scan: ScanResult, allowed: Set<MediaKind>, hidden: Set<String>): List<LibraryItem> {
    val known = old.associateBy { it.uri }
    val retained = old.filter { !it.scanned || (it.kind in allowed && it.kind !in scan.successfulKinds) }
    val found = scan.items.filterNot { it.uri in hidden }.map { item ->
        val previous = known[item.uri]
        if (previous?.tagged == true || previous?.autoMetadataBlocked == true) item.copy(favorite = previous.favorite, title = previous.title, artist = previous.artist, album = previous.album, tagged = previous.tagged, videoSection = previous.videoSection, videoCategory = previous.videoCategory, metadataUndo = previous.metadataUndo, autoMetadataBlocked = previous.autoMetadataBlocked)
        else item.copy(favorite = previous?.favorite ?: false, videoSection = previous?.videoSection.orEmpty(), videoCategory = previous?.videoCategory.orEmpty(), metadataUndo = previous?.metadataUndo.orEmpty(), autoMetadataBlocked = previous?.autoMetadataBlocked ?: false)
    }
    return (retained + found).distinctBy { it.uri }
}

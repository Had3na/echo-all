package fr.nacre.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Covers are copied into the app, downscaled: no persisted URI grant (Android caps them),
 * and the picture keeps working if the original is moved or deleted.
 */
object Covers {
    private const val MAX_SIDE = 720
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }
    private fun newFile(context: Context, key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        // A new name per change so image caches never show the previous picture.
        return File(dir(context), "$hash-${System.currentTimeMillis()}.jpg")
    }
    fun forget(context: Context, path: String) = deleteStored(context, path)
    private fun deleteStored(context: Context, path: String?) {
        if (path == null) return
        if (path.startsWith("/")) File(path).takeIf { it.parentFile == dir(context) }?.delete()
        else runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(path), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Model for Coil: a private file, or a not-yet-copied content:// reference from an older version. */
    fun model(path: String): Any = if (path.startsWith("/")) File(path) else path

    private fun decode(context: Context, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val side = maxOf(info.size.width, info.size.height)
            if (side > MAX_SIDE) decoder.setTargetSize(info.size.width * MAX_SIDE / side, info.size.height * MAX_SIDE / side)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        } else {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Image illisible.")
            val rotation = resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
                }
            } ?: 0
            if (rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }

    private fun scaled(bitmap: Bitmap): Bitmap {
        val side = maxOf(bitmap.width, bitmap.height)
        return if (side <= MAX_SIDE) bitmap else Bitmap.createScaledBitmap(bitmap, bitmap.width * MAX_SIDE / side, bitmap.height * MAX_SIDE / side, true)
    }

    /** Blocking: call from a background thread. */
    fun save(context: Context, key: String, source: Uri) {
        val file = newFile(context, key)
        file.outputStream().use { scaled(decode(context, source)).compress(Bitmap.CompressFormat.JPEG, 88, it) }
        deleteStored(context, StudioStore(context).setCover(key, file.absolutePath))
    }

    /** First image found among [urls] (Cover Art Archive, Internet Archive), stored like a picked cover. False if none exists. */
    suspend fun saveFromUrls(context: Context, key: String, urls: List<String>, onlyIfMissing: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val bytes = urls.firstNotNullOfOrNull { Online.image(it) } ?: return@withContext false
        currentCoroutineContext().ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Image illisible.")
        val file = newFile(context, key)
        file.outputStream().use { scaled(bitmap).compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (onlyIfMissing) { if (!StudioStore(context).setCoverIfMissing(key, file.absolutePath)) file.delete() }
        else deleteStored(context, StudioStore(context).setCover(key, file.absolutePath))
        true
    }

    fun clear(context: Context, key: String) { deleteStored(context, StudioStore(context).removeCover(key)) }

    fun duplicate(context: Context, from: String, to: String) {
        val path = StudioStore(context).cover(from) ?: return
        if (!path.startsWith("/")) return
        val file = newFile(context, to)
        File(path).copyTo(file)
        deleteStored(context, StudioStore(context).setCover(to, file.absolutePath))
    }

    /** Covers chosen before 0.7 were links to the original picture: copy them in, then give the grant back. Blocking. */
    fun adoptLinked(context: Context) {
        StudioStore(context).covers().filterValues { it.startsWith("content://") }.forEach { (key, link) -> runCatching { save(context, key, Uri.parse(link)) } }
    }

    /** Backup form: the stored JPEG inline, so covers survive a restore on another phone. Blocking. */
    fun exportValue(path: String): String? =
        if (path.startsWith("/")) runCatching { "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(File(path).readBytes()) }.getOrNull() else null

    /** Turns backup covers (data:image/…) into private files. Returns the key → value map to store. Blocking. */
    fun importValues(context: Context, covers: Map<String, String>): Map<String, String> = covers.mapNotNull { (key, value) ->
        if (!value.startsWith("data:image/")) return@mapNotNull key to value
        runCatching {
            val bytes = Base64.getDecoder().decode(value.substringAfter("base64,"))
            require(bytes.size <= 4_000_000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val file = newFile(context, key)
            file.writeBytes(bytes)
            key to file.absolutePath
        }.getOrNull()
    }.toMap()

    fun launch(block: suspend CoroutineScope.() -> Unit) { scope.launch(block = block) }
}

@Composable
fun rememberCover(key: String): Any? {
    val context = LocalContext.current
    val store = remember { StudioStore(context) }
    var path by remember(key) { mutableStateOf(store.cover(key)) }
    DisposableEffect(key) {
        val stop = StudioEvents.listen { changed -> if (changed == "cover:$key") path = store.cover(key) }
        onDispose { stop() }
    }
    return path?.let(Covers::model)
}

@Composable
fun rememberCoverPicker(key: String, onMessage: (String) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val target by rememberUpdatedState(key)
    val notify by rememberUpdatedState(onMessage)
    var requestedKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(key) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val chosenKey = requestedKey
            val app = context.applicationContext
            Covers.launch {
                runCatching { withContext(Dispatchers.IO) { Covers.save(app, chosenKey, uri) } }
                    .onSuccess { notify("Image enregistrée.") }
                    .onFailure { val message = "Impossible d’utiliser cette image. Choisis une autre image."; notify(message); Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
            }
        }
    }
    return { requestedKey = target; picker.launch(arrayOf("image/*")) }
}

fun clearCover(context: Context, key: String) { Covers.launch { withContext(Dispatchers.IO) { Covers.clear(context.applicationContext, key) } } }

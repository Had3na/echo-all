package fr.nacre.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val thumbnails = object : LruCache<String, Bitmap>(24*1024*1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

@Composable
fun MediaThumbnail(item: LibraryItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val custom = rememberCover(item.uri)
    if (custom != null) { AsyncImage(custom, "Pochette personnalisée", modifier, contentScale = ContentScale.Crop); return }
    val bitmap by produceState<Bitmap?>(thumbnails.get(item.uri), item.uri) {
        if (value == null && item.kind != MediaKind.PHOTO && item.uri.startsWith("content:")) value = withContext(Dispatchers.IO) {
            runCatching {
                val image = if (item.kind == MediaKind.VIDEO && Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(Uri.parse(item.uri), Size(240,240), null)
                else run { val retriever = MediaMetadataRetriever(); try {
                    retriever.setDataSource(context, Uri.parse(item.uri))
                    if (item.kind == MediaKind.VIDEO) { if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240) else null }
                    else retriever.embeddedPicture?.let { bytes ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        val sample = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth,bounds.outHeight)/240).coerceAtLeast(1) }
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size,sample)
                    }
                } finally { retriever.release() } }
                image?.also { thumbnails.put(item.uri,it) }
            }.getOrNull()
        }
    }
    if (item.kind == MediaKind.PHOTO) AsyncImage(item.uri, null, modifier, contentScale = ContentScale.Crop)
    else if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Icon(if (item.kind == MediaKind.VIDEO) Icons.Rounded.PlayCircle else Icons.Rounded.MusicNote, null, tint = Lime)
}

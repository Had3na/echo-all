package fr.nacre.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

fun shareMedia(context: Context, item: LibraryItem) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND)
        if(item.uri.startsWith("https://")) { intent.type="text/plain";intent.putExtra(Intent.EXTRA_TEXT,item.uri) }
        else {
            val uri=Uri.parse(item.uri)
            intent.type=context.contentResolver.getType(uri) ?: "application/octet-stream"
            intent.putExtra(Intent.EXTRA_STREAM,uri);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData=android.content.ClipData.newRawUri(item.title,uri)
        }
        context.startActivity(Intent.createChooser(intent,"Partager ${item.title}"))
    }.onFailure { Toast.makeText(context,"Partage indisponible pour ce média.",Toast.LENGTH_SHORT).show() }
}

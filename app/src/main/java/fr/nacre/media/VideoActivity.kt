package fr.nacre.media

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoActivity : ComponentActivity() {
    private var player: MediaController? by mutableStateOf(null)
    private var pip by mutableStateOf(false)
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val connection = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = connection
        connection.addListener({ if (!isDestroyed) runCatching { player = connection.get() } }, ContextCompat.getMainExecutor(this))
        setContent { NacreTheme { val current = player; if (current != null) VideoSurface(current, pip) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } } }
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) { super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); pip = isInPictureInPictureMode }
    override fun onStop() { if (!isChangingConfigurations && !isInPictureInPictureMode) player?.pause(); super.onStop() }
    override fun onDestroy() { future?.let { MediaController.releaseFuture(it) }; super.onDestroy() }

    @Composable
    private fun VideoSurface(controller: MediaController, small: Boolean) {
        val (settings) = rememberPreferences()
        val opacity = remember { Animatable(1f) }
        var mediaId by remember { mutableStateOf(controller.currentMediaItem?.mediaId.orEmpty()) }
        LaunchedEffect(mediaId) { if (!settings.reduceMotion && settings.mixSeconds > 0) { opacity.snapTo(0f); opacity.animateTo(1f,tween(400)) } }
        var locked by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var view by remember { mutableStateOf<PlayerView?>(null) }
        var playing by remember { mutableStateOf(controller.isPlaying) }
        val scope = rememberCoroutineScope()
        var snapshot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            val bitmap = snapshot
            if (uri != null && bitmap != null) scope.launch {
                message = withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } ?: error("Destination inaccessible"); "Image enregistrée." }.getOrDefault("Capture non enregistrée.") }
                snapshot = null
            }
        }
        val subtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null,null,null)?.use { if (it.moveToFirst()) it.getString(0) else "" }.orEmpty()
                val mime = if (name.endsWith(".vtt",true)) "text/vtt" else "application/x-subrip"
                val item = controller.currentMediaItem ?: return@runCatching
                val position = controller.currentPosition; val shouldPlay = controller.playWhenReady
                val subtitle = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
                controller.replaceMediaItem(controller.currentMediaItemIndex,item.buildUpon().setSubtitleConfigurations(listOf(subtitle)).build())
                controller.seekTo(position); controller.prepare(); controller.playWhenReady = shouldPlay
                message = "Sous-titres ajoutés."
            }.onFailure { message = "Impossible de charger ces sous-titres." }
        }
        LaunchedEffect(controller) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while(true) { playing = controller.isPlaying; mediaId = controller.currentMediaItem?.mediaId.orEmpty(); delay(250) } } }
        LaunchedEffect(message) { if (message.isNotBlank()) { delay(4000); message = "" } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { context -> (LayoutInflater.from(context).inflate(R.layout.video_player,null) as PlayerView).apply {
                player = controller; setShowSubtitleButton(true)
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); if (!locked) { if (isControllerFullyVisible) hideController() else showController() }; return true }
                    override fun onDoubleTap(e: MotionEvent): Boolean { if (!locked) { if (e.x < width / 2) controller.seekBack() else controller.seekForward() }; return true }
                    override fun onScroll(start: MotionEvent?, end: MotionEvent, dx: Float, dy: Float): Boolean {
                        if (locked || start == null || kotlin.math.abs(dy) < kotlin.math.abs(dx)) return false
                        if (start.x < width*.22f) { val a = window.attributes; a.screenBrightness = ((a.screenBrightness.takeIf { it >= 0 } ?: .5f) + dy/height).coerceIn(.02f,1f); window.attributes = a; return true }
                        if (start.x > width*.78f) { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val old = audio.getStreamVolume(AudioManager.STREAM_MUSIC); if (kotlin.math.abs(dy)>3) audio.setStreamVolume(AudioManager.STREAM_MUSIC, (old + if (dy>0) 1 else -1).coerceIn(0,max),0); return true }
                        return false
                    }
                })
                setOnTouchListener { _, event -> detector.onTouchEvent(event) }
                view = this
            } }, update = { it.player = controller; it.useController = false; it.keepScreenOn = playing }, onReset = null, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value })
            if (!small && !locked) CinemaPlaybackControls(controller, controller.mediaMetadata.title?.toString().orEmpty(), { finish() }, tools = {
                Row(Modifier.widthIn(max = 240.dp).horizontalScroll(rememberScrollState())) {
                if (!locked) {
                    IconButton(onClick = { subtitles.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Subtitles,"Ajouter SRT ou VTT",tint=Color.White) }
                    IconButton(onClick = {
                        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build()) }.onFailure { message = "Fenêtre flottante indisponible." }
                        else message = "Fenêtre flottante non prise en charge."
                    }) { Icon(Icons.Rounded.PictureInPictureAlt,"Fenêtre flottante",tint=Color.White) }
                    IconButton(onClick = { controller.currentMediaItem?.mediaId?.let { StudioStore(this@VideoActivity).bookmark(it,controller.currentPosition) }; message="Signet ajouté." }) { Icon(Icons.Rounded.BookmarkAdd,"Ajouter un signet",tint=Color.White) }
                    IconButton(onClick = { snapshot=(view?.videoSurfaceView as? TextureView)?.bitmap; if(snapshot!=null) export.launch("Echo-All-capture.png") else message="Capture indisponible." }) { Icon(Icons.Rounded.CameraAlt,"Capturer une image",tint=Color.White) }
                }
                IconButton(onClick = { locked = !locked }) { Icon(if(locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if(locked) "Déverrouiller" else "Verrouiller les commandes",tint=Color.White) }
            }
            })
            if (!small && locked) IconButton(onClick = { locked = false }, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding()) { Icon(Icons.Rounded.Lock, "Déverrouiller", tint = Color.White) }
            if (message.isNotBlank() && !small) Text(message, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.8f)).padding(16.dp),color=Color.White)
        }
    }
}

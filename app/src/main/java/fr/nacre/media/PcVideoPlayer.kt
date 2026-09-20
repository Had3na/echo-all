package fr.nacre.media
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun PcVideoPlayer(client:PcTorrentClient,route:String,title:String,onClose:()->Unit){
 val context=LocalContext.current
 val activity=context as? android.app.Activity
 DisposableEffect(activity) {
  val previous=activity?.requestedOrientation
  activity?.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  onDispose { if(previous!=null)activity.requestedOrientation=previous }
 }
 val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
 val player=remember(client,route){ExoPlayer.Builder(context).setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT,true).setHandleAudioBecomingNoisy(true).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client.http).setDefaultRequestProperties(client.headers))).build().apply{setMediaItem(MediaItem.fromUri(client.address+route));prepare();playWhenReady=true}}
 DisposableEffect(player,lifecycle){
  val observer=androidx.lifecycle.LifecycleEventObserver { _, event -> if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP)player.pause() }
  lifecycle.addObserver(observer)
  onDispose{lifecycle.removeObserver(observer);player.release()}
 }
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black)){
 AndroidView(factory={PlayerView(it).apply{this.player=player;keepScreenOn=true;useController=false}},onRelease={it.player=null},modifier=Modifier.fillMaxSize())
 CinemaPlaybackControls(player,title,onClose)
 }}
}

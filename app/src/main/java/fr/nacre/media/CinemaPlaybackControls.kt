package fr.nacre.media
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

@Composable
internal fun CinemaPlaybackControls(player:Player,title:String,onClose:()->Unit,tools:@Composable ()->Unit={}) {
 var visible by remember {mutableStateOf(true)};var playing by remember {mutableStateOf(player.isPlaying)}
 var position by remember {mutableLongStateOf(0)};var duration by remember {mutableLongStateOf(0)}
 var seeking by remember {mutableStateOf<Float?>(null)};var interaction by remember {mutableIntStateOf(0)}
 var speed by remember {mutableFloatStateOf(player.playbackParameters.speed)}
 LaunchedEffect(player){while(true){playing=player.isPlaying;position=player.currentPosition.coerceAtLeast(0);duration=player.duration.coerceAtLeast(0);delay(400)}}
 LaunchedEffect(visible,playing,interaction,seeking){if(visible&&playing&&seeking==null){delay(4500);visible=false}}
 Box(Modifier.fillMaxSize().pointerInput(player){detectTapGestures(onTap={visible=!visible},onDoubleTap={point->player.seekTo((player.currentPosition+if(point.x<size.width/2)-10000 else 10000).coerceIn(0,player.duration.coerceAtLeast(0)));interaction++})}){
  if(visible){
   Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.8f),Color.Transparent))))
   Box(Modifier.fillMaxWidth().height(200.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.9f)))))
   Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onClose){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Fermer la vidéo",tint=Color.White)};Text(title,Modifier.weight(1f),color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium);tools()}
   Row(Modifier.align(Alignment.Center),horizontalArrangement=Arrangement.spacedBy(32.dp),verticalAlignment=Alignment.CenterVertically){
    IconButton(onClick={player.seekTo((player.currentPosition-10000).coerceAtLeast(0));interaction++},modifier=Modifier.size(60.dp)){Icon(Icons.Rounded.Replay10,"Reculer de 10 secondes",Modifier.size(36.dp),tint=Color.White)}
    IconButton(onClick={if(player.isPlaying)player.pause() else player.play();interaction++},modifier=Modifier.size(80.dp)){Icon(if(playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(playing)"Pause" else "Lecture",Modifier.size(64.dp),tint=Color.White)}
    IconButton(onClick={player.seekTo((player.currentPosition+10000).coerceAtMost(duration));interaction++},modifier=Modifier.size(60.dp)){Icon(Icons.Rounded.Forward10,"Avancer de 10 secondes",Modifier.size(36.dp),tint=Color.White)}
   }
   Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal=24.dp,vertical=12.dp)){
    Slider(value=seeking?:position.toFloat().coerceAtMost(duration.toFloat()),onValueChange={seeking=it},onValueChangeFinished={seeking?.let{player.seekTo(it.toLong())};seeking=null;interaction++},valueRange=0f..duration.toFloat().coerceAtLeast(1f),enabled=duration>0,colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=Color(0xFFE50914),inactiveTrackColor=Color.White.copy(alpha=.3f)))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(mediaClock(position),color=Color.White);Spacer(Modifier.weight(1f));TextButton(onClick={speed=when(speed){1f->1.25f;1.25f->1.5f;1.5f->2f;else->1f};player.setPlaybackSpeed(speed);interaction++}){Text("Vitesse ${speed}×",color=Color.White)};Text("−"+mediaClock((duration-position).coerceAtLeast(0)),color=Color.White)}
   }
  }
 }
}

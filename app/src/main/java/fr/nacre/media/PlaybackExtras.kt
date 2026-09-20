package fr.nacre.media

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController

@Composable
fun PlaybackExtras(player: Player, shuffle: Boolean, repeat: Int, speed: Float) {
    var djOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var marksOpen by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val store = remember { StudioStore(context) }
    if (djOpen && player is MediaController) DjScreen(player) { djOpen = false }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
            IconToggleButton(shuffle, { player.shuffleModeEnabled = it }) { Icon(Icons.Rounded.Shuffle,"Lecture aléatoire",tint=if(shuffle) Lime else Muted) }
            IconButton(onClick={player.repeatMode=(repeat+1)%3}) { Icon(if(repeat==Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,"Répétition : $repeat",tint=if(repeat==0) Muted else Lime) }
            IconButton(onClick={player.seekBack()},enabled=player.isCurrentMediaItemSeekable) { Icon(Icons.Rounded.Replay10,"Reculer de 10 secondes") }
            IconButton(onClick={player.seekForward()},enabled=player.isCurrentMediaItemSeekable) { Icon(Icons.Rounded.Forward10,"Avancer de 10 secondes") }
            Box { TextButton(onClick={speedMenu=true}) { Text("${speed}×") }
                DropdownMenu(speedMenu,{speedMenu=false}) { listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { value -> DropdownMenuItem(text={Text("${value}×")},onClick={player.setPlaybackSpeed(value);speedMenu=false}) } }
            }
        }
        FilledTonalButton(onClick={djOpen=true}) { Text("Ouvrir le studio DJ") }
        Row { TextButton(onClick={queueOpen=true;revision++}) { Text("File · ${player.mediaItemCount}") }; TextButton(onClick={marksOpen=true}) { Text("Signets") } }
    }
    if(queueOpen) QueueDialog(player) { queueOpen=false }
    if(marksOpen) AlertDialog(onDismissRequest={marksOpen=false},title={Text("Signets du média")},text={
        Column { TextButton(onClick={store.bookmark(player.currentMediaItem?.mediaId.orEmpty(),player.currentPosition);revision++}) { Text("Marquer cette position") }
            val marks=remember(revision,marksOpen){store.bookmarks(player.currentMediaItem?.mediaId.orEmpty())}
            LazyColumn(Modifier.heightIn(max=300.dp)) { items(marks.size) { index->TextButton(onClick={player.seekTo(marks[index]);marksOpen=false}){Text(formatPosition(marks[index]))} } }
        }
    },confirmButton={TextButton(onClick={marksOpen=false}){Text("Fermer")}})
}

/** The playback queue: jump to a track, reorder it, drop one, or save the lot as a playlist. */
@Composable
fun QueueDialog(player: Player, onDismiss: () -> Unit) {
    var queueName by remember { mutableStateOf("Ma playlist") }
    var revision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val store = remember { StudioStore(context) }
    val queue = remember(revision, player.currentMediaItemIndex) { List(player.mediaItemCount) { player.getMediaItemAt(it) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("File de lecture") }, text = {
        LazyColumn(Modifier.heightIn(max=440.dp)) {
            item { OutlinedTextField(queueName,{queueName=it},label={Text("Nom de playlist")});TextButton(onClick={store.saveList(store.newList(queueName,queue.map{it.mediaId}));onDismiss()}) { Text("Enregistrer la file") } }
            items(queue.size) { index -> val item=queue[index]
                Column {
                    TextButton(onClick={player.seekTo(index,0);player.play();onDismiss()},modifier=Modifier.fillMaxWidth()) { Text("${index+1}. ${item.mediaMetadata.title?.toString().orEmpty()}") }
                    Row {
                        IconButton(enabled=index>0,onClick={player.moveMediaItem(index,index-1);revision++}) { Icon(Icons.Rounded.ArrowUpward,"Monter") }
                        IconButton(enabled=index<queue.lastIndex,onClick={player.moveMediaItem(index,index+1);revision++}) { Icon(Icons.Rounded.ArrowDownward,"Descendre") }
                        IconButton(onClick={player.removeMediaItem(index);revision++}) { Icon(Icons.Rounded.Remove,"Retirer de la file") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
}

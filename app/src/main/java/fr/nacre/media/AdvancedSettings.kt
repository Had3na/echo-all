package fr.nacre.media

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun AdvancedSettings(vm: LibraryViewModel, library: List<LibraryItem>) {
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("settings",Context.MODE_PRIVATE)}
    val studio=remember{StudioStore(context)}
    var revision by remember { mutableIntStateOf(0) }
    var folders by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    DisposableEffect(prefs){val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->revision++};prefs.registerOnSharedPreferenceChangeListener(listener);onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}}
    val values=remember(revision){(0..4).map{prefs.getInt("eq$it",0)}}
    val excluded=remember(revision){prefs.getStringSet("excludedFolders",emptySet()).orEmpty()}
    val theme=remember(revision){prefs.getString("theme","dark")}
    val privateMode=remember(revision){prefs.getBoolean("private",false)}
    val reduced=remember(revision){prefs.getBoolean("reduceMotion",false)}
    val minimum=remember(revision){prefs.getInt("minAudioSeconds",0)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)scope.launch{message=runCatching{exportBackup(context,uri,library);"Sauvegarde enregistrée."}.getOrElse{"Échec de sauvegarde : ${it.message}"}}}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.restoreBackup(uri)}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        HorizontalDivider();Text("Apparence et confort",fontSize=20.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("dark" to "Sombre","light" to "Clair","system" to "Système").forEach{(key,label)->FilterChip(theme==key,{prefs.edit().putString("theme",key).apply()},{Text(label)})}}
        Row(verticalAlignment=Alignment.CenterVertically){Text("Réduire les animations",Modifier.weight(1f));Switch(reduced,{prefs.edit().putBoolean("reduceMotion",it).apply()})}
        HorizontalDivider();Text("Égaliseur",fontSize=20.sp)
        Text("Traitement intégré à Echo-All : même rendu sur tous les téléphones, casque et Bluetooth.",color=Muted)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            mapOf("Neutre" to listOf(0,0,0,0,0),"Basses" to listOf(5,3,0,-1,0),"Voix" to listOf(-2,0,3,2,-1),"Doux" to listOf(1,0,0,-2,-3)).forEach{(name,levels)->OutlinedButton(onClick={val edit=prefs.edit();levels.forEachIndexed{i,value->edit.putInt("eq$i",value)};edit.apply()}){Text(name)}}
        }
        listOf("60 Hz","250 Hz","1 kHz","4 kHz","16 kHz").forEachIndexed{i,label->Text("$label · ${values[i]} dB");Slider(values[i].toFloat(),{prefs.edit().putInt("eq$i",it.toInt()).apply()},valueRange=-12f..12f,steps=23)}
        Text("Réglage appliqué immédiatement, y compris pendant les transitions DJ.",fontSize=12.sp,color=Muted)
        HorizontalDivider();Text("Scan et confidentialité",fontSize=20.sp)
        OutlinedButton(onClick={folders=true}){Text("Dossiers ignorés · ${excluded.size}")}
        Text("Ignorer les sons de moins de $minimum secondes")
        Slider(minimum.toFloat(),{prefs.edit().putInt("minAudioSeconds",it.toInt()).apply()},valueRange=0f..120f,steps=23)
        TextButton(onClick={vm.scan()}){Text("Appliquer au scan maintenant")}
        Row(verticalAlignment=Alignment.CenterVertically){Text("Mode privé · ne pas ajouter à l’historique",Modifier.weight(1f));Switch(privateMode,{prefs.edit().putBoolean("private",it).apply()})}
        TextButton(onClick={studio.clearHistory();context.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().apply();message="Historique et anciennes reprises effacés."}){Text("Effacer l’historique et les reprises")}
        TextButton(onClick={prefs.edit().putBoolean("sleepEnd",true).apply();message="La lecture s’arrêtera à la fin du média."}){Text("Arrêter à la fin du média")}
        TextButton(onClick={prefs.edit().putBoolean("sleepEnd",false).apply();message="Arrêt en fin de média annulé."}){Text("Annuler l’arrêt en fin de média")}
        HorizontalDivider();Text("Sauvegarde",fontSize=20.sp)
        OutlinedButton(onClick={export.launch("Echo-All-sauvegarde.json")}){Text("Exporter réglages, favoris et collections")}
        OutlinedButton(onClick={restore.launch(arrayOf("application/json","text/plain"))}){Text("Restaurer une sauvegarde")}
        Text("La sauvegarde contient les références des médias, pas les fichiers. Sur un autre téléphone, les fichiers devront être réimportés. Les réglages et collections du même identifiant sont remplacés.",fontSize=12.sp,color=Muted)
        if(message.isNotBlank())Text(message,color=Lime)
    }
    if(folders)AlertDialog(onDismissRequest={folders=false},title={Text("Dossiers exclus du scan")},text={LazyColumn(Modifier.heightIn(max=360.dp)){
        items((library.map{it.folder}+excluded).filter{it.isNotBlank()}.distinct().sorted()){folder->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(folder in excluded,{checked->prefs.edit().putStringSet("excludedFolders",if(checked)excluded+folder else excluded-folder).apply()});Text(folder)}}
    }},confirmButton={TextButton(onClick={folders=false;vm.scan()}){Text("Appliquer")}})
}

package fr.nacre.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun AccountSettings() {
    var open by remember { mutableStateOf(false) }
    Button(onClick = { open = true }) { Text("Mon compte et mes amis") }
    if (open) Dialog(onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { AccountScreen { open = false } }
    }
}

@Composable
private fun AccountScreen(close: () -> Unit) {
    val context = LocalContext.current
    val account = remember { runCatching { SocialAccount(context) }.getOrNull() }
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(account?.auth?.currentUser) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var friend by remember { mutableStateOf("") }
    var chats by remember { mutableStateOf(emptyList<DocumentSnapshot>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(emptyList<DocumentSnapshot>()) }
    var text by remember { mutableStateOf("") }
    var media by remember { mutableStateOf<android.net.Uri?>(null) }
    var download by remember { mutableStateOf<String?>(null) }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.localizedMessage ?: "Opération impossible." }
            finally { busy = false }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { media = it }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = download
        if (uri != null && path != null && account != null) runAction {
            val bytes = account.storage.reference.child(path).getBytes(25L * 1024 * 1024).socialAwait()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Fichier inaccessible.") }
            message = "Fichier enregistré."
        }
    }
    DisposableEffect(account) {
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        account?.auth?.addAuthStateListener(listener)
        onDispose { account?.auth?.removeAuthStateListener(listener) }
    }
    DisposableEffect(user?.uid) {
        selected = null; chats = emptyList(); messages = emptyList(); media = null; text = ""
        val current = user
        val registration = if (current != null && account != null) {
            account.db.collection("users").document(current.uid).set(mapOf("name" to (current.displayName ?: "Echo").take(80)))
                .addOnFailureListener { message = it.localizedMessage ?: "Profil indisponible." }
            account.db.collection("chats").whereArrayContains("members", current.uid).addSnapshotListener { snapshot, error ->
                if (error != null) message = error.localizedMessage ?: "Connexion impossible."
                chats = snapshot?.documents.orEmpty()
                if (selected != null && chats.none { it.id == selected && it.getString("status") == "accepted" }) selected = null
            }
        } else null
        onDispose { registration?.remove() }
    }
    DisposableEffect(selected, user?.uid) {
        messages = emptyList()
        val registration = selected?.let { id ->
            account?.db?.collection("chats")?.document(id)?.collection("messages")
                ?.orderBy("createdAt", Query.Direction.DESCENDING)?.limit(100)?.addSnapshotListener { snapshot, error ->
                    if (error != null) message = error.localizedMessage ?: "Messages indisponibles."
                    messages = snapshot?.documents.orEmpty().reversed()
                }
        }
        onDispose { registration?.remove() }
    }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mon compte et mes amis", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = close) { Text("Fermer") }
            }
            if (message.isNotBlank()) Text(message)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (account == null) {
            item { Text("Les comptes ne sont pas encore activés. La configuration Firebase doit être ajoutée à cette version. Tes médias locaux restent disponibles.") }
        } else if (user == null) {
            item {
                Text("Connecte-toi avec le même compte Google sur ton téléphone et ton ordinateur.")
                Button(enabled = !busy, onClick = { runAction { account.signIn(context) } }) { Text("Continuer avec Google") }
            }
        } else {
            val current = user!!
            item {
                Text(current.displayName ?: "Mon compte")
                SelectionContainer { Text("Mon identifiant : ${current.uid}") }
                TextButton(enabled = !busy, onClick = { account.auth.signOut() }) { Text("Déconnecter cet appareil") }
                OutlinedTextField(friend, { friend = it.take(128) }, label = { Text("Identifiant de ton ami") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && friend.isNotBlank(), onClick = { runAction {
                    val peer = friend.trim()
                    require(peer != current.uid && !peer.contains('/')) { "Identifiant invalide." }
                    require(account.db.collection("users").document(peer).get().socialAwait().exists()) { "Compte introuvable." }
                    account.db.collection("chats").add(mapOf("members" to listOf(current.uid, peer),
                        "sender" to current.uid, "recipient" to peer, "status" to "pending")).socialAwait()
                    friend = ""; message = "Invitation envoyée."
                } }) { Text("Inviter") }
            }
            items(chats, key = { "chat-" + it.id }) { chat ->
                val state = chat.getString("status")
                val peer = (chat.get("members") as? List<*>)?.firstOrNull { it != current.uid }.toString()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(peer); Text(when(state) { "accepted" -> "Ami"; "blocked" -> "Bloqué"; else -> "Invitation en attente" })
                        Row {
                            if (state == "pending" && chat.getString("recipient") == current.uid)
                                TextButton(enabled = !busy, onClick = { runAction { chat.reference.update("status", "accepted").socialAwait() } }) { Text("Accepter") }
                            if (state == "accepted")
                                TextButton(onClick = { selected = chat.id; text = ""; media = null }) { Text("Conversation") }
                            if (state != "blocked")
                                TextButton(enabled = !busy, onClick = { runAction { chat.reference.update("status", "blocked").socialAwait() } }) { Text("Bloquer / refuser") }
                        }
                    }
                }
            }
            if (selected != null) {
                item { HorizontalDivider(); Text("Conversation", style = MaterialTheme.typography.titleLarge) }
                items(messages, key = { "message-" + it.id }) { entry ->
                    Column {
                        Text(if (entry.getString("sender") == current.uid) "Moi" else "Mon ami")
                        SelectionContainer { Text(entry.getString("text").orEmpty()) }
                        val file = entry.getString("file").orEmpty()
                        if (file.isNotBlank()) TextButton(enabled = !busy, onClick = {
                            download = file; save.launch(entry.getString("name")?.substringAfterLast('/')?.substringAfterLast('\\') ?: "media")
                        }) { Text("Enregistrer ${entry.getString("name").orEmpty()}") }
                    }
                }
                item {
                    OutlinedTextField(text, { text = it.take(4000) }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !busy, onClick = { pick.launch(arrayOf("image/*", "audio/*", "video/*")) }) { Text(if (media == null) "Joindre une photo, musique ou vidéo" else "Changer le fichier joint") }
                    if (media != null) TextButton(onClick = { media = null }) { Text("Retirer la pièce jointe") }
                    Button(enabled = !busy && (text.isNotBlank() || media != null), onClick = {
                        val chatId = selected ?: return@Button
                        val body = text.trim(); val attachment = media
                        runAction {
                            var file = ""; var name = ""
                            try {
                                if (attachment != null) {
                                    val mime = context.contentResolver.getType(attachment).orEmpty()
                                    require(mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")) { "Média invalide." }
                                    require(mime != "image/svg+xml") { "Format non pris en charge." }
                                    val bytes = withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(attachment)?.use { input ->
                                            val output = java.io.ByteArrayOutputStream()
                                            val buffer = ByteArray(8192)
                                            while (true) {
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                require(output.size() + count <= 25 * 1024 * 1024) { "25 Mo maximum." }
                                                output.write(buffer, 0, count)
                                            }
                                            output.toByteArray()
                                        } ?: error("Fichier inaccessible.")
                                    }
                                    require(bytes.isNotEmpty()) { "Fichier vide." }
                                    name = "media." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin")
                                    file = "chats/$chatId/${current.uid}/${UUID.randomUUID()}"
                                    account.storage.reference.child(file).putBytes(bytes,
                                        com.google.firebase.storage.StorageMetadata.Builder().setContentType(mime).build()).socialAwait()
                                }
                                account.db.collection("chats").document(chatId).collection("messages").add(mapOf(
                                    "sender" to current.uid, "text" to body, "file" to file, "name" to name,
                                    "createdAt" to FieldValue.serverTimestamp())).socialAwait()
                                if (selected == chatId) { text = ""; media = null }
                                message = "Message envoyé."
                            } catch (error: Exception) {
                                if (file.isNotBlank()) account.storage.reference.child(file).delete()
                                throw error
                            }
                        }
                    }) { Text("Envoyer") }
                }
            }
        }
    }
}

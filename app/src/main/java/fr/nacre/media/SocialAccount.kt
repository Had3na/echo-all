package fr.nacre.media

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.socialAwait(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

class SocialAccount(context: Context) {
    private val config = JSONObject(context.assets.open("google-services.json").bufferedReader().use { it.readText() })
    private val project = config.getJSONObject("project_info")
    private val client = (0 until config.getJSONArray("client").length())
        .map { config.getJSONArray("client").getJSONObject(it) }
        .first { it.getJSONObject("client_info").getJSONObject("android_client_info").getString("package_name") == context.packageName }
    private val app = FirebaseApp.getApps(context).firstOrNull { it.name == "echo-social" }
        ?: FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setApplicationId(client.getJSONObject("client_info").getString("mobilesdk_app_id"))
            .setApiKey(client.getJSONArray("api_key").getJSONObject(0).getString("current_key"))
            .setProjectId(project.getString("project_id"))
            .setStorageBucket(project.getString("storage_bucket")).build(), "echo-social")
    val auth = FirebaseAuth.getInstance(app)
    val db = FirebaseFirestore.getInstance(app)
    val storage = FirebaseStorage.getInstance(app)
    suspend fun signIn(context: Context) {
        val clients = client.getJSONArray("oauth_client")
        val web = (0 until clients.length()).map { clients.getJSONObject(it) }.first { it.getInt("client_type") == 3 }.getString("client_id")
        val option = GetGoogleIdOption.Builder().setServerClientId(web).setFilterByAuthorizedAccounts(false).build()
        val result = CredentialManager.create(context).getCredential(context,
            GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        require(result is CustomCredential && result.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        val token = GoogleIdTokenCredential.createFrom(result.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).socialAwait()
    }
}

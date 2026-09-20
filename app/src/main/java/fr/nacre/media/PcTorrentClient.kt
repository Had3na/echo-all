package fr.nacre.media
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.*

internal class PcTorrentClient private constructor(val address:String, private val token:String, val http:OkHttpClient) {
 val headers get()=mapOf("Authorization" to "Bearer $token")
 suspend fun call(route:String, body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO) {
  val request=Request.Builder().url(address+route).header("Authorization","Bearer $token")
  if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
  http.newCall(request.build()).execute().use { r ->
   val result=JSONObject(r.body?.string().orEmpty())
   check(r.isSuccessful){result.optString("error","Serveur indisponible")};result
  }
 }
 companion object {
  fun configured(context:Context)=context.getSharedPreferences("pc_torrents",0).contains("pairing")
  fun load(context:Context):PcTorrentClient?=context.getSharedPreferences("pc_torrents",0).getString("pairing",null)?.let { from(JSONObject(it)) }
  fun save(context:Context,text:String):PcTorrentClient {require(text.length<20000);val client=from(JSONObject(text));context.getSharedPreferences("pc_torrents",0).edit().putString("pairing",text).apply();return client}
  fun from(json:JSONObject):PcTorrentClient {
   val url=java.net.URI(json.getString("address"));require(url.scheme=="https" && NotesLanTransport.isPrivateIpv4(url.host.orEmpty()) && url.userInfo==null && (url.path.isNullOrEmpty()||url.path=="/")) {"Adresse HTTPS locale requise"}
   val token=json.getString("token");require(token.matches(Regex("[a-f0-9]{64}")))
   val cert=CertificateFactory.getInstance("X.509").generateCertificate(json.getString("certificate").byteInputStream())
   val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null);setCertificateEntry("pc",cert)}
   val managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply{init(store)}.trustManagers
   val trust=managers.filterIsInstance<X509TrustManager>().single()
   val tls=SSLContext.getInstance("TLS").apply{init(null,arrayOf(trust),null)}
   val http=OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,trust).connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(45,java.util.concurrent.TimeUnit.SECONDS).callTimeout(60,java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build()
   return PcTorrentClient(url.toString().trimEnd('/'),token,http)
  }
 }
}

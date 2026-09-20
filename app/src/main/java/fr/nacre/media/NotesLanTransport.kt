package fr.nacre.media

import java.net.InetSocketAddress
import java.net.Socket
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/** Only carries authenticated encrypted packets to the Echo-All companion, never arbitrary URLs. */
object NotesLanTransport {
    fun isPrivateIpv4(address: String): Boolean {
        if(!address.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return false
        val parts = address.split('.').map { it.toInt() }
        return parts.all { it in 0..255 } && (parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))
    }
    fun exchange(address: String, payload: String): JSONObject {
        require(isPrivateIpv4(address)) { "Adresse du réseau local requise" }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 48 * 1024 * 1024) { "Envoi trop volumineux" }
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(address, 4319), 6000)
            socket.soTimeout = 90000
            val output = socket.getOutputStream()
            output.write("POST /lan HTTP/1.1\r\nHost: $address:4319\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes); output.flush()
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val buffer = ByteArrayOutputStream()
                while(true) { val value = input.read(); require(value >= 0 && buffer.size() < 8192) { "Réponse locale invalide" }; if(value == 10) break; if(value != 13) buffer.write(value) }
                return buffer.toString("US-ASCII")
            }
            val statusLine = line().split(' ')
            require(statusLine.size >= 2 && statusLine[0].startsWith("HTTP/1."))
            val status = statusLine[1].toInt()
            var size = -1
            var headers = 0
            while(true) { val header = line(); if(header.isEmpty()) break; require(++headers < 100); if(header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt() }
            require(size in 0..(96 * 1024 * 1024)) { "Réponse locale trop volumineuse" }
            val data = ByteArray(size); var offset = 0
            while(offset < size) { val count = input.read(data, offset, size-offset); require(count > 0) { "Connexion interrompue" }; offset += count }
            JSONObject().put("status", status).put("body", String(data, Charsets.UTF_8))
        }
    }
}

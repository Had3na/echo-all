package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test

class NotesLanTransportTest {
    @Test fun acceptsOnlyPrivateIpv4Addresses() {
        listOf("192.168.1.18", "10.0.0.2", "172.16.0.1", "172.31.255.254").forEach { assertTrue(NotesLanTransport.isPrivateIpv4(it)) }
        listOf("127.0.0.1", "8.8.8.8", "172.32.0.1", "192.168.1.999", "example.com", "100.69.75.89", "::1", "192.168.0.1:80", "192.168.1.1/lan").forEach { assertFalse(NotesLanTransport.isPrivateIpv4(it)) }
    }
}

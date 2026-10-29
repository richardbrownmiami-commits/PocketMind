package com.pocketmind.remote

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class RemoteSessionClientTest {
    @Test fun secureUpgradeUsesHttpsAndFixedWebSocketPath() {
        val relay = requireNotNull(RemoteSessionClient.parseRelayUrl("https://relay.example:8443/"))
        val upgrade = RemoteSessionClient.webSocketUrl(relay)
        assertTrue(upgrade.isHttps)
        assertEquals("https://relay.example:8443/ws", upgrade.toString())
    }

    @Test fun rejectsNonHttpsAndNonRootRelayAddresses() {
        listOf(
            "http://relay.example", "wss://relay.example", "https://user:pass@relay.example",
            "https://relay.example/path", "https://relay.example?token=value", "https://relay.example#fragment",
            "https://relay.example:0", "https://relay.example:65536", "not a URL"
        ).forEach { assertNull(it, RemoteSessionClient.parseRelayUrl(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesCleartextWebSocketUpgrade() {
        RemoteSessionClient.webSocketUrl("http://relay.example/".toHttpUrl())
    }

    @Test fun validatesOnlyTenUnambiguousPairingCharacters() {
        assertTrue(RemoteSessionClient.validPairingCode("ABCD2EFGH3"))
        listOf("abcd2efgh3", "ABCD2-EFGH3", "ABCD2EFGH", "ABCD2EFGHI", "ABCD2EFGH0", "ABCD2EFGH1").forEach {
            assertFalse(it, RemoteSessionClient.validPairingCode(it))
        }
    }
}

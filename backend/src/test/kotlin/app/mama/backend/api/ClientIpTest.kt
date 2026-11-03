package app.mama.backend.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Client-IP resolution for rate limiting (ChatGPT review item 2): X-Forwarded-For
 * must never be trusted from an arbitrary peer, so a forged header cannot inflate
 * or dodge a per-IP quota.
 */
class ClientIpTest {

    @Test fun `forged XFF from an untrusted peer is ignored`() {
        // Attacker connects directly and sets any XFF they like.
        assertEquals("203.0.113.9", resolveClientIp(peerHost = "203.0.113.9", xff = "1.1.1.1", trustedProxies = emptySet()))
        assertEquals("203.0.113.9", resolveClientIp("203.0.113.9", "1.1.1.1, 2.2.2.2", emptySet()))
    }

    @Test fun `with no trusted proxies configured XFF is always ignored`() {
        assertEquals("10.0.0.5", resolveClientIp("10.0.0.5", "9.9.9.9", trustedProxies = emptySet()))
    }

    @Test fun `a trusted proxy's forwarded client is used`() {
        // The TLS proxy (trusted) appended the real client as the right-most hop.
        assertEquals("198.51.100.7", resolveClientIp("10.0.0.1", "198.51.100.7", setOf("10.0.0.1")))
        assertEquals("198.51.100.7", resolveClientIp("10.0.0.1", "spoofed, 198.51.100.7", setOf("10.0.0.1")))
    }

    @Test fun `right-most non-proxy hop is the client when proxies chain`() {
        // client -> edge(trusted) -> app-proxy(trusted, the peer). XFF: client, edge.
        assertEquals("198.51.100.7", resolveClientIp("10.0.0.2", "198.51.100.7, 10.0.0.1", setOf("10.0.0.1", "10.0.0.2")))
    }

    @Test fun `a trusted peer with no XFF falls back to the socket`() {
        assertEquals("10.0.0.1", resolveClientIp("10.0.0.1", xff = null, trustedProxies = setOf("10.0.0.1")))
        assertEquals("10.0.0.1", resolveClientIp("10.0.0.1", xff = "   ", trustedProxies = setOf("10.0.0.1")))
    }
}

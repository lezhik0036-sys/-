package app.mama.backend.api

/**
 * Resolves the client IP used for rate limiting. `X-Forwarded-For` is attacker-
 * controlled on any direct request, so it is honoured ONLY when the actual
 * socket peer ([peerHost]) is an explicitly configured trusted proxy
 * ([trustedProxies], from `MAMA_TRUSTED_PROXIES`); otherwise the socket peer is
 * used and the header is ignored. With no trusted proxies configured (the
 * test-only default) XFF is always ignored — the conservative, fail-closed
 * choice so a forged header cannot inflate or dodge a per-IP quota.
 *
 * When the peer is trusted, the real client is the right-most forwarded hop that
 * is not itself a trusted proxy (hops are appended left-to-right as the request
 * passes through proxies, so the entry added by the nearest trusted proxy is the
 * right-most one; walking from the right past trusted proxies lands on the first
 * untrusted address, which is the client — spoofed entries a client prepends sit
 * to its left and are skipped).
 */
internal fun resolveClientIp(peerHost: String, xff: String?, trustedProxies: Set<String>): String {
    if (peerHost in trustedProxies && xff != null) {
        val hops = xff.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        hops.lastOrNull { it !in trustedProxies }?.let { return it }
    }
    return peerHost
}

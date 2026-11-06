package app.mama.account

import android.content.Context

/**
 * App-wide access to the real MAMA server session. Holds the HTTPS account client
 * built from the configured backend URL + the Keystore-protected device key
 * ([KeystoreDeviceKeyProvider]) + the encrypted session store
 * ([SecureAccountSessionStore]).
 *
 * Fail-closed: [isConfigured] is false — and [ensureSession] returns
 * [MamaAccountClient.Result.Unavailable] — whenever no backend URL is configured
 * or the platform cannot hold the Ed25519 device key (API < 33). There is no
 * local fallback: without a real FastAPI-issued session the app simply has no
 * session.
 *
 * [configure] is light (no network, no Keystore) and safe to call from
 * [android.app.Application.onCreate]. [ensureSession]/[client] do network +
 * Keystore work and MUST be called off the main thread.
 */
object MamaAccount {
    @Volatile private var appContext: Context? = null
    @Volatile private var baseUrl: String = ""
    @Volatile private var cached: MamaAccountClient? = null

    fun configure(context: Context, backendBaseUrl: String) {
        appContext = context.applicationContext
        baseUrl = backendBaseUrl.trim()
        cached = null
    }

    /** True only when a backend URL is set AND the platform can hold the device key. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && KeystoreDeviceKeyProvider.isSupported()

    /**
     * The HTTPS account client, building it on first use (loads or creates the
     * Keystore-wrapped device key). Null when not configured or the device key is
     * unavailable (fail-closed). Call off the main thread.
     */
    @Synchronized
    fun client(): MamaAccountClient? {
        cached?.let { return it }
        val ctx = appContext ?: return null
        if (!isConfigured) return null
        val deviceKey = KeystoreDeviceKeyProvider.loadOrCreate(ctx) ?: return null
        return MamaAccountClient(
            UrlConnectionAccountTransport(baseUrl),
            deviceKey,
            SecureAccountSessionStore(ctx),
        ).also { cached = it }
    }

    /** Obtain a session (cached, else login, else register). Call OFF the main thread. */
    fun ensureSession(): MamaAccountClient.Result =
        client()?.ensureSession() ?: MamaAccountClient.Result.Unavailable

    /** Drop the cached bearer after an authenticated 401 so the next call re-authenticates. */
    fun forgetSession() {
        client()?.forgetSession()
    }
}

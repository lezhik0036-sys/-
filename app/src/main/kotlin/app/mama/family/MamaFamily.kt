package app.mama.family

import android.content.Context
import android.os.Build
import app.mama.account.AccountSession
import app.mama.account.KeystoreDeviceKeyProvider
import app.mama.account.MamaAccount
import app.mama.account.MamaAccountClient
import app.mama.account.UrlConnectionAccountTransport

/**
 * App-wide composition root for the REAL MAMA Family client, mirroring
 * [app.mama.trustedexit.MamaTrustedExit]. [configure] is light (no network, no
 * Keystore) and safe from Application.onCreate; the [FamilyClient] is built LAZILY
 * on first use (Keystore + network) and MUST run off the main thread.
 *
 * Fail-closed: with no backend URL, or on a platform that cannot hold the Ed25519
 * device key, [client] is null and the Family UI shows "unavailable" —
 * there is NO local pairing path and no fabricated link; all authority is the
 * server's. Uses the SAME account session + device key as the rest of the app, so a
 * no-SIM Wi-Fi device pairs identically (the whole credential is the device key).
 */
object MamaFamily {
    @Volatile private var appContext: Context? = null
    @Volatile private var baseUrl: String = ""
    @Volatile private var cached: FamilyClient? = null

    fun configure(context: Context, backendBaseUrl: String) {
        appContext = context.applicationContext
        baseUrl = backendBaseUrl.trim()
        cached = null
    }

    /** True only when a backend URL is set AND the platform can hold the device key. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && KeystoreDeviceKeyProvider.isSupported(Build.VERSION.SDK_INT)

    /**
     * The real HTTPS family client, built on first use. Null when not configured or
     * the device key is unavailable (fail-closed). Call OFF the main thread.
     */
    @Synchronized
    fun client(): FamilyClient? {
        cached?.let { return it }
        val ctx = appContext ?: return null
        if (!isConfigured) return null
        val deviceKey = KeystoreDeviceKeyProvider.loadOrCreate(ctx) ?: return null
        val sessionSource = sessionSource() ?: return null
        return FamilyClient(UrlConnectionAccountTransport(baseUrl), sessionSource, deviceKey).also { cached = it }
    }

    /** Session source for the family client: the account client's bearer, or null. */
    private fun sessionSource(): FamilySessionSource? {
        val accountClient = MamaAccount.client() ?: return null
        return object : FamilySessionSource {
            override fun ensureSession(): AccountSession? =
                (accountClient.ensureSession() as? MamaAccountClient.Result.Ok)?.session
            override fun forgetSession() {
                // Route 401 invalidation through the central account path, never
                // clear only this client, so the rest of the app re-auths too.
                MamaAccount.forgetSession()
            }
        }
    }
}

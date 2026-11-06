package app.mama.trustedexit

import android.content.Context
import android.os.Build
import app.mama.account.AccountSession
import app.mama.account.KeystoreDeviceKeyProvider
import app.mama.account.MamaAccount
import app.mama.account.MamaAccountClient
import app.mama.account.UrlConnectionAccountTransport

/**
 * App-wide composition root for the REAL, server-authoritative Trusted Exit
 * client. Builds [HttpTrustedExitBackend] from the configured backend URL, the
 * Keystore-protected device key, the account session (via [MamaAccount]), the
 * persisted provisioned [ExitContext] and the durable [ExitRequestKeyStore].
 *
 * [configure] is light (no network, no Keystore) and safe from
 * [android.app.Application.onCreate]. The backend is built LAZILY on first use
 * ([backend]/[deferredBackend]), which does Keystore + network work and MUST run
 * off the main thread — exactly where the Trusted Exit flow already calls it.
 *
 * Fail-closed: with no backend URL, or on a platform that cannot hold the device
 * key (API < 33), [backend] is null and [deferredBackend] refuses every call.
 * There is NO local OTP path here; the only exit is the server's.
 */
object MamaTrustedExit {
    @Volatile private var appContext: Context? = null
    @Volatile private var baseUrl: String = ""
    @Volatile private var cached: HttpTrustedExitBackend? = null

    fun configure(context: Context, backendBaseUrl: String) {
        appContext = context.applicationContext
        baseUrl = backendBaseUrl.trim()
        cached = null
    }

    /** True only when a backend URL is set AND the platform can hold the device key. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && KeystoreDeviceKeyProvider.isSupported(Build.VERSION.SDK_INT)

    /**
     * The real HTTPS backend, built on first use. Null when not configured or the
     * device key is unavailable (fail-closed). Call OFF the main thread.
     */
    @Synchronized
    fun backend(): HttpTrustedExitBackend? {
        cached?.let { return it }
        val ctx = appContext ?: return null
        if (!isConfigured) return null
        val deviceKey = KeystoreDeviceKeyProvider.loadOrCreate(ctx) ?: return null
        val accountClient = MamaAccount.client() ?: return null
        val sessionSource = object : TrustedExitSessionSource {
            override fun ensureSession(): AccountSession? =
                (accountClient.ensureSession() as? MamaAccountClient.Result.Ok)?.session
            override fun forgetSession() {
                accountClient.forgetSession()
            }
        }
        return HttpTrustedExitBackend(
            transport = UrlConnectionAccountTransport(baseUrl),
            deviceKey = deviceKey,
            session = sessionSource,
            context = FileExitContextStore(ctx),
            requestKeys = SecureExitRequestKeyStore(ctx),
        ).also { cached = it }
    }

    /**
     * A backend safe to assign eagerly (e.g. in onCreate): it resolves the real
     * HTTPS backend lazily on first call, which runs off the main thread in the
     * Trusted Exit flow. Fail-closed until configured.
     */
    fun deferredBackend(): TrustedExitBackend = DeferredTrustedExitBackend(::backend)

    /**
     * The server's own readiness for the provisioned series — the authoritative
     * BRICK preflight. Null = unknown/unconfigured (fail-closed). Call off the
     * main thread. See [HttpTrustedExitBackend.serverReadiness].
     */
    fun serverReadiness(): ServerReadiness? = backend()?.serverReadiness()

    /** The persisted provisioned-context store, for the setup/provisioning step to write. */
    fun contextStore(context: Context): FileExitContextStore = FileExitContextStore(context)
}

/**
 * Wraps a lazily-built [TrustedExitBackend] so it can be assigned on the main
 * thread while the real backend (Keystore + network) is constructed on first use,
 * off the main thread. A null provider result is fail-closed: create reports
 * not-configured, verify reports Unknown — never a local accept.
 */
class DeferredTrustedExitBackend(
    private val provider: () -> TrustedExitBackend?,
) : TrustedExitBackend {
    override fun createChallenge(request: CreateChallengeRequest): CreateChallengeResult =
        provider()?.createChallenge(request)
            ?: CreateChallengeResult.Failed("trusted-exit-not-configured")

    override fun verify(challengeId: String, enteredCode: String): VerifyResult =
        provider()?.verify(challengeId, enteredCode) ?: VerifyResult.Unknown
}

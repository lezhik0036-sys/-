package app.mama.account

import android.content.Context
import java.io.File
import java.util.Base64
import org.json.JSONObject

/**
 * Persists the server session bearer at rest, wrapped by a hardware-backed
 * AndroidKeyStore AES key ([AndroidKeystoreAesKey]). App-private, per-device,
 * never shared. Android-only (not host-tested); the wrap/unwrap it relies on is
 * tested separately. Fail-closed: any read error returns null so the caller
 * re-authenticates; [clear] drops the bearer after an authenticated 401.
 */
class SecureAccountSessionStore(private val context: Context) : AccountSessionStore {

    private val file = File(context.filesDir, SESSION_FILE)

    override fun load(): AccountSession? = runCatching {
        if (!file.exists()) return null
        val aesKey = AndroidKeystoreAesKey.getOrCreate(AES_ALIAS)
        val plain = AesGcmWrap.unwrap(aesKey, Base64.getDecoder().decode(file.readText().trim()))
        val json = JSONObject(String(plain, Charsets.UTF_8))
        AccountSession(
            userId = json.getString("user_id"),
            deviceId = json.getString("device_id"),
            token = json.getString("token"),
        )
    }.getOrNull()

    override fun save(session: AccountSession) {
        // DO NOT swallow Keystore/IO failures: MamaAccountClient must not claim
        // durable authentication if the encrypted bearer was never persisted.
        // Callers map errors to Unavailable (fail closed).
        val aesKey = AndroidKeystoreAesKey.getOrCreate(AES_ALIAS)
        val plain = JSONObject()
            .put("user_id", session.userId)
            .put("device_id", session.deviceId)
            .put("token", session.token)
            .toString().toByteArray(Charsets.UTF_8)
        val blob = Base64.getEncoder().encodeToString(AesGcmWrap.wrap(aesKey, plain))
        val tmp = File(context.filesDir, "$SESSION_FILE.tmp")
        try {
            tmp.writeText(blob)
            if (!tmp.renameTo(file)) {
                // Never silently fall back to an unverified direct overwrite of
                // the current encrypted bearer. Keep existing session unchanged.
                throw java.io.IOException("atomic session persistence failed")
            }
        } finally {
            tmp.delete()
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val AES_ALIAS = "mama.account.session.aeswrap.v1"
        const val SESSION_FILE = "mama_account_session.v1"
    }
}

package app.mama.trustedexit

import android.content.Context
import app.mama.account.AesGcmWrap
import app.mama.account.AndroidKeystoreAesKey
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * Durable [ExitRequestKeyStore] for the real HTTPS Trusted Exit flow. Persists,
 * at rest and wrapped by a hardware-backed AndroidKeyStore AES key (the same
 * [AesGcmWrap]/[AndroidKeystoreAesKey] primitives the account session store uses),
 * the mapping from the caller's idempotency key to the server idempotency UUID
 * and, once known, the server request id.
 *
 * Why it must survive a restart: the idempotency UUID is written BEFORE the
 * network call, so if the app is killed (or the connection drops) right after the
 * server created the request and sent the SMS, the next run recovers the SAME
 * request instead of starting a new one — the server short-circuits on the key,
 * so the trusted contact is never sent a second code (issue #47).
 *
 * Android-only (not host-tested); the wrap/unwrap it relies on is tested
 * separately, and the pure store logic is covered against the in-memory store.
 * Missing file is first-use only; corrupt/unreadable existing state fails closed
 * so no fresh idempotency key can trigger an unintended duplicate SMS.
 * Writes are atomic-or-error, never an unverified direct overwrite.
 */
class SecureExitRequestKeyStore(private val context: Context) : ExitRequestKeyStore {

    private val file = File(context.filesDir, STORE_FILE)

    @Synchronized
    override fun loadOrCreate(idempotencyKey: String): ExitRequestRecord {
        val map = read()
        map[idempotencyKey]?.let { return it }
        val fresh = ExitRequestRecord(idempotencyKey, UUID.randomUUID().toString(), null)
        map[idempotencyKey] = fresh
        write(map)
        return fresh
    }

    @Synchronized
    override fun recordRequestId(idempotencyKey: String, requestId: String) {
        val map = read()
        // Never mint a new idempotency UUID while recording a server reply:
        // if the original row is gone, it could have dispatched SMS already.
        val rec = map[idempotencyKey]
            ?: throw IOException("trusted exit request correlation was lost")
        map[idempotencyKey] = rec.copy(requestId = requestId)
        write(map)
    }

    private fun read(): MutableMap<String, ExitRequestRecord> {
        val out = HashMap<String, ExitRequestRecord>()
        if (!file.exists()) return out  // first-use only
        // A corrupt/unreadable existing file is NOT first-use. Fail closed:
        // resetting the map would mint a new UUID and risk a duplicate SMS.
        try {
            val aesKey = AndroidKeystoreAesKey.getOrCreate(AES_ALIAS)
            val plain = AesGcmWrap.unwrap(aesKey, Base64.getDecoder().decode(file.readText().trim()))
            val root = JSONObject(String(plain, Charsets.UTF_8))
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val o = root.getJSONObject(key)
                val uuid = UUID.fromString(o.getString("uuid")).toString()
                val requestId = if (o.isNull("request_id")) null
                    else UUID.fromString(o.getString("request_id")).toString()
                out[key] = ExitRequestRecord(
                    idempotencyKey = key, idempotencyUuid = uuid, requestId = requestId,
                )
            }
        } catch (_: Exception) {
            throw IOException("trusted exit correlation store unavailable")
        }
        return out
    }

    private fun write(map: Map<String, ExitRequestRecord>) {
        val root = JSONObject()
        for ((key, rec) in map) {
            val o = JSONObject().put("uuid", rec.idempotencyUuid)
            if (rec.requestId != null) o.put("request_id", rec.requestId)
            root.put(key, o)
        }
        val aesKey = AndroidKeystoreAesKey.getOrCreate(AES_ALIAS)
        val blob = Base64.getEncoder()
            .encodeToString(AesGcmWrap.wrap(aesKey, root.toString().toByteArray(Charsets.UTF_8)))
        val tmp = File(context.filesDir, "$STORE_FILE.tmp")
        try {
            tmp.writeText(blob)
            // A failed rename must NOT overwrite the only durable SMS
            // correlation map in place (which could truncate on power loss).
            if (!tmp.renameTo(file)) throw IOException("atomic correlation write failed")
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val AES_ALIAS = "mama.trustedexit.reqkeys.aeswrap.v1"
        const val STORE_FILE = "mama_trusted_exit_request_keys.v1"
    }
}

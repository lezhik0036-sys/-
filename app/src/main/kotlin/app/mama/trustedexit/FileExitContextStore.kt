package app.mama.trustedexit

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * The current server-owned Trusted Exit context (series id + registered period id)
 * this device should request an early exit for, persisted across restarts.
 *
 * It is set by the setup/provisioning step (after the server activates the series
 * and registers the lock period via `/v1/setup/*`) and read by
 * [HttpTrustedExitBackend]. The ids are server UUIDs, not secrets, so a plain
 * app-private file is sufficient; the security of the exit rests on the session
 * bearer and the device-key proof, not on hiding these ids.
 *
 * Fail-closed: [current] returns null when nothing is provisioned (or on any read
 * error), so the backend reports "not provisioned" rather than guessing a series.
 */
class FileExitContextStore(private val context: Context) : ExitContextSource {

    private val file = File(context.filesDir, STORE_FILE)

    override fun current(): ExitContext? = runCatching {
        if (!file.exists()) return null
        val j = JSONObject(file.readText())
        val series = j.optString("series_id", "")
        val period = j.optString("period_id", "")
        if (series.isBlank() || period.isBlank()) null
        else ExitContext(seriesId = series, periodId = period)
    }.getOrNull()

    /** Record the provisioned series + period. Overwrites any previous context. */
    fun set(ctx: ExitContext) {
        val blob = JSONObject()
            .put("series_id", ctx.seriesId)
            .put("period_id", ctx.periodId)
            .toString()
        val tmp = File(context.filesDir, "$STORE_FILE.tmp")
        tmp.writeText(blob)
        if (!tmp.renameTo(file)) {
            file.writeText(blob)
            tmp.delete()
        }
    }

    /** Forget the provisioned context (e.g. the series ended or was reconfigured). */
    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val STORE_FILE = "mama_trusted_exit_context.v1"
    }
}

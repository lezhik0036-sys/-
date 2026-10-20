package app.mama.platform

import android.content.Context
import app.mama.core.FlexPackage
import app.mama.core.HistoryCodec
import app.mama.core.HistoryRecord
import app.mama.core.Series
import app.mama.core.SeriesHistory
import java.time.Instant

/**
 * SeriesHistory: every finished STANDARD series and FLEX package, newest
 * first, for the statistics screen. A journal of results only: written
 * right after the core has settled a result, never read by the core.
 * Device-protected storage like the lock state, so a result settled before
 * the first unlock after a reboot is kept as well. No limit on the number
 * of records.
 */
object History {
    private const val RECORDS = "records"

    /** Entries written before [HistoryRecord] (at most 100 each): read and converted, never rewritten. */
    private const val LEGACY_SERIES = "series"
    private const val LEGACY_FLEX = "flex"

    private fun prefs(context: Context) = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("mama_history", Context.MODE_PRIVATE)

    /** A series that ended COMPLETED or BROKEN; any other series is ignored. */
    fun record(context: Context, series: Series, now: Instant) {
        SeriesHistory.of(series, now)?.let { add(context, it) }
    }

    /** A FLEX package that is over; a running one is ignored. */
    fun recordFlex(context: Context, pkg: FlexPackage, now: Instant) {
        SeriesHistory.of(pkg, now)?.let { add(context, it) }
    }

    /** The whole history, older entries included, each result once, newest first. */
    fun records(context: Context): List<HistoryRecord> {
        val p = prefs(context)
        return HistoryCodec.read(
            p.getString(RECORDS, null).orEmpty(), p.getString(LEGACY_SERIES, null).orEmpty(), p.getString(LEGACY_FLEX, null).orEmpty(),
        )
    }

    @Synchronized
    private fun add(context: Context, record: HistoryRecord) {
        if (records(context).any { it.sameResultAs(record) }) return
        val p = prefs(context)
        val stored = p.getString(RECORDS, null).orEmpty()
        val own = HistoryCodec.read(stored, "", "")
        // Lines this version cannot read (say, written by a newer one) are kept as they are.
        val unknown = stored.lines().filter { it.isNotBlank() && HistoryCodec.decode(it) == null }
        val all = (listOf(HistoryCodec.write(SeriesHistory.append(own, record))) + unknown).joinToString("\n")
        // Synchronous, so the record is on disk before the state that settled it is saved.
        p.edit().putString(RECORDS, all).commit()
    }
}

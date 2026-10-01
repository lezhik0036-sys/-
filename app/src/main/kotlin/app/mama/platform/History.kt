package app.mama.platform

import android.content.Context
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import java.time.Instant

/** Finished series, newest first, for the statistics screen. */
object History {

    data class Entry(val endedAt: Instant, val kind: SeriesKind, val periods: Int, val status: SeriesStatus)

    private const val KEY = "series"
    private const val MAX = 100

    private fun prefs(context: Context) = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences("mama_history", Context.MODE_PRIVATE)

    fun record(context: Context, series: Series) {
        val line = listOf(
            (series.endedAt ?: Instant.now()).toEpochMilli(), series.kind.name, series.completedPeriods, series.status.name,
        ).joinToString("|")
        val old = prefs(context).getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }
        prefs(context).edit().putString(KEY, (listOf(line) + old).take(MAX).joinToString("\n")).apply()
    }

    fun entries(context: Context): List<Entry> =
        prefs(context).getString(KEY, "").orEmpty().lines().mapNotNull { line ->
            val p = line.split("|")
            runCatching {
                Entry(Instant.ofEpochMilli(p[0].toLong()), SeriesKind.valueOf(p[1]), p[2].toInt(), SeriesStatus.valueOf(p[3]))
            }.getOrNull()
        }
}

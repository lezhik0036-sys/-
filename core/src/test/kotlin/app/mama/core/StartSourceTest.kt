package app.mama.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StandardRules.startSource]: the one rule every start entry point uses to
 * pick where a standard series starts from. Priority — a free start when the
 * 30-day wait is over or there was no срыв; otherwise a same-length copilka
 * credit; otherwise a paid Restart of that length.
 */
class StartSourceTest {
    private val now: Instant = t("2026-10-22T10:00:00Z")
    private fun failed(daysAgo: Long) = Entitlements(standardFailedAt = now.minus(Duration.ofDays(daysAgo)))

    @Test
    fun `no срыв - free`() {
        assertEquals(StartSource.Free, StandardRules.startSource(Entitlements(), SeriesKind.THREE, now))
    }

    @Test
    fun `wait over - free, and a credit is not spent when the start is already free`() {
        val e = failed(40).copy(standardCredits = mapOf(SeriesKind.THREE to 1))
        assertEquals(StartSource.Free, StandardRules.startSource(e, SeriesKind.THREE, now))
    }

    @Test
    fun `cooldown with a matching credit - from the copilka, never paid`() {
        val e = failed(3).copy(standardCredits = mapOf(SeriesKind.THREE to 1))
        assertEquals(StartSource.FromCredit, StandardRules.startSource(e, SeriesKind.THREE, now))
    }

    @Test
    fun `cooldown, credit of another length - paid Restart at this length's price`() {
        val withThree = failed(3).copy(standardCredits = mapOf(SeriesKind.THREE to 1))
        assertEquals(StartSource.PaidRestart(179), StandardRules.startSource(withThree, SeriesKind.FIVE, now))
        assertEquals(StartSource.PaidRestart(299), StandardRules.startSource(withThree, SeriesKind.SEVEN, now))
    }

    @Test
    fun `cooldown, no credit - paid Restart 99 for three days`() {
        assertEquals(StartSource.PaidRestart(99), StandardRules.startSource(failed(3), SeriesKind.THREE, now))
    }
}

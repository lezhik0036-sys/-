package app.mama.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StandardRules.startSource]: the one money-only rule every start entry point
 * uses. The MAMA balance and the free FLEX entitlement are separate, and a
 * paid Restart never grants a free credit. Matrix: the 30-day wait over → a
 * free start; the wait on and the balance covers the length's price → a paid
 * Restart; the wait on and the balance is short → a paid Restart not yet
 * affordable.
 */
class StartSourceTest {
    private val now: Instant = t("2026-10-22T10:00:00Z")
    private fun failed(daysAgo: Long) = Entitlements(standardFailedAt = now.minus(Duration.ofDays(daysAgo)))

    @Test
    fun `no срыв - free`() {
        assertEquals(StartSource.Free, StandardRules.startSource(Entitlements(), SeriesKind.THREE, now, 0))
    }

    @Test
    fun `wait over - free regardless of balance`() {
        assertEquals(StartSource.Free, StandardRules.startSource(failed(40), SeriesKind.THREE, now, 0))
    }

    @Test
    fun `cooldown with enough balance - paid Restart at this length's price`() {
        assertEquals(StartSource.PaidRestart(99), StandardRules.startSource(failed(3), SeriesKind.THREE, now, 99_00))
        assertEquals(StartSource.PaidRestart(179), StandardRules.startSource(failed(3), SeriesKind.FIVE, now, 44_000_00))
        assertEquals(StartSource.PaidRestart(299), StandardRules.startSource(failed(3), SeriesKind.SEVEN, now, 299_00))
    }

    @Test
    fun `cooldown with insufficient balance - paid Restart the user cannot afford yet`() {
        assertEquals(
            StartSource.PaidRestartInsufficientFunds(99, 98_99),
            StandardRules.startSource(failed(3), SeriesKind.THREE, now, 98_99),
        )
        assertEquals(
            StartSource.PaidRestartInsufficientFunds(299, 0),
            StandardRules.startSource(failed(3), SeriesKind.SEVEN, now, 0),
        )
    }
}

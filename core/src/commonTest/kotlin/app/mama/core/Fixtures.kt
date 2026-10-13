package app.mama.core

import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.TimeZone

internal val MSK: TimeZone = TimeZone.of("Europe/Moscow")
internal val BERLIN: TimeZone = TimeZone.of("Europe/Berlin")

internal fun t(iso: String): Instant = Instant.parse(iso)

internal fun Instant.plusSeconds(s: Long): Instant = this + s.seconds
internal fun Instant.minusSeconds(s: Long): Instant = this - s.seconds
internal fun Instant.minusMillis(ms: Long): Instant = this - ms.milliseconds

internal val MOM = TrustedContact("Мама", "+79001234567")

/** Deterministic codes: 11111, 22222, ... */
internal class FakeCodes : CodeSource {
    private var n = 0
    override fun nextCode(length: Int): String { n++; return (n % 10).toString().repeat(length) }
    override fun nextSalt(): String = "salt$n"
}

internal fun engine() = LockEngine(CorePolicy(), FakeCodes())

internal fun LockEngine.created(plan: LockPlan, now: Instant): Session =
    (create("s1", plan, MOM, now) as LockEngine.CreateResult.Created).session

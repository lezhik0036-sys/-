package app.mama.core

import java.time.Instant
import java.time.ZoneId

internal val MSK: ZoneId = ZoneId.of("Europe/Moscow")
internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

internal fun t(iso: String): Instant = Instant.parse(iso)

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

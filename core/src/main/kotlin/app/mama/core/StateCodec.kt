package app.mama.core

import java.time.Instant
import java.time.ZoneId

/** Everything MAMA must persist to survive a reboot or a process kill. */
data class Snapshot(
    val session: Session? = null,
    val anchor: ClockAnchor? = null,
    val lastTrusted: Instant? = null,
)

/**
 * Flat string map encoding, so the platform layer can store it in any
 * key-value store (Android: device-protected SharedPreferences, readable
 * before the user unlocks the phone after a reboot).
 */
object StateCodec {
    const val VERSION = 1

    fun encode(snapshot: Snapshot): Map<String, String> = buildMap {
        put("v", VERSION.toString())
        snapshot.lastTrusted?.let { put("clock.lastTrusted", it.toString()) }
        snapshot.anchor?.let {
            put("clock.anchor.wall", it.wall.toString())
            put("clock.anchor.elapsed", it.elapsedMs.toString())
            put("clock.anchor.boot", it.bootId)
        }
        val s = snapshot.session ?: return@buildMap
        put("s.id", s.id)
        put("s.start", s.plan.start.toString())
        put("s.end", s.plan.end.toString())
        put("s.zone", s.plan.zone.id)
        put("s.mode", s.plan.mode.name)
        put("s.contact.name", s.contact.name)
        put("s.contact.phone", s.contact.phone)
        put("s.status", s.status.name)
        s.finishReason?.let { put("s.finishReason", it.name) }
        s.finishedAt?.let { put("s.finishedAt", it.toString()) }
        s.emergencyUntil?.let { put("s.emergencyUntil", it.toString()) }
        put("s.emergencyUsed", s.emergencyPassesUsed.toString())
        s.codeCooldownUntil?.let { put("s.cooldownUntil", it.toString()) }
        s.challenge?.let {
            put("s.ch.kind", it.kind.name)
            put("s.ch.salt", it.salt)
            put("s.ch.hash", it.hash)
            put("s.ch.issued", it.issuedAt.toString())
            put("s.ch.expires", it.expiresAt.toString())
            put("s.ch.attempts", it.attemptsLeft.toString())
        }
    }

    /**
     * Decodes a snapshot. Corrupt session data throws rather than silently
     * returning "no session", so the platform layer sees and handles it.
     */
    fun decode(map: Map<String, String>): Snapshot {
        if (map.isEmpty()) return Snapshot()
        val anchor = map["clock.anchor.wall"]?.let {
            ClockAnchor(
                wall = Instant.parse(it),
                elapsedMs = map.req("clock.anchor.elapsed").toLong(),
                bootId = map.req("clock.anchor.boot"),
            )
        }
        val lastTrusted = map["clock.lastTrusted"]?.let(Instant::parse)
        val session = map["s.id"]?.let { id ->
            Session(
                id = id,
                plan = LockPlan(
                    start = Instant.parse(map.req("s.start")),
                    end = Instant.parse(map.req("s.end")),
                    zone = ZoneId.of(map.req("s.zone")),
                    mode = SessionMode.valueOf(map.req("s.mode")),
                ),
                contact = TrustedContact(map.req("s.contact.name"), map.req("s.contact.phone")),
                status = SessionStatus.valueOf(map.req("s.status")),
                finishReason = map["s.finishReason"]?.let(FinishReason::valueOf),
                finishedAt = map["s.finishedAt"]?.let(Instant::parse),
                emergencyUntil = map["s.emergencyUntil"]?.let(Instant::parse),
                emergencyPassesUsed = map["s.emergencyUsed"]?.toInt() ?: 0,
                codeCooldownUntil = map["s.cooldownUntil"]?.let(Instant::parse),
                challenge = map["s.ch.kind"]?.let { kind ->
                    CodeChallenge(
                        kind = ExitKind.valueOf(kind),
                        salt = map.req("s.ch.salt"),
                        hash = map.req("s.ch.hash"),
                        issuedAt = Instant.parse(map.req("s.ch.issued")),
                        expiresAt = Instant.parse(map.req("s.ch.expires")),
                        attemptsLeft = map.req("s.ch.attempts").toInt(),
                    )
                },
            )
        }
        return Snapshot(session, anchor, lastTrusted)
    }

    private fun Map<String, String>.req(key: String): String =
        this[key] ?: throw IllegalStateException("MAMA state is missing '$key'")
}

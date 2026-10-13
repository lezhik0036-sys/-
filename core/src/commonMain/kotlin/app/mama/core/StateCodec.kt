package app.mama.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Everything MAMA must persist to survive a reboot or a process kill. */
data class Snapshot(
    val session: Session? = null,
    val anchor: ClockAnchor? = null,
    val lastTrusted: Instant? = null,
    val series: Series? = null,
    val entitlements: Entitlements = Entitlements(),
    val wallet: Wallet = Wallet(),
    /** The failed series a pending Restart replaced; put back if that Restart is cancelled before ACTIVE. */
    val restartedFrom: Series? = null,
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
        snapshot.series?.let {
            putSeries("sr.", it)
            // Marks state written with the SCHEDULED/ACTIVATING/ACTIVE model (see upgradeLegacy).
            put("sr.model", "2")
        }
        snapshot.restartedFrom?.let { putSeries("rf.", it) }
        val e = snapshot.entitlements
        e.standardFailedAt?.let { put("en.stdFailedAt", it.toString()) }
        put("en.freeFlex", e.freeFlexCredits.toString())
        e.flex?.let {
            put("fx.id", it.id)
            put("fx.activatedAt", it.activatedAt.toString())
            put("fx.source", it.source.name)
            put("fx.used", it.used.toString())
            put("fx.successful", it.successful.toString())
            it.currentSessionId?.let { v -> put("fx.current", v) }
            it.consumedSessionId?.let { v -> put("fx.consumed", v) }
            it.settledSessionId?.let { v -> put("fx.settled", v) }
            it.endedAt?.let { v -> put("fx.endedAt", v.toString()) }
            it.lastBurntAt?.let { v -> put("fx.burntAt", v.toString()) }
        }
        val w = snapshot.wallet
        put("wl.available", w.availableKop.toString())
        put("wl.reserved", w.reservedKop.toString())
        w.entries.forEachIndexed { i, e ->
            put(
                "wl.e.$i",
                listOf(
                    e.id, e.at.toString(), e.amountKop.toString(), e.type.name, e.seriesId.orEmpty(), e.flexId.orEmpty(),
                    e.status.name, e.dueAt?.toString().orEmpty(),
                ).joinToString(SEP),
            )
        }
        w.commitments.forEachIndexed { i, c ->
            put(
                "wl.c.$i",
                listOf(
                    c.seriesId, c.amountKop.toString(), c.reservedAt.toString(), c.state.name, c.settledAt?.toString().orEmpty(),
                    c.returnKop.toString(), c.returnDueAt?.toString().orEmpty(), c.returnProcessed.toString(), c.returnSeen.toString(),
                    c.fixedAt?.toString().orEmpty(),
                ).joinToString(SEP),
            )
        }
        put("wl.restartReserved", w.restartReservedKop.toString())
        w.restartHolds.forEachIndexed { i, h ->
            put(
                "wl.r.$i",
                listOf(h.seriesId, h.amountKop.toString(), h.heldAt.toString(), h.state.name, h.settledAt?.toString().orEmpty()).joinToString(SEP),
            )
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
                    zone = TimeZone.of(map.req("s.zone")),
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
        val series = map.series("sr.")
        val flex = map["fx.id"]?.let { id ->
            FlexPackage(
                id = id,
                activatedAt = Instant.parse(map.req("fx.activatedAt")),
                source = GrantSource.valueOf(map.req("fx.source")),
                used = map.req("fx.used").toInt(),
                successful = map.req("fx.successful").toInt(),
                currentSessionId = map["fx.current"],
                consumedSessionId = map["fx.consumed"],
                settledSessionId = map["fx.settled"],
                endedAt = map["fx.endedAt"]?.let(Instant::parse),
                lastBurntAt = map["fx.burntAt"]?.let(Instant::parse),
            )
        }
        val entitlements = Entitlements(
            standardFailedAt = map["en.stdFailedAt"]?.let(Instant::parse),
            flex = flex,
            freeFlexCredits = map["en.freeFlex"]?.toInt() ?: 0,
        )
        val legacy = series != null && map["sr.model"] == null
        val snapshot = Snapshot(session, anchor, lastTrusted, series, entitlements, decodeWallet(map), restartedFrom = map.series("rf."))
        return upgradeLegacy(snapshot, legacy)
    }

    private fun MutableMap<String, String>.putSeries(p: String, it: Series) {
        put("${p}id", it.id)
        put("${p}kind", it.kind.name)
        put("${p}start", it.window.start.toString())
        put("${p}end", it.window.end.toString())
        put("${p}zone", it.window.zone.id)
        put("${p}contact.name", it.contact.name)
        put("${p}contact.phone", it.contact.phone)
        put("${p}mode", it.mode.name)
        put("${p}startedAt", it.startedAt.toString())
        put("${p}done", it.completedPeriods.toString())
        put("${p}status", it.status.name)
        it.endedAt?.let { e -> put("${p}endedAt", e.toString()) }
        it.lastSessionId?.let { l -> put("${p}lastSession", l) }
        it.actualStartedAt?.let { a -> put("${p}actualStartedAt", a.toString()) }
        it.protectionMissedAt?.let { m -> put("${p}missedAt", m.toString()) }
        it.activatingSince?.let { a -> put("${p}activatingSince", a.toString()) }
        it.restartOf?.let { r -> put("${p}restartOf", r) }
        if (it.autoNow) put("${p}autoNow", "1")
    }

    private fun Map<String, String>.series(p: String): Series? = this["${p}id"]?.let { id ->
        Series(
            id = id,
            kind = SeriesKind.valueOf(req("${p}kind")),
            window = DailyWindow(
                LocalTime.parse(req("${p}start")),
                LocalTime.parse(req("${p}end")),
                TimeZone.of(req("${p}zone")),
            ),
            contact = TrustedContact(req("${p}contact.name"), req("${p}contact.phone")),
            mode = SessionMode.valueOf(req("${p}mode")),
            startedAt = Instant.parse(req("${p}startedAt")),
            completedPeriods = req("${p}done").toInt(),
            status = SeriesStatus.valueOf(req("${p}status")),
            endedAt = this["${p}endedAt"]?.let(Instant::parse),
            lastSessionId = this["${p}lastSession"],
            actualStartedAt = this["${p}actualStartedAt"]?.let(Instant::parse),
            protectionMissedAt = this["${p}missedAt"]?.let(Instant::parse),
            activatingSince = this["${p}activatingSince"]?.let(Instant::parse),
            restartOf = this["${p}restartOf"],
            autoNow = this["${p}autoNow"] == "1",
        )
    }

    /**
     * State saved before v0.7 knew only ACTIVE for a running series, which
     * was binding from the moment "Начать серию" was pressed. Now a series is
     * binding only once its first lock has begun. An old series whose first
     * lock has not started yet becomes SCHEDULED (and its "Цена слова"
     * changeable again); one that has already begun stays ACTIVE.
     */
    private fun upgradeLegacy(snapshot: Snapshot, legacy: Boolean): Snapshot {
        val series = snapshot.series
        if (!legacy || series == null || series.status != SeriesStatus.ACTIVE) return snapshot
        val session = snapshot.session
        val notBegun = series.completedPeriods == 0 && session?.status == SessionStatus.SCHEDULED
        if (!notBegun) {
            val began = if (series.completedPeriods == 0 && session != null) maxOf(session.plan.start, series.startedAt) else series.startedAt
            return snapshot.copy(series = series.copy(actualStartedAt = began))
        }
        val wallet = snapshot.wallet
        return snapshot.copy(
            series = series.copy(status = SeriesStatus.SCHEDULED),
            wallet = wallet.copy(
                commitments = wallet.commitments.map {
                    if (it.seriesId == series.id && it.state == CommitmentState.RESERVED) it.copy(state = CommitmentState.SCHEDULED) else it
                },
            ),
        )
    }

    private const val SEP = "|"

    private fun decodeWallet(map: Map<String, String>): Wallet {
        fun opt(v: String) = v.ifEmpty { null }
        val entries = generateSequence(0) { it + 1 }.map { map["wl.e.$it"] }.takeWhile { it != null }.map { line ->
            val p = line!!.split(SEP)
            LedgerEntry(
                id = p[0], at = Instant.parse(p[1]), amountKop = p[2].toLong(), type = LedgerType.valueOf(p[3]),
                seriesId = opt(p[4]), flexId = opt(p[5]), status = LedgerStatus.valueOf(p[6]), dueAt = opt(p[7])?.let(Instant::parse),
            )
        }.toList()
        val commitments = generateSequence(0) { it + 1 }.map { map["wl.c.$it"] }.takeWhile { it != null }.map { line ->
            val p = line!!.split(SEP)
            Commitment(
                seriesId = p[0], amountKop = p[1].toLong(), reservedAt = Instant.parse(p[2]), state = CommitmentState.valueOf(p[3]),
                settledAt = opt(p[4])?.let(Instant::parse), returnKop = p[5].toLong(), returnDueAt = opt(p[6])?.let(Instant::parse),
                returnProcessed = p[7].toBoolean(), returnSeen = p[8].toBoolean(),
                fixedAt = p.getOrNull(9)?.let(::opt)?.let(Instant::parse),
            )
        }.toList()
        val holds = generateSequence(0) { it + 1 }.map { map["wl.r.$it"] }.takeWhile { it != null }.map { line ->
            val p = line!!.split(SEP)
            RestartHold(
                seriesId = p[0], amountKop = p[1].toLong(), heldAt = Instant.parse(p[2]), state = RestartHoldState.valueOf(p[3]),
                settledAt = opt(p[4])?.let(Instant::parse),
            )
        }.toList()
        return Wallet(
            availableKop = map["wl.available"]?.toLong() ?: 0,
            reservedKop = map["wl.reserved"]?.toLong() ?: 0,
            entries = entries,
            commitments = commitments,
            restartReservedKop = map["wl.restartReserved"]?.toLong() ?: 0,
            restartHolds = holds,
        )
    }

    private fun Map<String, String>.req(key: String): String =
        this[key] ?: throw IllegalStateException("MAMA state is missing '$key'")
}

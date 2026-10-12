package app.mama.core

import java.time.Duration

/*
 * Emergency calls from the lock screen.
 *
 * Main rule: MAMA never gets in the way of a real emergency call. An
 * emergency call is not a state of the series: the series stays ACTIVE and
 * its lock keeps running; the call is not a Trusted Exit, not a failure,
 * resets nothing, charges nothing.
 *
 * The list of numbers comes from the phone itself (Android builds it from
 * the SIM, the network, the modem and its emergency database), so MAMA is
 * not tied to one country. A Russian list is only the pilot fallback when
 * the system list cannot be read.
 */

/** Emergency service categories: the same bits as Android's EmergencyNumber. */
object EmergencyCategory {
    const val UNSPECIFIED = 0
    const val POLICE = 1
    const val AMBULANCE = 2
    const val FIRE_BRIGADE = 4
    const val MARINE_GUARD = 8
    const val MOUNTAIN_RESCUE = 16
    const val MIEC = 32
    const val AIEC = 64
}

/** Where Android got a number from: the same bits as Android's EmergencyNumber. */
object EmergencySource {
    const val NETWORK_SIGNALING = 1
    const val SIM = 2
    const val MODEM_CONFIG = 4

    /** Android's built-in generic list (112, 911 and, without a SIM, 000, 08, 110, 999, 118, 119). */
    const val DEFAULT = 8
    const val DATABASE = 16
}

/** One number as the system reports it for one SIM (subscription). */
data class SystemEmergencyNumber(
    val number: String,
    val categories: Int = EmergencyCategory.UNSPECIFIED,
    val sources: Int = EmergencySource.DEFAULT,
    val countryIso: String = "",
)

/** One line of the "Экстренные службы" screen. */
data class EmergencyService(val number: String, val name: String)

object EmergencyNumberCatalog {
    /** Kept even when only Android's generic list has it: it works on every GSM network. */
    const val UNIVERSAL = "112"

    /** Russia pilot: used only when the system list cannot be read. */
    val RUSSIA_FALLBACK = listOf(
        EmergencyService("112", "Единая служба спасения"),
        EmergencyService("101", "Пожарные и спасатели"),
        EmergencyService("102", "Полиция"),
        EmergencyService("103", "Скорая помощь"),
        EmergencyService("104", "Аварийная газовая служба"),
    )
    private val RUSSIA_NAMES = RUSSIA_FALLBACK.associate { it.number to it.name }

    /**
     * The screen's list from what the system reports for every SIM
     * ([system] null = could not be read, e.g. before Android 10).
     * - The same number from two SIMs is shown once (categories merged).
     * - Numbers only from Android's generic default list are left out, except
     *   112, when the SIM / network / database give real ones: 911 appears
     *   only where the system really recognises it here.
     * - Old two-digit codes (01…04) are hidden when the three-digit ones
     *   (101…104) are there.
     * - 112 first, then the others in order.
     */
    fun build(system: List<SystemEmergencyNumber>?, deviceCountryIso: String?): List<EmergencyService> {
        val country = deviceCountryIso.orEmpty().lowercase()
        if (system.isNullOrEmpty()) return fallback(country)
        val merged = system
            .map { it.copy(number = it.number.filter { c -> c.isDigit() }) }
            .filter { it.number.isNotEmpty() }
            .groupBy { it.number }
            .map { (number, same) ->
                SystemEmergencyNumber(
                    number,
                    categories = same.fold(0) { acc, n -> acc or n.categories },
                    sources = same.fold(0) { acc, n -> acc or n.sources },
                    countryIso = same.firstOrNull { it.countryIso.isNotBlank() }?.countryIso.orEmpty().lowercase(),
                )
            }
        if (merged.isEmpty()) return fallback(country)
        val real = merged.filter { it.sources and EmergencySource.DEFAULT.inv() != 0 || it.number == UNIVERSAL }
        val chosen = real.ifEmpty { merged }
        val numbers = chosen.map { it.number }.toSet()
        return chosen
            .filterNot { isLegacyShortCode(it.number, numbers) }
            .sortedWith(compareBy<SystemEmergencyNumber>({ it.number != UNIVERSAL }, { it.number.length }, { it.number }))
            .map { EmergencyService(it.number, name(it, it.countryIso.ifBlank { country })) }
    }

    /** Pilot fallback: the Russian list in Russia (or when the country is unknown), else only 112. */
    fun fallback(countryIso: String?): List<EmergencyService> {
        val c = countryIso.orEmpty().lowercase()
        return if (c.isBlank() || c == "ru") RUSSIA_FALLBACK else listOf(EmergencyService(UNIVERSAL, "Единый номер экстренных служб"))
    }

    /** "01" is an old code when "101" is in the list too (Russia: 101–104 replaced 01–04 on mobiles). */
    private fun isLegacyShortCode(number: String, all: Set<String>) =
        number.length == 2 && number.startsWith("0") && "1$number" in all

    private fun name(n: SystemEmergencyNumber, country: String): String {
        if (country == "ru") RUSSIA_NAMES[n.number]?.let { return it }
        val names = listOfNotNull(
            "Полиция".takeIf { n.categories and EmergencyCategory.POLICE != 0 },
            "Скорая помощь".takeIf { n.categories and EmergencyCategory.AMBULANCE != 0 },
            "Пожарные и спасатели".takeIf { n.categories and EmergencyCategory.FIRE_BRIGADE != 0 },
            "Морская спасательная служба".takeIf { n.categories and EmergencyCategory.MARINE_GUARD != 0 },
            "Горноспасательная служба".takeIf { n.categories and EmergencyCategory.MOUNTAIN_RESCUE != 0 },
        )
        return when {
            names.isNotEmpty() -> names.joinToString(", ")
            n.number == UNIVERSAL -> "Единый номер экстренных служб"
            else -> "Экстренные службы"
        }
    }
}

/**
 * A real emergency call started from the lock screen. Lives only on the
 * device next to the lock; it is NOT a series state and never touches the
 * series, the wallet or the 30-day rules.
 */
data class EmergencyCallSession(
    val number: String,
    /** Monotonic clock (ms) when the dialer was opened. */
    val startedAtMs: Long,
    /** The app Android uses for ACTION_DIAL, if it could be resolved. */
    val dialerPackage: String?,
    /** The phone confirmed that a call is going on. */
    val callStarted: Boolean = false,
    /** Before the call: since when no call screen is visible (the user may have gone back). */
    val callFlowGoneSinceMs: Long? = null,
)

/** What is in front, as the accessibility guard sees it during an emergency call. */
enum class FrontWindow {
    /** The phone's own call flow: dialer, emergency dialer, SIM choice, in-call screen. */
    CALL_FLOW,

    /** System pieces that say nothing about where the user went (keyboard, system dialogs). */
    NEUTRAL,

    /** Anything else: home, Settings, Recents, another app, MAMA's own app. */
    OTHER,
}

/** What the phone says about calls ([UNKNOWN] when MAMA may not read the call state). */
enum class CallObservation { ACTIVE, NONE, UNKNOWN }

object EmergencyCallPolicy {
    /**
     * EMERGENCY_DIAL_GRACE_PERIOD: how long the dialer may stay open before a
     * call starts. Generous on purpose: the person may be in shock or in a
     * hurry. TODO(product): tune after tests on real phones; configurable.
     */
    val DIAL_GRACE_PERIOD: Duration = Duration.ofMinutes(2)

    /** Before the call: no call screen visible for this long means the user went back. */
    val LEFT_DIALER_AFTER: Duration = Duration.ofSeconds(3)

    /**
     * Right after the dialer is requested, whatever flashes in front (the
     * home screen behind the lock screen, a slow dialer start) does not count
     * as the user leaving: the dialer gets this long to come up.
     */
    val LAUNCH_SETTLE: Duration = Duration.ofSeconds(5)

    /**
     * Every second while the lock runs. Returns the session to keep, or null
     * when it is over. A call that is going on is never cut short: the
     * session ends only when the phone says the call ended. When in doubt
     * (call state unknown, guard not running), it is kept.
     */
    fun tick(s: EmergencyCallSession, nowMs: Long, call: CallObservation, callFlowOnScreen: Boolean?): EmergencyCallSession? {
        if (call == CallObservation.ACTIVE) return s.copy(callStarted = true, callFlowGoneSinceMs = null)
        if (s.callStarted) return if (call == CallObservation.NONE) null else s
        // No call yet.
        if (call == CallObservation.NONE && nowMs - s.startedAtMs >= DIAL_GRACE_PERIOD.toMillis()) return null
        if (settling(s, nowMs)) return s.copy(callFlowGoneSinceMs = null)
        return when (callFlowOnScreen) {
            false -> {
                val since = s.callFlowGoneSinceMs ?: nowMs
                if (nowMs - since >= LEFT_DIALER_AFTER.toMillis()) null else s.copy(callFlowGoneSinceMs = since)
            }
            true, null -> s.copy(callFlowGoneSinceMs = null)
        }
    }

    /**
     * Something new came to the front. Before the call, going anywhere else
     * than the call flow ends the session (the lock screen comes back). During
     * the call the session stays: the lock screen comes back over other apps,
     * but the call goes on and the in-call screen can be reopened.
     */
    fun onFront(s: EmergencyCallSession, front: FrontWindow): EmergencyCallSession? = when (front) {
        FrontWindow.CALL_FLOW -> s.copy(callFlowGoneSinceMs = null)
        FrontWindow.NEUTRAL -> s
        FrontWindow.OTHER -> if (s.callStarted) s else null
    }

    /** How a window in front counts at [nowMs]: nothing counts as leaving while the dialer is coming up. */
    fun effectiveFront(s: EmergencyCallSession, front: FrontWindow, nowMs: Long): FrontWindow =
        if (front == FrontWindow.OTHER && !s.callStarted && settling(s, nowMs)) FrontWindow.NEUTRAL else front

    private fun settling(s: EmergencyCallSession, nowMs: Long) = nowMs - s.startedAtMs < LAUNCH_SETTLE.toMillis()

    /** The lock screen steps aside only for the call flow, and only during a session. */
    fun overlayMayStepAside(s: EmergencyCallSession?, lastFront: FrontWindow): Boolean =
        s != null && lastFront == FrontWindow.CALL_FLOW
}

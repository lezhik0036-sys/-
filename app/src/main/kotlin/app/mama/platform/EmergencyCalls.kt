package app.mama.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import app.mama.core.CallObservation
import app.mama.core.EmergencyCallPolicy
import app.mama.core.EmergencyCallSession
import app.mama.core.EmergencyNumberCatalog
import app.mama.core.EmergencyService
import app.mama.core.FrontWindow
import app.mama.core.SystemEmergencyNumber

/**
 * Emergency calls during a lock. MAMA never gets in the way of a real
 * emergency call:
 * - the "Экстренные службы" screen lists the numbers the phone itself knows
 *   for its SIM(s) and network ([TelephonyManager.getEmergencyNumberList]);
 * - choosing one opens the phone's own dialer with the number entered
 *   (ACTION_DIAL) and starts an [EmergencyCallSession];
 * - during the session the lock screen steps aside for the phone's call flow
 *   only (dialer, SIM choice, in-call screen) and the guard never presses
 *   Back/Home over it. Anything else brings the lock screen back: an
 *   emergency call is not a phone unlock.
 * The session is kept on the device next to the lock (it survives a restart
 * of the lock service) and never touches the series, the wallet or the
 * 30-day rules.
 */
object EmergencyCalls {
    private const val TAG = "MAMA"

    sealed interface Availability {
        data class Numbers(val services: List<EmergencyService>, val fromSystem: Boolean) : Availability

        /** The device cannot place phone calls at all (tablet without telephony). */
        data object NoTelephony : Availability
    }

    /** The numbers for the "Экстренные службы" screen. */
    fun services(context: Context): Availability {
        if (!hasTelephony(context)) return Availability.NoTelephony
        val tm = context.getSystemService(TelephonyManager::class.java)
        val country = runCatching { tm?.networkCountryIso.orEmpty().ifBlank { tm?.simCountryIso.orEmpty() } }.getOrDefault("")
        val system = if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) systemNumbers(context, tm) else null
        val list = EmergencyNumberCatalog.build(system, country)
        val checked = if (system.isNullOrEmpty()) confirmedFallback(tm, list) else list
        return Availability.Numbers(checked, fromSystem = !system.isNullOrEmpty())
    }

    fun hasTelephony(context: Context): Boolean {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
    }

    /** Every SIM's list (dual SIM: one per subscription), or null when it cannot be read. */
    @SuppressLint("MissingPermission")
    private fun systemNumbers(context: Context, tm: TelephonyManager): List<SystemEmergencyNumber>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            tm.emergencyNumberList.values.flatten().map { n ->
                // Public API: lists of single bits (the bitmask getters are hidden).
                SystemEmergencyNumber(
                    n.number,
                    categories = n.emergencyServiceCategories.fold(0) { acc, c -> acc or c },
                    sources = n.emergencyNumberSources.fold(0) { acc, src -> acc or src },
                    countryIso = n.countryIso,
                )
            }
        } catch (e: RuntimeException) {
            // SecurityException, IllegalStateException (no telephony service), UnsupportedOperationException.
            Log.w(TAG, "Emergency number list unavailable", e)
            null
        }
    }

    /** Fallback numbers the system definitely rejects are left out (never all of them). */
    private fun confirmedFallback(tm: TelephonyManager?, list: List<EmergencyService>): List<EmergencyService> {
        if (tm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return list
        val kept = list.filter { isEmergencyNumber(tm, it.number) != false }
        return kept.ifEmpty { list }
    }

    /** True / false where Android can tell, null where it cannot. */
    private fun isEmergencyNumber(tm: TelephonyManager, number: String): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            tm.isEmergencyNumber(number)
        } catch (e: RuntimeException) {
            null
        }
    }

    // ---------------- the session ----------------

    private const val PREFS = "mama_emergency"

    @Volatile private var loaded = false
    @Volatile private var current: EmergencyCallSession? = null

    /** The last decisive window in front (keyboard and system dialogs do not count). */
    @Volatile var lastFront: FrontWindow = FrontWindow.OTHER
        private set

    fun session(context: Context): EmergencyCallSession? {
        if (!loaded) load(context)
        return current
    }

    /** The lock screen steps aside: a session runs and the call flow is in front. */
    fun overlayMayStepAside(context: Context): Boolean = EmergencyCallPolicy.overlayMayStepAside(session(context), lastFront)

    sealed interface Start {
        data object Opened : Start
        data object NoTelephony : Start

        /** No app could open the dialer. */
        data object NoDialer : Start
    }

    /**
     * The user chose a service: start the session and open the phone's own
     * dialer with the number entered. The call itself is placed by the user
     * in the system dialer; MAMA builds no phone system of its own.
     */
    fun start(context: Context, number: String): Start {
        if (!hasTelephony(context)) return Start.NoTelephony
        val tm = context.getSystemService(TelephonyManager::class.java)
        if (tm != null && isEmergencyNumber(tm, number) == false) {
            // Never refuse an emergency attempt because of a check: log and go on.
            Log.w(TAG, "System does not list $number as an emergency number; opening the dialer anyway")
        }
        val dialer = dialerPackage(context)
        val s = EmergencyCallSession(number, SystemClock.elapsedRealtime(), dialer)
        set(context, s)
        // The dialer is coming up: step aside right away, before its first window event.
        lastFront = FrontWindow.CALL_FLOW
        val tel = Uri.fromParts("tel", number, null)
        val opened = open(context, Intent(Intent.ACTION_DIAL, tel).apply { dialer?.let { setPackage(it) } }) ||
            (dialer != null && open(context, Intent(Intent.ACTION_DIAL, tel)))
        if (!opened) {
            end(context, "no dialer")
            return Start.NoDialer
        }
        Log.i(TAG, "Emergency call session: $number via ${dialer ?: "system choice"}")
        return Start.Opened
    }

    /** The app Android uses for ACTION_DIAL (the default dialer when there is a choice). */
    private fun dialerPackage(context: Context): String? {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val default = runCatching { telecom?.defaultDialerPackage }.getOrNull()
        val resolved = runCatching {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        // "android" is the app chooser, not a dialer.
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { telecom?.systemDialerPackage }.getOrNull() else null
        return default ?: resolved?.takeIf { it != "android" } ?: system
    }

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cannot open the dialer", e)
        false
    }

    /**
     * Every second from the lock service. Returns true when the session ended
     * (the lock screen must come back).
     */
    fun tick(context: Context, call: CallObservation, callFlowOnScreen: Boolean?): Boolean {
        val s = session(context) ?: return false
        val next = EmergencyCallPolicy.tick(s, SystemClock.elapsedRealtime(), call, callFlowOnScreen)
        if (next == null) {
            end(context, if (s.callStarted) "call ended" else "no call")
            return true
        }
        if (next != s) set(context, next)
        return false
    }

    /** The guard saw [pkg] come to the front. Returns how it counts for the session. */
    fun onFront(context: Context, pkg: String, className: String?): FrontWindow {
        // Without a session the answer is not used: skip the work.
        val s = session(context) ?: return FrontWindow.OTHER
        val front = EmergencyCallPolicy.effectiveFront(s, classify(context, pkg, className, s), SystemClock.elapsedRealtime())
        if (front != FrontWindow.NEUTRAL) lastFront = front
        val next = EmergencyCallPolicy.onFront(s, front)
        when {
            next == null -> end(context, "left the dialer for $pkg")
            next != s -> set(context, next)
        }
        return front
    }

    /** The phone's own call flow, system pieces that do not count, or anything else. */
    fun classify(context: Context, pkg: String, className: String?, s: EmergencyCallSession? = session(context)): FrontWindow {
        val p = pkg.lowercase()
        return when {
            pkg == context.packageName ->
                // MAMA's own app is somewhere else than the call; its overlay windows say nothing.
                if (className?.startsWith("app.mama.ui") == true) FrontWindow.OTHER else FrontWindow.NEUTRAL
            isCallFlow(context, p, className, s) ->
                // The phone app's own settings are not part of a call (generic, not vendor-specific).
                if (settingsScreen(className)) FrontWindow.OTHER else FrontWindow.CALL_FLOW
            p == "android" || p == "com.android.systemui" || "telephonyui" in p || "aodservice" in p || "biometrics" in p ||
                p in inputMethods(context) -> FrontWindow.NEUTRAL
            else -> FrontWindow.OTHER
        }
    }

    private fun isCallFlow(context: Context, p: String, className: String?, s: EmergencyCallSession?): Boolean {
        if (s?.dialerPackage?.lowercase() == p) return true
        if (p in CALL_FLOW_PACKAGES || "incallui" in p || p.endsWith(".emergency")) return true
        val telecom = context.getSystemService(TelecomManager::class.java)
        val dialers = runCatching {
            listOfNotNull(
                telecom?.defaultDialerPackage,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) telecom?.systemDialerPackage else null,
            )
        }.getOrDefault(emptyList())
        if (dialers.any { it.lowercase() == p }) return true
        return Calls.classify(p, className) == Calls.Screen.EMERGENCY
    }

    private fun settingsScreen(className: String?): Boolean {
        val c = className?.lowercase() ?: return false
        return "setting" in c || "preference" in c
    }

    private fun inputMethods(context: Context): Set<String> = runCatching {
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName.lowercase() }?.toSet()
    }.getOrNull().orEmpty()

    /** Brings the system in-call screen back (from the lock screen's call card). */
    @SuppressLint("MissingPermission")
    fun returnToCall(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            lastFront = FrontWindow.CALL_FLOW
            context.getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot show the in-call screen", e)
        }
    }

    /**
     * The call control refused a non-emergency number during the lock (for
     * example 112 was erased in the dialer and another number typed): the
     * session is over and the lock screen comes back. Not a failure.
     */
    fun onCallBlocked(context: Context) {
        if (session(context) != null) end(context, "another number was dialled")
        app.mama.lock.LockService.restoreLock()
    }

    /** The lock is over: nothing to step aside for any more. */
    fun clear(context: Context) {
        if (session(context) != null) end(context, "lock over")
    }

    private fun end(context: Context, why: String) {
        Log.i(TAG, "Emergency call session over: $why")
        set(context, null)
        lastFront = FrontWindow.OTHER
    }

    // Kept in device-protected storage so a restarted lock service still knows
    // a call is going on. Monotonic times are only valid within one boot.

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun set(context: Context, s: EmergencyCallSession?) {
        current = s
        loaded = true
        val e = prefs(context).edit().clear()
        if (s != null) {
            e.putString("number", s.number)
                .putLong("startedAt", s.startedAtMs)
                .putString("dialer", s.dialerPackage)
                .putBoolean("callStarted", s.callStarted)
                .putString("boot", DeviceClock.bootId(context))
            s.callFlowGoneSinceMs?.let { e.putLong("goneSince", it) }
        }
        e.apply()
    }

    private fun load(context: Context) {
        val p = prefs(context)
        val number = p.getString("number", null)
        val sameBoot = p.getString("boot", null) == DeviceClock.bootId(context)
        current = if (number != null && sameBoot) {
            EmergencyCallSession(
                number = number,
                startedAtMs = p.getLong("startedAt", 0),
                dialerPackage = p.getString("dialer", null),
                callStarted = p.getBoolean("callStarted", false),
                callFlowGoneSinceMs = if (p.contains("goneSince")) p.getLong("goneSince", 0) else null,
            )
        } else {
            null
        }
        // After a restart during a call the in-call screen is most likely in front.
        if (current != null) lastFront = FrontWindow.CALL_FLOW
        loaded = true
    }

    /** Telecom (SIM choice, call routing), the phone process (emergency dialer), in-call screens. */
    private val CALL_FLOW_PACKAGES = setOf(
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.emergency",
    )
}

package app.mama.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmergencyTest {
    private val sim = EmergencySource.SIM
    private val db = EmergencySource.DATABASE
    private val def = EmergencySource.DEFAULT

    private fun numbers(list: List<EmergencyService>) = list.map { it.number }

    // ---------------- the list ----------------

    @Test
    fun `russian SIM - 112 - 101 - 102 - 103 - 104 with their names - 112 first`() {
        val system = listOf(
            SystemEmergencyNumber("103", EmergencyCategory.AMBULANCE, db, "ru"),
            SystemEmergencyNumber("112", EmergencyCategory.UNSPECIFIED, def or db, "ru"),
            SystemEmergencyNumber("101", EmergencyCategory.FIRE_BRIGADE, db, "ru"),
            SystemEmergencyNumber("102", EmergencyCategory.POLICE, db, "ru"),
            SystemEmergencyNumber("104", EmergencyCategory.UNSPECIFIED, db, "ru"),
        )
        assertEquals(EmergencyNumberCatalog.RUSSIA_FALLBACK, EmergencyNumberCatalog.build(system, "ru"))
    }

    @Test
    fun `old two-digit codes are hidden when the three-digit ones are there`() {
        val system = listOf("112", "101", "102", "103", "104", "01", "02", "03", "04").map { SystemEmergencyNumber(it, sources = db, countryIso = "ru") }
        assertEquals(listOf("112", "101", "102", "103", "104"), numbers(EmergencyNumberCatalog.build(system, "ru")))
    }

    @Test
    fun `two-digit numbers of other countries stay - France 15 - 17 - 18`() {
        val system = listOf(
            SystemEmergencyNumber("15", EmergencyCategory.AMBULANCE, sim, "fr"),
            SystemEmergencyNumber("17", EmergencyCategory.POLICE, sim, "fr"),
            SystemEmergencyNumber("18", EmergencyCategory.FIRE_BRIGADE, sim, "fr"),
            SystemEmergencyNumber("112", sources = def, countryIso = "fr"),
        )
        val list = EmergencyNumberCatalog.build(system, "fr")
        assertEquals(listOf("112", "15", "17", "18"), numbers(list))
        assertEquals("Скорая помощь", list[1].name)
        assertEquals("Полиция", list[2].name)
    }

    @Test
    fun `dual SIM - the same number from both SIMs is shown once`() {
        val sim1 = listOf(SystemEmergencyNumber("112", sources = sim, countryIso = "ru"), SystemEmergencyNumber("103", EmergencyCategory.AMBULANCE, sim, "ru"))
        val sim2 = listOf(SystemEmergencyNumber("112", sources = db, countryIso = "ru"), SystemEmergencyNumber("103", EmergencyCategory.AMBULANCE, db, "ru"))
        assertEquals(listOf("112", "103"), numbers(EmergencyNumberCatalog.build(sim1 + sim2, "ru")))
    }

    @Test
    fun `911 only when the system really recognises it here - not from the generic default list`() {
        val russia = listOf(
            SystemEmergencyNumber("112", sources = def or db, countryIso = "ru"),
            SystemEmergencyNumber("911", sources = def, countryIso = "ru"),
            SystemEmergencyNumber("102", EmergencyCategory.POLICE, db, "ru"),
        )
        assertFalse("911" in numbers(EmergencyNumberCatalog.build(russia, "ru")))
        val usa = listOf(
            SystemEmergencyNumber("911", EmergencyCategory.UNSPECIFIED, sim or db, "us"),
            SystemEmergencyNumber("112", sources = def, countryIso = "us"),
        )
        assertEquals(listOf("112", "911"), numbers(EmergencyNumberCatalog.build(usa, "us")))
    }

    @Test
    fun `categories give the names outside Russia`() {
        val list = EmergencyNumberCatalog.build(listOf(SystemEmergencyNumber("999", EmergencyCategory.POLICE or EmergencyCategory.AMBULANCE, sim, "gb")), "gb")
        assertEquals(EmergencyService("999", "Полиция, Скорая помощь"), list.single { it.number == "999" })
    }

    @Test
    fun `no system list - Russian pilot fallback in Russia or unknown country - only 112 elsewhere`() {
        assertEquals(EmergencyNumberCatalog.RUSSIA_FALLBACK, EmergencyNumberCatalog.build(null, "ru"))
        assertEquals(EmergencyNumberCatalog.RUSSIA_FALLBACK, EmergencyNumberCatalog.build(emptyList(), null))
        assertEquals(listOf("112"), numbers(EmergencyNumberCatalog.build(null, "de")))
        assertFalse("01" in numbers(EmergencyNumberCatalog.RUSSIA_FALLBACK))
    }

    @Test
    fun `only generic defaults with no SIM - 112 is kept`() {
        val noSim = listOf("112", "911", "000", "08", "110", "999", "118", "119").map { SystemEmergencyNumber(it, sources = def) }
        assertEquals(listOf("112"), numbers(EmergencyNumberCatalog.build(noSim, "")))
    }

    // ---------------- the session ----------------

    private val opened = EmergencyCallSession("112", startedAtMs = 1_000, dialerPackage = "com.samsung.android.dialer")

    @Test
    fun `dialer open - the lock screen steps aside for the call flow only`() {
        assertTrue(EmergencyCallPolicy.overlayMayStepAside(opened, FrontWindow.CALL_FLOW))
        assertFalse(EmergencyCallPolicy.overlayMayStepAside(opened, FrontWindow.OTHER))
        assertFalse(EmergencyCallPolicy.overlayMayStepAside(null, FrontWindow.CALL_FLOW), "no session, no stepping aside")
    }

    @Test
    fun `the dialer stays while the number is being dialled`() {
        var s: EmergencyCallSession? = opened
        for (t in 1..45) s = EmergencyCallPolicy.tick(s!!, 1_000L + t * 1_000, CallObservation.NONE, callFlowOnScreen = true)
        assertNotNull(s, "45 seconds in the dialer are fine")
        // Keyboard or a system dialog in front changes nothing.
        assertEquals(s, EmergencyCallPolicy.onFront(s, FrontWindow.NEUTRAL))
    }

    @Test
    fun `call started - kept for the whole call - even long after the grace period - and ends with the call`() {
        val calling = EmergencyCallPolicy.tick(opened, 10_000, CallObservation.ACTIVE, true)!!
        assertTrue(calling.callStarted)
        val muchLater = 1_000 + EmergencyCallPolicy.DIAL_GRACE_PERIOD.inWholeMilliseconds * 10
        assertNotNull(EmergencyCallPolicy.tick(calling, muchLater, CallObservation.ACTIVE, false))
        // Going home during the call: the lock comes back, the call and the session go on.
        assertNotNull(EmergencyCallPolicy.onFront(calling, FrontWindow.OTHER))
        assertNotNull(EmergencyCallPolicy.tick(calling, muchLater, CallObservation.UNKNOWN, false), "never cut short when unsure")
        assertNull(EmergencyCallPolicy.tick(calling, muchLater + 1_000, CallObservation.NONE, false), "call over: lock screen back")
    }

    @Test
    fun `back from the dialer without a call - session over - lock screen back`() {
        assertNull(EmergencyCallPolicy.onFront(opened, FrontWindow.OTHER))
        // Or detected from the screen: no call window for 3 seconds (after the dialer had time to come up).
        val t0 = 1_000 + EmergencyCallPolicy.LAUNCH_SETTLE.inWholeMilliseconds
        val gone = EmergencyCallPolicy.tick(opened, t0, CallObservation.NONE, callFlowOnScreen = false)!!
        assertNotNull(EmergencyCallPolicy.tick(gone, t0 + 1_000, CallObservation.NONE, false))
        assertNull(EmergencyCallPolicy.tick(gone, t0 + EmergencyCallPolicy.LEFT_DIALER_AFTER.inWholeMilliseconds, CallObservation.NONE, false))
    }

    @Test
    fun `while the dialer is coming up nothing counts as leaving - the Samsung cover-up`() {
        // The home screen behind the lock screen flashes before the dialer appears.
        assertEquals(FrontWindow.NEUTRAL, EmergencyCallPolicy.effectiveFront(opened, FrontWindow.OTHER, 1_500))
        var s: EmergencyCallSession? = opened
        for (t in listOf(1_200L, 2_500L, 4_000L, 5_900L)) s = EmergencyCallPolicy.tick(s!!, t, CallObservation.NONE, callFlowOnScreen = false)
        assertNotNull(s, "a slow dialer start does not end the session")
        // After that, going elsewhere does count.
        val later = 1_000 + EmergencyCallPolicy.LAUNCH_SETTLE.inWholeMilliseconds
        assertEquals(FrontWindow.OTHER, EmergencyCallPolicy.effectiveFront(opened, FrontWindow.OTHER, later))
        // And during a call it never matters.
        val calling = opened.copy(callStarted = true)
        assertEquals(FrontWindow.OTHER, EmergencyCallPolicy.effectiveFront(calling, FrontWindow.OTHER, 1_500))
        assertNotNull(EmergencyCallPolicy.onFront(calling, FrontWindow.OTHER))
    }

    @Test
    fun `no call within the grace period - session over`() {
        val end = 1_000 + EmergencyCallPolicy.DIAL_GRACE_PERIOD.inWholeMilliseconds
        assertNotNull(EmergencyCallPolicy.tick(opened, end - 1, CallObservation.NONE, true))
        assertNull(EmergencyCallPolicy.tick(opened, end, CallObservation.NONE, true))
    }

    @Test
    fun `call state unknown - the grace period never cuts a possible call - leaving still ends it`() {
        val late = 1_000 + EmergencyCallPolicy.DIAL_GRACE_PERIOD.inWholeMilliseconds * 3
        assertNotNull(EmergencyCallPolicy.tick(opened, late, CallObservation.UNKNOWN, true))
        assertNotNull(EmergencyCallPolicy.tick(opened, late, CallObservation.UNKNOWN, null), "guard not running: kept")
        assertNull(EmergencyCallPolicy.onFront(opened, FrontWindow.OTHER))
    }
}

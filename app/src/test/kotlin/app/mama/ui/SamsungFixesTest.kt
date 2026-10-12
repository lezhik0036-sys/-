package app.mama.ui

import android.app.NotificationManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.mama.core.Commitment
import app.mama.core.CommitmentState
import app.mama.core.DailyWindow
import app.mama.core.Entitlements
import app.mama.core.GrantSource
import app.mama.core.LedgerEntry
import app.mama.core.LedgerStatus
import app.mama.core.LedgerType
import app.mama.core.Series
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.core.Snapshot
import app.mama.core.StateCodec
import app.mama.core.TrustedContact
import app.mama.core.Wallet
import app.mama.platform.Mama
import app.mama.ui.MainActivity.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Regression tests for the three findings on the physical Samsung. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamsungFixesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val mom = TrustedContact("Мама", "+79001234567")
    private val failedAt = Instant.now().minus(Duration.ofDays(2))

    private fun seed(snapshot: Snapshot) {
        val store = context.createDeviceProtectedStorageContext().getSharedPreferences("mama_state", Context.MODE_PRIVATE).edit().clear()
        StateCodec.encode(snapshot).forEach { (k, v) -> store.putString(k, v) }
        store.commit()
        Mama.reconcile(context)
    }

    private fun failedSeries(commitment: Commitment? = null, availableKop: Long = 0) = seed(
        Snapshot(
            series = Series(
                "old", SeriesKind.SEVEN, DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), zone), mom, SessionMode.SLEEP,
                startedAt = failedAt.minus(Duration.ofDays(3)), completedPeriods = 2, status = SeriesStatus.BROKEN, endedAt = failedAt,
                actualStartedAt = failedAt.minus(Duration.ofDays(3)),
            ),
            entitlements = Entitlements(standardFailedAt = failedAt),
            wallet = Wallet(availableKop = availableKop, commitments = listOfNotNull(commitment)),
        ),
    )

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun find(v: View, text: String): View? = when {
        v is TextView && v.text.toString() == text -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it), text) }
        else -> null
    }

    private fun content(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content)!!

    private fun dialogRoot(): View = ShadowDialog.getLatestDialog()!!.window!!.decorView

    @Before
    fun clean() {
        seed(Snapshot())
        context.getSharedPreferences("mama_setup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---------------- 1. a new series does not inherit the old time ----------------

    @Test
    fun `1 - previous series had 23_00, a new draft starts at the current time`() {
        context.getSharedPreferences("mama_setup", Context.MODE_PRIVATE).edit()
            .putString("start", "23:00").putString("end", "07:00").putString("choice", "SEVEN").commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val before = LocalTime.now(zone).withSecond(0).withNano(0)
        a.beginDraft()
        a.go(Step.TIME)
        val after = LocalTime.now(zone).withSecond(0).withNano(0)
        assertTrue("draft start ${a.start}, now $before..$after", a.start == before || a.start == after)
        assertEquals(a.start.plusHours(8), a.end)
    }

    @Test
    fun `2 - a chosen time survives going on, coming back and a recreated screen`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        a.choice = MainActivity.Choice.SEVEN
        a.beginDraft()
        a.go(Step.TIME)
        a.start = LocalTime.of(18, 0)
        a.saveForm()
        a.go(Step.CONTACT)
        a.back()
        assertEquals(Step.TIME, a.step)
        assertEquals(LocalTime.of(18, 0), a.start)
        // «Начать» again from the welcome screen keeps the open draft.
        a.beginDraft()
        a.go(Step.TIME)
        assertEquals(LocalTime.of(18, 0), a.start)
        // Rotation / recreation: same step, same time.
        controller.recreate()
        val b = controller.get()
        assertEquals(Step.TIME, b.step)
        assertEquals(LocalTime.of(18, 0), b.start)
        // Once the draft became a series, the next draft starts from now again.
        b.closeDraft()
        b.beginDraft()
        b.go(Step.TIME)
        val now = LocalTime.now(zone).withSecond(0).withNano(0)
        if (now != LocalTime.of(18, 0) && now.minusMinutes(1) != LocalTime.of(18, 0)) {
            assertFalse("a new draft does not reuse 18:00", b.start == LocalTime.of(18, 0))
        }
    }

    // ---------------- 2. the 50% return is a surprise ----------------

    private val announcing = listOf("50%", "50 %", "24 час", "вернутся", "Начисление", "половин")

    private fun assertNoAnnouncement(where: String, list: List<String>) {
        for (t in list) for (word in announcing) assertFalse("$where announces the return: «$t»", t.contains(word))
    }

    @Test
    fun `3 - failure with a commitment - the failure screen does not announce any return`() {
        failedSeries(
            Commitment("old", 500_00, failedAt, CommitmentState.FORFEITED, failedAt, returnKop = 250_00, returnDueAt = Instant.now().plus(Duration.ofHours(20))),
        )
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val shown = texts(content(a))
        assertTrue(shown.toString(), "Серия завершена досрочно" in shown)
        assertTrue(shown.toString(), "Цена слова: 500 ₽" in shown)
        assertTrue(shown.toString(), "Цена слова применена." in shown)
        assertNoAnnouncement("failure screen", shown)
        // Nor the confirmation, "Цена слова" and Restart screens.
        a.choice = MainActivity.Choice.SEVEN
        a.commitmentKop = 500_00
        assertNoAnnouncement("review", texts(a.reviewScreen().first))
        assertNoAnnouncement("commitment", texts(a.commitmentScreen(false, SeriesKind.SEVEN).first))
        assertNoAnnouncement("restart", texts(a.commitmentScreen(true, SeriesKind.SEVEN).first))
        // The balance history does not list a return that has not happened.
        val pending = LedgerEntry("return-old", failedAt, 250_00, LedgerType.COMMITMENT_PARTIAL_RETURN, "old", status = LedgerStatus.SCHEDULED, dueAt = failedAt.plusSeconds(86_400))
        val history = texts(a.ledgerList(Wallet(entries = listOf(pending)), zone))
        assertFalse(history.toString(), history.any { "Возврат" in it })
    }

    @Test
    fun `4 - once the return is really credited, MAMA says so`() {
        failedSeries(
            Commitment("old", 500_00, failedAt, CommitmentState.FORFEITED, failedAt, returnKop = 250_00, returnDueAt = failedAt.plus(Duration.ofHours(24))),
        )
        val w = Mama.reconcile(context).snapshot.wallet
        assertEquals(250_00L, w.availableKop)
        val nm = context.getSystemService(NotificationManager::class.java)
        val titles = shadowOf(nm).allNotifications.map { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() }
        assertTrue(titles.toString(), "MAMA вернула тебе 250 ₽" in titles)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(texts(a.ledgerList(Mama.wallet(context), zone)).contains("Возврат от MAMA"))
        assertTrue(texts(dialogRoot()).contains("MAMA вернула тебе 250 ₽"))
    }

    // ---------------- 3. test credits pay like the real balance ----------------

    @Test
    fun `5 and 6 - test top-up 1 000, Restart 299 paid from the balance through the screens, cancelled before start gives 299 back`() {
        failedSeries()
        assertTrue(Mama.topUpTest(context, 1_000_00))
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        find(content(a), "Начать заново")!!.performClick()
        find(content(a), "Пропустить")!!.performClick()
        val pay = find(dialogRoot(), "Оплатить с баланса MAMA")
        assertNotNull("pay from balance is offered", pay)
        pay!!.performClick()

        val restarted = Mama.series(context)!!
        assertEquals(SeriesStatus.SCHEDULED, restarted.status)
        assertEquals("old", restarted.restartOf)
        assertEquals(701_00L, Mama.wallet(context).availableKop)
        assertEquals(299_00L, Mama.wallet(context).restartReservedKop)
        assertEquals(LedgerType.BALANCE_TOPUP_TEST, Mama.wallet(context).entries.first().type)

        assertTrue(Mama.cancelScheduledSeries(context))
        assertEquals(1_000_00L, Mama.wallet(context).availableKop)
        assertEquals(0L, Mama.wallet(context).restartReservedKop)
        assertEquals("the original 30-day wait goes on", failedAt, Mama.entitlements(context).standardFailedAt)
    }

    @Test
    fun `not enough for the Restart - a clear message and a test top-up that continues the payment`() {
        failedSeries(availableKop = 200_00)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        find(content(a), "Начать заново")!!.performClick()
        find(content(a), "Пропустить")!!.performClick()
        val first = texts(dialogRoot())
        assertTrue(first.toString(), "На балансе MAMA недостаточно средств." in first)
        assertNull(find(dialogRoot(), "Оплатить с баланса MAMA"))
        find(dialogRoot(), "+${Texts.money(1_000_00)} · тест")!!.performClick()
        find(dialogRoot(), "Оплатить с баланса MAMA")!!.performClick()
        assertEquals(SeriesStatus.SCHEDULED, Mama.series(context)!!.status)
        assertEquals(901_00L, Mama.wallet(context).availableKop)
    }

    @Test
    fun `7 - test top-up pays for FLEX 399`() {
        assertTrue(Mama.topUpTest(context, 1_000_00))
        assertTrue(Mama.activateFlex(context, GrantSource.INTERNAL_BALANCE))
        assertEquals(601_00L, Mama.wallet(context).availableKop)
        assertEquals(LedgerType.FLEX_PURCHASE_TEST, Mama.wallet(context).entries.last().type)
        assertNotNull(Mama.entitlements(context).flex)
    }

    @Test
    fun `8 - test top-up reserves «Цена слова»`() {
        assertTrue(Mama.topUpTest(context, 5_000_00))
        val window = DailyWindow(LocalTime.now(zone).plusHours(10).withSecond(0).withNano(0), LocalTime.now(zone).plusHours(18).withSecond(0).withNano(0), zone)
        assertEquals(Mama.SeriesStart.Started, Mama.startSeries(context, SeriesKind.SEVEN, window, mom, SessionMode.SLEEP, 1_000_00))
        assertEquals(4_000_00L, Mama.wallet(context).availableKop)
        assertEquals(1_000_00L, Mama.wallet(context).reservedKop)
    }
}

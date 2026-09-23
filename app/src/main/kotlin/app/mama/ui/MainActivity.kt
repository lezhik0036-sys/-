package app.mama.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.mama.core.DailyWindow
import app.mama.core.LockPlan
import app.mama.core.LockState
import app.mama.core.SessionMode
import app.mama.core.SessionStatus
import app.mama.core.TrustedContact
import app.mama.platform.Mama
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Setup screen: mode, start/end time and time zone, trusted contact,
 * permission checklist, start. While a session is planned or running it
 * shows the session instead of the form, so the contact cannot be changed.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("mama_setup", Context.MODE_PRIVATE) }

    private var mode = SessionMode.SLEEP
    private var start = LocalTime.of(23, 0)
    private var end = LocalTime.of(7, 0)
    private var zone: ZoneId = ZoneId.systemDefault()
    private lateinit var contactName: EditText
    private lateinit var contactPhone: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = runCatching { SessionMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(SessionMode.SLEEP)
        start = runCatching { LocalTime.parse(prefs.getString("start", null)) }.getOrDefault(start)
        end = runCatching { LocalTime.parse(prefs.getString("end", null)) }.getOrDefault(end)
        zone = runCatching { ZoneId.of(prefs.getString("zone", null)) }.getOrDefault(zone)
        contactName = input("Имя, например «Мама»", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            .apply { setText(prefs.getString("contactName", "")) }
        contactPhone = input("+7 900 123-45-67", InputType.TYPE_CLASS_PHONE)
            .apply { setText(prefs.getString("contactPhone", "")) }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (setupAllActive) window.decorView.post { continueSetupAll() }
    }

    // --- "grant everything" wizard ---

    /** True while the one-button setup walks through the missing permissions. */
    private var setupAllActive = false

    /** Whether this screen was covered since the last runtime-permission request. */
    private var pausedSinceRequest = false

    override fun onPause() {
        super.onPause()
        pausedSinceRequest = true
    }

    /** Items already offered in this run, so a refusal does not loop forever. */
    private val setupAllOffered = mutableSetOf<Requirement>()

    private fun startSetupAll() {
        setupAllActive = true
        setupAllOffered.clear()
        continueSetupAll()
    }

    /**
     * Android does not let an app grant these itself: each needs a tap from the
     * user. The wizard asks all runtime permissions in one dialog, then opens
     * each settings screen in turn and comes back here after every one.
     */
    private fun continueSetupAll() {
        if (!setupAllActive) return
        val missing = Requirement.entries.filter { !it.isGranted(this) && it !in setupAllOffered }
        val runtime = missing.filter { it.runtimePermissions.isNotEmpty() }
        if (runtime.isNotEmpty()) {
            setupAllOffered += runtime
            pausedSinceRequest = false
            requestPermissions(runtime.flatMap { it.runtimePermissions.toList() }.toTypedArray(), SETUP_ALL)
            return
        }
        val next = missing.firstOrNull()
        if (next == null) {
            setupAllActive = false
            val left = Requirement.missingRequired(this)
            if (left.isEmpty()) {
                Toast.makeText(this, "Все разрешения выданы", Toast.LENGTH_SHORT).show()
            } else {
                alert("Остались разрешения", left.joinToString("\n") { "• ${it.title}" } +
                    "\n\nНажмите «Выдать все разрешения» ещё раз.")
            }
            render()
            return
        }
        setupAllOffered += next
        next.hint?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        next.request(this)
    }

    @Deprecated("Framework Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        // If a system dialog was shown, onResume continues the wizard. If Android
        // answered without a dialog (denied for good earlier), continue here.
        if (requestCode == SETUP_ALL && !pausedSinceRequest) continueSetupAll()
    }

    private fun saveForm() {
        prefs.edit()
            .putString("mode", mode.name)
            .putString("start", start.toString())
            .putString("end", end.toString())
            .putString("zone", zone.id)
            .putString("contactName", contactName.text.toString())
            .putString("contactPhone", contactPhone.text.toString())
            .apply()
    }

    private fun render() {
        val state = Mama.sync(this)
        // The contact fields are reused across renders to keep typed text.
        (contactName.parent as? ViewGroup)?.removeView(contactName)
        (contactPhone.parent as? ViewGroup)?.removeView(contactPhone)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }
        column.addView(label("MAMA  ${Texts.version(this)}", 30f, bold = true))
        column.addView(label("Добровольная блокировка телефона на выбранное время", 15f, MUTED))
        column.addView(space(16))
        when (state) {
            LockState.Free -> renderForm(column)
            else -> renderSession(column, state)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    // --- session in progress ---

    private fun renderSession(column: LinearLayout, state: LockState) {
        val session = Mama.session(this) ?: return
        val z = session.plan.zone
        val now = Mama.trustedNow(this)
        column.addView(label("Режим «${Texts.mode(session.plan.mode)}»", 20f, bold = true))
        column.addView(label("${Texts.dayTime(session.plan.start, z)} → ${Texts.dayTime(session.plan.end, z)}", 16f))
        column.addView(label(Texts.zone(z, now), 14f, MUTED))
        column.addView(label("Доверенный контакт: ${session.contact.name}, ${session.contact.phone}", 14f, MUTED))
        column.addView(space(16))
        when (state) {
            is LockState.Waiting -> {
                column.addView(label("Начнётся через ${Texts.duration(Duration.between(now, state.startsAt))}.", 16f))
                column.addView(label("До начала можно передумать. После — выйти можно только с кодом от доверенного контакта.", 14f, MUTED))
                column.addView(button("Отменить до начала") {
                    Mama.cancelBeforeStart(this)
                    render()
                })
            }
            is LockState.Locked -> column.addView(label("Идёт блокировка.", 16f))
            is LockState.EmergencyPass -> {
                column.addView(label("Экстренный доступ до ${Texts.time(state.until, z)}.", 16f))
                column.addView(label("После этого блокировка вернётся до ${Texts.time(state.endsAt, z)}.", 14f, MUTED))
            }
            LockState.Free -> Unit
        }
    }

    // --- setup form ---

    private fun renderForm(column: LinearLayout) {
        Mama.session(this)?.takeIf { it.status == SessionStatus.FINISHED }?.let {
            column.addView(label("Прошлая сессия: ${Texts.finishReason(it.finishReason)}.", 14f, MUTED))
            column.addView(space(12))
        }

        column.addView(section("Режим"))
        column.addView(button(Texts.mode(mode)) { pickMode() })

        column.addView(section("Время"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("С ${start}") { pickTime(start) { start = it } }, weighted())
        row.addView(button("До ${end}") { pickTime(end) { end = it } }, weighted())
        column.addView(row)
        column.addView(button("Часовой пояс: ${Texts.zone(zone)}") { pickZone() })
        column.addView(label(preview(), 14f, MUTED))

        column.addView(section("Доверенный контакт"))
        column.addView(label("Только этот человек сможет выпустить вас раньше: ему придёт SMS с кодом из 5 цифр, на ввод — 3 попытки.", 14f, MUTED))
        column.addView(contactName)
        column.addView(contactPhone)
        column.addView(button("Выбрать из контактов") { pickContact() })

        column.addView(section("Разрешения"))
        if (Requirement.entries.any { !it.isGranted(this) }) {
            column.addView(button("Выдать все разрешения", primary = true) { startSetupAll() })
            column.addView(label("MAMA по очереди откроет каждый экран: включите переключатель и вернитесь назад.", 13f, MUTED))
        }
        Requirement.entries.forEach { req ->
            val ok = req.isGranted(this)
            val mark = if (ok) "✓" else if (req.required) "✗" else "○"
            column.addView(button("$mark  ${req.title}", enabled = !ok) { req.request(this) })
            column.addView(label(req.why, 13f, MUTED))
        }

        column.addView(space(20))
        column.addView(button("Запустить MAMA", primary = true) { confirmStart() })
    }

    private fun plan(): LockPlan =
        DailyWindow(start, end, zone).nextPlan(Mama.trustedNow(this), mode, Mama.policy.minDuration)

    private fun preview(): String {
        if (start == end) return "Начало и конец совпадают."
        val p = plan()
        val now = Mama.trustedNow(this)
        val startsNow = !p.start.isAfter(now)
        val from = if (startsNow) "сразу" else Texts.dayTime(p.start, zone)
        val length = Duration.between(maxOf(p.start, now), p.end)
        val local = ZoneId.systemDefault()
        val localHint = if (local.rules.getOffset(now) != zone.rules.getOffset(now)) {
            "\nПо времени телефона (${local.id}): ${Texts.dayTime(p.start, local)} → ${Texts.dayTime(p.end, local)}"
        } else {
            ""
        }
        return "Блокировка: $from → ${Texts.dayTime(p.end, zone)} (${Texts.duration(length)})$localHint"
    }

    private fun confirmStart() {
        saveForm()
        val missing = Requirement.missingRequired(this)
        if (missing.isNotEmpty()) {
            alert("Не хватает разрешений", missing.joinToString("\n") { "• ${it.title}" })
            return
        }
        if (start == end) {
            alert("Проверьте время", "Начало и конец совпадают.")
            return
        }
        val name = contactName.text.toString().trim()
        val phone = TrustedContact.normalizePhone(contactPhone.text.toString())
        if (name.isEmpty() || phone == null) {
            alert("Доверенный контакт", "Укажите имя и номер телефона в формате +7 900 123-45-67.")
            return
        }
        val contact = TrustedContact(name, phone)
        val p = plan()
        AlertDialog.Builder(this)
            .setTitle("Запустить блокировку?")
            .setMessage(
                "${preview()}\n\nПосле начала отменить блокировку сможете только с кодом, " +
                    "который придёт $name ($phone). Перезагрузка её не снимет.",
            )
            .setPositiveButton("Запустить") { _, _ -> doStart(p, contact) }
            .setNegativeButton("Назад", null)
            .show()
    }

    private fun doStart(plan: LockPlan, contact: TrustedContact) {
        when (val r = Mama.start(this, plan, contact)) {
            is Mama.StartResult.Started -> render()
            is Mama.StartResult.Rejected -> alert("Не получилось", Texts.createError(r.reason))
            Mama.StartResult.AlreadyRunning -> render()
        }
    }

    private fun pickMode() {
        val modes = SessionMode.entries
        AlertDialog.Builder(this)
            .setTitle("Режим")
            .setSingleChoiceItems(modes.map { Texts.mode(it) }.toTypedArray(), modes.indexOf(mode)) { d, i ->
                mode = modes[i]
                saveForm()
                d.dismiss()
                render()
            }
            .show()
    }

    private fun pickTime(initial: LocalTime, set: (LocalTime) -> Unit) {
        TimePickerDialog(this, { _, h, m ->
            set(LocalTime.of(h, m))
            saveForm()
            render()
        }, initial.hour, initial.minute, true).show()
    }

    private fun pickZone() {
        val now = Instant.now()
        val zones = ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .map(ZoneId::of)
            .sortedWith(compareBy<ZoneId> { it.rules.getOffset(now).totalSeconds }.thenBy { it.id })
        val system = ZoneId.systemDefault()
        val items = listOf(system) + zones.filter { it != system }
        AlertDialog.Builder(this)
            .setTitle("Часовой пояс")
            .setItems(items.map { Texts.zone(it, now) }.toTypedArray()) { _, i ->
                zone = items[i]
                saveForm()
                render()
            }
            .show()
    }

    private fun pickContact() {
        saveForm()
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        runCatching { @Suppress("DEPRECATION") startActivityForResult(intent, PICK_CONTACT) }
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_CONTACT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                contactName.setText(c.getString(0).orEmpty())
                contactPhone.setText(c.getString(1).orEmpty())
                saveForm()
            }
        }
    }

    // --- small view helpers ---

    private fun alert(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Понятно", null).show()
    }

    private fun section(text: String) = label(text, 13f, ACCENT, bold = true).apply {
        setPadding(0, dp(20), 0, dp(6))
        isAllCaps = true
    }

    private fun label(text: String, sizeSp: Float, color: Int = FG, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
    }

    private fun button(
        text: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        isAllCaps = false
        isEnabled = enabled
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (primary) Color.WHITE else FG)
        background = GradientDrawable().apply {
            setColor(if (primary) PRIMARY else SURFACE)
            cornerRadius = dp(12).toFloat()
        }
        if (primary) gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_CONTACT = 10
        const val SETUP_ALL = 20
        val FG = Color.rgb(0x1A, 0x1D, 0x22)
        val MUTED = Color.rgb(0x6B, 0x72, 0x7C)
        val ACCENT = Color.rgb(0x2B, 0x5C, 0xB8)
        val PRIMARY = Color.rgb(0x2B, 0x5C, 0xB8)
        val SURFACE = Color.rgb(0xEE, 0xF1, 0xF5)
    }
}

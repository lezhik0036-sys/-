package app.mama.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.mama.billing.FeatureFlags
import app.mama.core.DailyWindow
import app.mama.core.LaunchTime
import app.mama.core.LockState
import app.mama.core.SeriesDraft
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.platform.Mama
import app.mama.ui.design.DesignKit
import app.mama.ui.design.SceneLayout
import app.mama.ui.design.SceneMood
import app.mama.ui.design.Scenery
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * MAMA's only activity. Shows, in this order of priority: the result of a
 * finished series or FLEX package, the dashboard of whatever is running, or
 * the step-by-step setup wizard (design reference screens 1–7). Screens live
 * in SetupScreens.kt and HomeScreens.kt; this class holds the state.
 */
class MainActivity : Activity() {

    /** What the user is about to start. */
    enum class Choice(val series: SeriesKind?) {
        TEST(null), THREE(SeriesKind.THREE), FIVE(SeriesKind.FIVE), SEVEN(SeriesKind.SEVEN), FLEX(null)
    }

    enum class Step { WELCOME, PERMISSIONS, SERIES, TIME, CONTACT, PROMISE, COMMITMENT, REVIEW }

    /**
     * How the draft's start is meant. AUTO_NOW (a new draft, start untouched):
     * the series starts at the final confirmation, for the chosen length.
     * MANUAL (the user set the start): a fixed daily window.
     */
    enum class StartMode { AUTO_NOW, MANUAL }

    internal val prefs by lazy { getSharedPreferences("mama_setup", Context.MODE_PRIVATE) }
    internal val kit by lazy { Kit(this) }

    /** The components of MAMA Design System v2 (docs/MAMA_DESIGN_SYSTEM_V2.md). */
    internal val design by lazy { DesignKit(this) }

    /** What only the hero screens need on top of [design] (the accent in a title, the earth page). */
    internal val v2 by lazy { app.mama.ui.design.DesignKitV2(this) }

    internal var mode = SessionMode.SLEEP
    internal var choice = Choice.SEVEN
    internal var start: LocalTime = LocalTime.of(23, 0)
    internal var end: LocalTime = LocalTime.of(7, 0)
    internal var zone: ZoneId = ZoneId.systemDefault()
    internal var startMode = StartMode.MANUAL
    internal lateinit var contactName: EditText
    internal lateinit var contactPhone: EditText
    internal lateinit var promiseField: EditText
    internal var manualContact = false
    internal var agreed = false

    /** "Всё готово": the full terms of the series are shown (they are one tap away otherwise). */
    internal var termsOpen = false

    /** The motivation screen was opened from the home screen's phrase. */
    internal var motivationOpen = false

    /** "Мой путь" (the statistics) was opened from the welcome, success or recovery screen, which have no tab bar. */
    internal var pathOpen = false

    /**
     * The short onboarding shown once before the first use, between the
     * welcome and the setup: its page (1–3), or 0 when it is not shown. Only
     * pages in front of the wizard; the setup itself is unchanged.
     */
    internal var onboardingPage = 0

    /** "Цена слова" chosen for the series about to start (kopecks), or null. Never carried forward. */
    internal var commitmentKop: Long? = null

    /** Failure screen: the user chose "Начать заново": Restart time, then "Цена слова", then payment. */
    internal var restartFlow = false

    /** Current step of the Restart flow: [Step.TIME] or [Step.COMMITMENT]. */
    internal var restartStep = Step.TIME

    /** "Изменить условия" of a SCHEDULED series: the wizard edits a draft of its conditions. */
    internal var editing = false

    /** Show "Изменения сохранены" on the dashboard after an edit. */
    internal var editSaved = false
    internal var returnNoticeOpen = false

    internal var step = Step.WELCOME
    /** Bottom navigation tab on dashboards: 0 home, 1 statistics, 2 settings. */
    internal var tab = 0
    /** FLEX: the period planner is open. */
    internal var flexPlanning = false
    internal var flexStartNow = true
    internal var flexStart: LocalDateTime? = null
    internal var flexEnd: LocalDateTime? = null

    /** Debug builds only: renders one screen with sample data (screenshot tour). */
    internal var preview: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        preview = intent?.getStringExtra(EXTRA_PREVIEW)?.takeIf { debuggable }
        edgeToEdge()
        mode = runCatching { SessionMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(SessionMode.SLEEP)
        choice = runCatching { Choice.valueOf(prefs.getString("choice", null)!!) }.getOrElse {
            runCatching { Choice.valueOf(prefs.getString("series", null)!!) }.getOrDefault(Choice.SEVEN)
        }
        if (choice == Choice.TEST && !FeatureFlags.TEST_MODE_ENABLED) choice = Choice.SEVEN
        // Back on the same wizard step after a screen rotation or recreation.
        savedInstanceState?.getString(STATE_STEP)?.let { s -> runCatching { step = Step.valueOf(s) } }
        onboardingPage = savedInstanceState?.getInt(STATE_ONBOARDING) ?: 0
        savedInstanceState?.getString(STATE_RESTART_STEP)?.let { s -> runCatching { restartStep = Step.valueOf(s); restartFlow = true } }
        start = runCatching { LocalTime.parse(prefs.getString("start", null)) }.getOrDefault(start)
        end = runCatching { LocalTime.parse(prefs.getString("end", null)) }.getOrDefault(end)
        zone = runCatching { ZoneId.of(prefs.getString("zone", null)) }.getOrDefault(zone)
        // A draft from before AUTO_NOW keeps its time as it was: MANUAL.
        startMode = runCatching { StartMode.valueOf(prefs.getString(DRAFT_START_MODE, null)!!) }.getOrDefault(StartMode.MANUAL)
        contactName = kit.textField("Имя, например «Жена»", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            .apply { setText(prefs.getString("contactName", "")) }
        contactPhone = kit.textField("+7 900 123-45-67", InputType.TYPE_CLASS_PHONE)
            .apply { setText(prefs.getString("contactPhone", "")) }
        promiseField = kit.textField(
            "Хочу нормально высыпаться и чувствовать себя лучше утром.",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, multiLine = true,
        ).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(app.mama.platform.Promise.MAX_LENGTH))
            minLines = 4
            setText(app.mama.platform.Promise.get(this@MainActivity))
        }
    }

    /**
     * Edge-to-edge on every Android version; screens pad themselves for the
     * bars in [padForBars]. Uses window.decorView: window.insetsController
     * before setContentView throws on Android 11+ (the v0.4.0 startup crash).
     */
    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Dark bar icons on light screens, light icons on night screens. */
    @Suppress("DEPRECATION")
    private fun barIcons(lightScreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (lightScreen) mask else 0, mask)
        } else {
            val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (lightScreen) {
                base or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                base
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STEP, step.name)
        outState.putInt(STATE_ONBOARDING, onboardingPage)
        if (restartFlow) outState.putString(STATE_RESTART_STEP, restartStep.name)
    }

    // ---------------- series draft ----------------

    /**
     * «Начать»: a NEW draft starts unless one is already open (the user came
     * back with Back, or the app was recreated): an open draft keeps its values.
     */
    internal fun beginDraft() {
        if (prefs.getBoolean(DRAFT_OPEN, false)) return
        prefs.edit().putBoolean(DRAFT_OPEN, true).putBoolean(DRAFT_TIME_SET, false).apply()
        zone = ZoneId.systemDefault()
    }

    /**
     * A new draft's time starts from now (never the previous series' window),
     * once, as AUTO_NOW; after that the user's choice is kept.
     */
    internal fun ensureDraftTime() {
        // Screenshot tour: sample data only.
        if (preview != null || prefs.getBoolean(DRAFT_TIME_SET, false)) return
        val (s, e) = SeriesDraft.freshTimes(Mama.trustedNow(this), zone)
        start = s
        end = e
        startMode = StartMode.AUTO_NOW
        prefs.edit().putBoolean(DRAFT_TIME_SET, true).apply()
        saveForm()
    }

    /** The draft's window length (start to end, across midnight); zero when they coincide. */
    internal val draftLength: Duration
        get() = Duration.between(start, end).let { if (it.isNegative) it.plusDays(1) else it }

    /** AUTO_NOW: the preview start follows the current minute and the end keeps the chosen length. */
    internal fun refreshAutoNow() {
        if (startMode != StartMode.AUTO_NOW || preview != null) return
        val length = draftLength
        start = SeriesDraft.freshTimes(Mama.trustedNow(this), zone).first
        end = start.plus(length)
        saveForm()
    }

    /** Start time set by hand: from now on a fixed daily window (MANUAL). */
    internal fun pickStart(t: LocalTime) {
        if (t != start) {
            start = t
            startMode = StartMode.MANUAL
        }
        saveForm()
    }

    /** End time set: under AUTO_NOW only the length changes, the start stays "at the confirmation". */
    internal fun pickEnd(t: LocalTime) {
        refreshAutoNow()
        end = t
        saveForm()
    }

    /** What the final confirmation starts; resolved by the platform on trusted time at that moment. */
    internal fun launchTime(): LaunchTime =
        if (startMode == StartMode.AUTO_NOW && start != end) {
            LaunchTime.AutoNow(draftLength, zone)
        } else {
            LaunchTime.Fixed(DailyWindow(start, end, zone))
        }

    /**
     * «Начать заново» on the failure screen: a Restart is a new launch with the
     * same length; its time is a new draft (current time), never the failed
     * series' time. Coming back into the same Restart keeps the draft.
     */
    internal fun beginRestart(kind: SeriesKind) {
        beginDraft()
        choice = Choice.entries.first { it.series == kind }
        commitmentKop = null
        restartFlow = true
        restartStep = Step.TIME
    }

    /** The draft became a series (or test / FLEX): the next one starts fresh. */
    internal fun closeDraft() {
        prefs.edit().putBoolean(DRAFT_OPEN, false).putBoolean(DRAFT_TIME_SET, false).apply()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (setupAllActive) window.decorView.post { continueSetupAll() }
    }

    override fun onStop() {
        stopTicker()
        editSaved = false
        super.onStop()
    }

    @Deprecated("Framework Activity API")
    override fun onBackPressed() {
        when {
            preview != null -> super.onBackPressed()
            pathOpen -> closePath()
            onboardingPage > 0 -> onboardingBack()
            flexPlanning -> { flexPlanning = false; render() }
            restartFlow -> {
                if (restartStep == Step.COMMITMENT) restartStep = Step.TIME else restartFlow = false
                render()
            }
            editing -> back()
            onMotivation != null -> closeMotivation(onMotivation!!)
            tab != 0 && onDashboard -> { tab = 0; render() }
            onWizard && step != Step.WELCOME -> back()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------------- persistence ----------------

    internal fun saveForm() {
        prefs.edit()
            .putString("mode", mode.name)
            .putString("choice", choice.name)
            .putString("start", start.toString())
            .putString("end", end.toString())
            .putString("zone", zone.id)
            .putString(DRAFT_START_MODE, startMode.name)
            .putString("contactName", contactName.text.toString())
            .putString("contactPhone", contactPhone.text.toString())
            .apply()
    }

    // ---------------- routing ----------------

    private var scroll: ScrollView? = null
    private var screenKey: String? = null
    internal var onWizard = false
    internal var onDashboard = false

    /** The series whose motivation screen is shown (Back closes it like "Продолжить"). */
    private var onMotivation: app.mama.core.Series? = null

    /** A night of [series] is done and its motivation screen was not seen yet: show it once. */
    private fun motivationDue(series: app.mama.core.Series): Boolean =
        series.completedPeriods > 0 && prefs.getString(MOTIVATION_SEEN, null) != "${series.id}:${series.completedPeriods}"

    internal fun render() {
        stopTicker()
        detach(contactName)
        detach(contactPhone)
        detach(promiseField)
        preview?.let {
            showScreen(Previews.render(this, it), "preview-$it")
            return
        }
        val state = Mama.sync(this)
        // A series cancelled before it began leaves nothing behind.
        val series = Mama.series(this)?.takeIf { it.status != SeriesStatus.CANCELLED_BEFORE_START }
        if (editing && series?.status != SeriesStatus.SCHEDULED && series?.status != SeriesStatus.NEEDS_ATTENTION) {
            // The first lock has begun meanwhile (or the series is gone): nothing to edit any more.
            editing = false
            if (series != null && !series.beforeCommitment) alert("Серия уже началась", "Блокировка началась, поэтому условия зафиксированы.")
        }
        val flex = Mama.entitlements(this).flex
        val session = Mama.session(this)
        onWizard = false
        onDashboard = false
        onMotivation = null
        val (view, key) = when {
            // "Мой путь" from a screen without the tab bar: the same statistics, with a way back.
            pathOpen -> statisticsScreen(standalone = true) to "path"
            series != null && series.status == SeriesStatus.BROKEN && restartFlow ->
                if (restartStep == Step.COMMITMENT) {
                    commitmentScreen(restart = true, kind = series.kind) to "restartCommitment"
                } else {
                    timeScreen() to "restartTime"
                }
            series != null && series.status == SeriesStatus.BROKEN -> failureScreen(series) to "failure"
            series != null && series.status == SeriesStatus.COMPLETED -> successScreen(series) to "success"
            flex != null && flex.over -> flexResultScreen(flex) to "flexResult"
            flex != null && flexPlanning && flex.currentSessionId == null -> flexPlannerScreen(flex) to "flexPlan"
            editing && series != null -> {
                onWizard = true
                wizardScreen() to "edit-$step"
            }
            series != null && series.status == SeriesStatus.ACTIVE && tab == 0 && (motivationOpen || motivationDue(series)) -> {
                onMotivation = series
                motivationScreen(series) to "motivation"
            }
            series != null || flex != null || (session != null && state != LockState.Free) -> {
                onDashboard = true
                when (tab) {
                    1 -> statisticsScreen() to "stats"
                    2 -> settingsScreen() to "settings"
                    else -> when {
                        series != null -> seriesDashboard(series) { Mama.state(this) } to "dash"
                        flex != null -> flexDashboard(flex) { Mama.state(this) } to "flex"
                        else -> sessionDashboard(session!!) { Mama.state(this) } to "session"
                    }
                }
            }
            // Before the first use: the onboarding pages in front of the setup.
            onboardingPage > 0 -> onboardingScreen(onboardingPage) to "onboarding-$onboardingPage"
            else -> {
                onWizard = true
                wizardScreen() to "wizard-$step"
            }
        }
        showScreen(view, key)
        showReturnNoticeIfAny()
    }

    private fun showScreen(view: Pair<View, Boolean>, key: String) {
        val (content, light) = view
        barIcons(light)
        val keepY = if (key == screenKey) scroll?.scrollY ?: 0 else 0
        content.setOnApplyWindowInsetsListener { v, insets -> padForBars(v, insets) }
        setContentView(content)
        val newScroll = findScroll(content)
        if (keepY > 0) newScroll?.post { newScroll.scrollTo(0, keepY) }
        scroll = newScroll
        screenKey = key
    }

    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    @Suppress("DEPRECATION")
    private fun padForBars(v: View, insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        return insets
    }

    private fun detach(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
    }

    // ---------------- frames ----------------

    /**
     * A screen on the earth page without a nature window: header, scrolling
     * body, sticky bottom action area, navigation ([DesignKit.screen]).
     * Returns (view, light status bar).
     */
    internal fun lightFrame(header: View?, body: View, footer: View?, nav: View? = null): Pair<View, Boolean> =
        design.screen(null, header = header, body = body, cta = footer, nav = nav) to true

    /** A screen with a nature window ([scenery]) over the earth page: header, scrolling body, sticky bottom action, navigation. Light status bar. */
    internal fun v1Frame(
        scenery: Scenery,
        header: View?,
        body: View,
        footer: View?,
        nav: View? = null,
        layout: SceneLayout = SceneLayout.HEADER,
        mood: SceneMood = SceneMood.CALM,
    ): Pair<View, Boolean> = design.screen(scenery, layout, header, body, footer, nav, mood) to true

    internal fun body(paddingTopDp: Int = 18) = design.body(paddingTopDp)

    // ---------------- countdown ticker ----------------

    private val tickHandler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    internal fun startTicker(tick: () -> Unit) {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                tick()
                tickHandler.postDelayed(this, 1_000)
            }
        }
        ticker = r
        r.run()
    }

    private fun stopTicker() {
        ticker?.let(tickHandler::removeCallbacks)
        ticker = null
    }

    // ---------------- "grant everything" wizard ----------------

    private var setupAllActive = false
    private var setupAllScope: List<Requirement> = Requirement.entries
    private var pausedSinceRequest = false
    private val setupAllOffered = mutableSetOf<Requirement>()

    override fun onPause() {
        super.onPause()
        pausedSinceRequest = true
    }

    /** Opens each missing item of [scope] in turn ("Исправить защиту" passes only the strict ones). */
    internal fun startSetupAll(scope: List<Requirement> = Requirement.entries) {
        setupAllScope = scope
        setupAllActive = true
        setupAllOffered.clear()
        continueSetupAll()
    }

    /**
     * Android does not let an app grant these itself: each needs a tap from the
     * user. Runtime permissions are asked in one dialog, then each settings
     * screen opens in turn and returns here. When everything required is
     * granted, the wizard moves on to the next step.
     */
    private fun continueSetupAll() {
        if (!setupAllActive) return
        val missing = setupAllScope.filter { !it.isGranted(this) && it !in setupAllOffered }
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
            val left = setupAllScope.filter { it.required && !it.isGranted(this) }
            if (left.isEmpty()) {
                if (onWizard && step == Step.PERMISSIONS) step = nextStep(Step.PERMISSIONS)
            } else {
                alert("Остались разрешения", left.joinToString("\n") { "• ${it.title}" } + "\n\nНажми «Продолжить» ещё раз.")
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
        if (requestCode == SETUP_ALL && !pausedSinceRequest) continueSetupAll()
    }

    // ---------------- contacts ----------------

    internal fun pickContact() {
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
                manualContact = false
                saveForm()
            }
        }
    }

    internal fun alert(title: String, message: String) {
        BrandDialog.show(this, title, message)
    }

    companion object {
        const val EXTRA_PREVIEW = "mama.preview"
        private const val STATE_STEP = "step"
        private const val STATE_ONBOARDING = "onboarding"
        private const val STATE_RESTART_STEP = "restartStep"
        internal const val DRAFT_OPEN = "draft.open"
        internal const val DRAFT_TIME_SET = "draft.timeSet"
        internal const val DRAFT_START_MODE = "draft.startMode"

        /** "<series id>:<nights done>" whose motivation screen was seen. */
        internal const val MOTIVATION_SEEN = "motivation.seen"

        /** The onboarding was gone through once (a UI flag only). */
        internal const val ONBOARDING_SEEN = "onboarding.seen"
        private const val PICK_CONTACT = 10
        private const val SETUP_ALL = 20
    }
}

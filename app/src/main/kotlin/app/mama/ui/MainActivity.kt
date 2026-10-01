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
import app.mama.core.LockState
import app.mama.core.SeriesKind
import app.mama.core.SeriesStatus
import app.mama.core.SessionMode
import app.mama.platform.Mama
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

    enum class Step { WELCOME, PERMISSIONS, SERIES, TIME, CONTACT, PROMISE, REVIEW }

    internal val prefs by lazy { getSharedPreferences("mama_setup", Context.MODE_PRIVATE) }
    internal val kit by lazy { Kit(this) }
    internal val nightKit by lazy { Kit(this, night = true) }

    internal var mode = SessionMode.SLEEP
    internal var choice = Choice.SEVEN
    internal var start: LocalTime = LocalTime.of(23, 0)
    internal var end: LocalTime = LocalTime.of(7, 0)
    internal var zone: ZoneId = ZoneId.systemDefault()
    internal lateinit var contactName: EditText
    internal lateinit var contactPhone: EditText
    internal lateinit var promiseField: EditText
    internal var manualContact = false
    internal var agreed = false

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
        start = runCatching { LocalTime.parse(prefs.getString("start", null)) }.getOrDefault(start)
        end = runCatching { LocalTime.parse(prefs.getString("end", null)) }.getOrDefault(end)
        zone = runCatching { ZoneId.of(prefs.getString("zone", null)) }.getOrDefault(zone)
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

    override fun onResume() {
        super.onResume()
        render()
        if (setupAllActive) window.decorView.post { continueSetupAll() }
    }

    override fun onStop() {
        stopTicker()
        super.onStop()
    }

    @Deprecated("Framework Activity API")
    override fun onBackPressed() {
        when {
            preview != null -> super.onBackPressed()
            flexPlanning -> { flexPlanning = false; render() }
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
            .putString("contactName", contactName.text.toString())
            .putString("contactPhone", contactPhone.text.toString())
            .apply()
    }

    // ---------------- routing ----------------

    private var scroll: ScrollView? = null
    private var screenKey: String? = null
    internal var onWizard = false
    internal var onDashboard = false

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
        val series = Mama.series(this)
        val flex = Mama.entitlements(this).flex
        val session = Mama.session(this)
        onWizard = false
        onDashboard = false
        val (view, key) = when {
            series != null && series.status == SeriesStatus.BROKEN -> failureScreen(series) to "failure"
            series != null && series.status == SeriesStatus.COMPLETED -> successScreen(series) to "success"
            flex != null && flex.over -> flexResultScreen(flex) to "flexResult"
            flex != null && flexPlanning && flex.currentSessionId == null -> flexPlannerScreen(flex) to "flexPlan"
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
            else -> {
                onWizard = true
                wizardScreen() to "wizard-$step"
            }
        }
        showScreen(view, key)
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
     * Light wizard frame: step header, scrolling body, sticky bottom action
     * area (reference screens 2–6). Returns (view, light status bar).
     */
    internal fun lightFrame(header: View?, body: View, footer: View?, nav: View? = null): Pair<View, Boolean> {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MamaColors.WarmIvory)
        }
        header?.let { root.addView(it, kit.fill().apply { leftMargin = kit.dp(12); rightMargin = kit.dp(16) }) }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        footer?.let {
            root.addView(it, kit.fill().apply {
                leftMargin = kit.dp(20); rightMargin = kit.dp(20); topMargin = kit.dp(8); bottomMargin = kit.dp(14)
            })
        }
        nav?.let { root.addView(it, kit.fill()) }
        return root to true
    }

    /** Night frame: landscape behind everything (reference screens 1, 7, 8, 10). */
    internal fun nightFrame(scene: Scene, body: View, footer: View? = null, nav: View? = null): Pair<View, Boolean> {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = PhotoBackground(this@MainActivity, scene)
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        footer?.let {
            root.addView(it, kit.fill().apply {
                leftMargin = kit.dp(20); rightMargin = kit.dp(20); topMargin = kit.dp(8); bottomMargin = kit.dp(16)
            })
        }
        nav?.let { root.addView(it, kit.fill()) }
        return root to false
    }

    internal fun body(paddingTopDp: Int = 18) = kit.column().apply {
        setPadding(kit.dp(20), kit.dp(paddingTopDp), kit.dp(20), kit.dp(24))
    }

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
    private var pausedSinceRequest = false
    private val setupAllOffered = mutableSetOf<Requirement>()

    override fun onPause() {
        super.onPause()
        pausedSinceRequest = true
    }

    internal fun startSetupAll() {
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
                if (onWizard && step == Step.PERMISSIONS) step = nextStep(Step.PERMISSIONS)
            } else {
                alert("Остались разрешения", left.joinToString("\n") { "• ${it.title}" } + "\n\nНажмите «Продолжить» ещё раз.")
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
        private const val PICK_CONTACT = 10
        private const val SETUP_ALL = 20
    }
}

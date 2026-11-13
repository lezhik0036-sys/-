package app.mama.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Toast
import app.mama.family.FamilyClient
import app.mama.family.MamaFamily
import app.mama.ui.design.CircleStyle
import app.mama.ui.design.Palette
import app.mama.ui.design.Typo

/**
 * The MAMA Family screen: a parent master account that pairs a child device, and a
 * child device that asks to be paired. It is a thin face over [FamilyClient]; ALL
 * authority is the server's. This screen never sets a parent/child role flag, never
 * grants a BRICK bypass, and never fabricates a link — the server decides the
 * relationship when it accepts a device-key proof and a one-time invitation.
 *
 * Fail-closed: when there is no configured backend, or the device cannot hold the
 * Ed25519 device key (API < 33), [MamaFamily.isConfigured] is false and the screen
 * shows an honest "unavailable" state with NO pairing action — there is no local
 * pairing path. Every server call runs OFF the main thread (network + Keystore
 * signing) and only the result is applied on the UI thread.
 *
 * A no-SIM Wi-Fi tablet pairs through exactly the same HTTP flow: the whole
 * credential is the device key, so nothing here needs telephony.
 */

/** Stable view tags (tests + the welcome entry link). */
internal const val FAMILY_SCREEN = "family_screen"
internal const val FAMILY_ENTRY = "family_entry"
internal const val FAMILY_UNAVAILABLE = "family_unavailable"
internal const val FAMILY_PARENT_LINK = "family_parent_link"
internal const val FAMILY_CHILD_CREATE = "family_child_create"
internal const val FAMILY_CHILD_CODE = "family_child_code"

/**
 * Transient UI state of the Family screen. It is NOT a role or privilege flag: it
 * holds only what the last server round-trip produced (a status line, a created
 * invitation code to read out, the parent's linked devices). Reset on every open.
 */
internal class FamilyUi {
    /** A family server call is in flight; actions are disabled and a quiet note shows. */
    var busy = false
    /** The last status line under the actions. */
    var message: String? = null
    /** Tone of [message]: olive (positive) when true, clay (caution) when false. */
    var good = false
    /** CHILD: the single-use invitation code to read to the parent, once created. */
    var invitation: String? = null
    /** PARENT: the linked child devices, loaded lazily; null until first loaded. */
    var devices: List<FamilyClient.LinkedDevice>? = null
}

/** Open the Family screen fresh — never carry a previous session's code or status. */
internal fun MainActivity.openFamily() {
    family.busy = false
    family.message = null
    family.invitation = null
    family.devices = null
    familyOpen = true
    render()
}

internal fun MainActivity.closeFamily() {
    familyOpen = false
    render()
}

internal fun MainActivity.familyScreen(): Pair<View, Boolean> {
    val d = design
    val b = body(22)
    b.addView(d.title("Семья"))
    b.addView(
        d.text(
            "Родительский мастер-аккаунт и привязка детского устройства. Роль родителя или ребёнка выдаёт сервер при привязке — в приложении нет локального переключателя «стать родителем».",
            Typo.BODY, Palette.InkMuted,
        ),
        d.gap(8),
    )

    // Fail-closed: without a real server (and a platform that can hold the device
    // key) there is no pairing path at all — only an honest "unavailable" card.
    if (!MamaFamily.isConfigured) {
        b.addView(familyUnavailableCard(), d.gap(22))
        return lightFrame(d.topBar({ closeFamily() }), b, null).also { it.first.tag = FAMILY_SCREEN }
    }

    // ---- PARENT: link a child device by its one-time code ----
    b.addView(d.text("Родитель", Typo.HEADLINE), d.gap(30))
    val parent = d.card(paddingDp = 18)
    parent.addView(headRow(Icon.SHIELD, "Подключить детское устройство", "Введите код, который показывает детское устройство"))
    val codeField = d.textField("Код от детского устройства", InputType.TYPE_CLASS_TEXT)
    parent.addView(codeField, d.gap(14))
    parent.addView(
        d.primaryButton("Привязать устройство") {
            val code = codeField.text.toString().trim()
            if (code.isEmpty()) {
                family.message = "Введите код, который показывает детское устройство."
                family.good = false
                render()
            } else {
                familyConsume(code)
            }
        }.apply { tag = FAMILY_PARENT_LINK; isEnabled = !family.busy },
        d.gap(12),
    )
    parent.addView(
        d.secondaryButton("Показать привязанные устройства", Icon.REFRESH) { familyLoadDevices() }
            .apply { isEnabled = !family.busy },
        d.gap(10),
    )
    family.devices?.let { parent.addView(devicesList(it), d.gap(14)) }
    b.addView(parent, d.gap(12))

    // ---- CHILD: become a child device (a one-time code to read to the parent) ----
    b.addView(d.text("Ребёнок", Typo.HEADLINE), d.gap(30))
    val child = d.card(paddingDp = 18)
    child.addView(headRow(Icon.PHONE, "Сделать это устройство детским", "Создайте код и продиктуйте его родителю"))
    child.addView(
        d.text("Работает и на Wi-Fi планшете без SIM — привязка идёт по ключу устройства, а не по номеру.", Typo.BODY, Palette.InkMuted),
        d.gap(10),
    )
    child.addView(
        d.primaryButton("Создать код привязки") { familyCreateInvitation() }
            .apply { tag = FAMILY_CHILD_CREATE; isEnabled = !family.busy },
        d.gap(14),
    )
    family.invitation?.let { child.addView(childCodeCard(it), d.gap(14)) }
    child.addView(
        d.linkButton("Отвязать это устройство от родителя") { confirmDetachThisDevice() },
        d.gap(10),
    )
    b.addView(child, d.gap(12))

    val status = when {
        family.busy -> d.text("Соединяемся с сервером…", Typo.LABEL, Palette.InkMuted, center = true)
        family.message != null -> d.text(family.message!!, Typo.LABEL, if (family.good) Palette.OliveInk else Palette.ClayInk, center = true)
        else -> null
    }
    status?.let { b.addView(it, d.gap(18)) }

    return lightFrame(d.topBar({ closeFamily() }), b, null).also { it.first.tag = FAMILY_SCREEN }
}

/** The honest fail-closed card: a real server and a key-capable device are required; no local path. */
private fun MainActivity.familyUnavailableCard(): View = design.card(paddingDp = 18).apply {
    tag = FAMILY_UNAVAILABLE
    addView(design.iconCircle(Icon.SHIELD, 48, CircleStyle.SAND))
    addView(design.text("Семья пока недоступна", Typo.VALUE), design.gap(12))
    addView(
        design.text(
            "Для привязки нужен настоящий сервер MAMA и устройство, которое умеет надёжно хранить ключ доступа (Android 13+). Локальной привязки без сервера нет — это защита от обхода родительского контроля.",
            Typo.BODY, Palette.InkMuted,
        ),
        design.gap(8),
    )
}

/** Icon-circle + title + subtitle header for a section card. */
private fun MainActivity.headRow(icon: Icon, title: String, subtitle: String): View = design.row().apply {
    gravity = Gravity.TOP
    addView(design.iconCircle(icon, 46, CircleStyle.MINT))
    val col = design.column()
    col.addView(design.text(title, Typo.VALUE))
    col.addView(design.text(subtitle, Typo.LABEL, Palette.InkMuted), design.gap(3))
    addView(col, design.weight().apply { leftMargin = design.dp(14) })
}

/** The parent's linked child devices, each with a revoke action. */
private fun MainActivity.devicesList(devices: List<FamilyClient.LinkedDevice>): View = design.column().apply {
    if (devices.isEmpty()) {
        addView(design.text("Пока нет привязанных устройств.", Typo.LABEL, Palette.InkMuted))
        return@apply
    }
    addView(design.text("Привязанные устройства", Typo.LABEL, Palette.InkMuted))
    devices.forEach { dev ->
        val row = design.row().apply { gravity = Gravity.CENTER_VERTICAL }
        val col = design.column()
        col.addView(design.text("Детское устройство", Typo.LABEL))
        col.addView(design.text(shortId(dev.childDeviceId), Typo.CAPTION, Palette.InkSoft), design.gap(2))
        row.addView(col, design.weight())
        row.addView(design.linkButton("Отвязать", Palette.ClayInk) { confirmRevoke(dev.childDeviceId) })
        addView(row, design.gap(12))
    }
}

/** The created invitation code, shown for the parent to enter (copy for convenience). */
private fun MainActivity.childCodeCard(code: String): View = design.card(paddingDp = 16, fill = Palette.CardQuiet).apply {
    tag = FAMILY_CHILD_CODE
    addView(design.text("Код для родителя", Typo.LABEL, Palette.InkMuted))
    addView(design.text(code, Typo.VALUE, Palette.Ink).apply { setTextIsSelectable(true) }, design.gap(6))
    addView(
        design.secondaryButton("Скопировать код", Icon.CHECK) {
            copyToClipboard("MAMA invitation", code)
            Toast.makeText(this@childCodeCard, "Код скопирован", Toast.LENGTH_SHORT).show()
        },
        design.gap(12),
    )
    addView(
        design.text("Код действует один раз. Родитель вводит его в разделе «Родитель».", Typo.CAPTION, Palette.InkMuted),
        design.gap(10),
    )
}

// ---------------- server calls (off the main thread; result applied on the UI thread) ----------------

/**
 * Run [call] against the real family client OFF the main thread, then apply
 * [onResult] on the UI thread with the (immutable) result. A null client (fail-closed:
 * unconfigured or no device key) or a thrown error yields an honest message and never
 * a forged outcome. Guarded so a second tap cannot start a duplicate request.
 */
private fun <T> MainActivity.runFamily(call: (FamilyClient) -> T, onResult: FamilyUi.(T?) -> Unit) {
    if (family.busy) return
    family.busy = true
    family.message = null
    render()
    Thread {
        val client = MamaFamily.client()
        val hadClient = client != null
        val result: T? = if (client == null) null else runCatching { call(client) }.getOrNull()
        runOnUiThread {
            family.busy = false
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (!hadClient) {
                family.message = "Семья недоступна на этом устройстве."
                family.good = false
            } else {
                family.onResult(result)
            }
            render()
        }
    }.start()
}

private fun MainActivity.familyCreateInvitation() = runFamily({ it.createInvitation() }) { r ->
    when (r) {
        is FamilyClient.InviteResult.Created -> {
            invitation = r.invitationToken
            message = "Код создан. Продиктуйте его родителю — он действует один раз."
            good = true
        }
        else -> {
            message = "Не удалось создать код. Проверьте вход в аккаунт и соединение."
            good = false
        }
    }
}

private fun MainActivity.familyConsume(code: String) = runFamily({ it.consumeInvitation(code) }) { r ->
    when (r) {
        is FamilyClient.LinkResult.Linked -> {
            message = "Детское устройство привязано."
            good = true
            devices = null // force a reload to reflect the new link
        }
        FamilyClient.LinkResult.ConsentUnavailable -> {
            message = "Согласие опекуна пока не настроено на сервере — привязка не выполнена. На этом этапе это ожидаемо."
            good = false
        }
        else -> {
            message = "Не удалось привязать. Проверьте код и соединение."
            good = false
        }
    }
}

private fun MainActivity.familyLoadDevices() = runFamily({ it.listDevices() }) { r ->
    when (r) {
        is FamilyClient.DevicesResult.Ok -> {
            devices = r.devices
            message = if (r.devices.isEmpty()) "Пока нет привязанных устройств." else null
            good = true
        }
        else -> {
            message = "Не удалось получить список устройств."
            good = false
        }
    }
}

private fun MainActivity.familyRevoke(childDeviceId: String) = runFamily({ it.revokeChildDevice(childDeviceId) }) { ok ->
    if (ok == true) {
        devices = devices?.filterNot { it.childDeviceId == childDeviceId }
        message = "Привязка удалена."
        good = true
    } else {
        message = "Не удалось удалить привязку."
        good = false
    }
}

private fun MainActivity.familyDetachThisDevice() = runFamily(
    { client -> client.thisDeviceId()?.let { client.revokeParentLinkAsChild(it) } },
) { r ->
    when (r) {
        FamilyClient.ParentRevokeResult.Revoked -> {
            message = "Это устройство отвязано от родителя."
            good = true
        }
        FamilyClient.ParentRevokeResult.RecentAuthRequired -> {
            message = "Для отвязки нужно заново войти — так сервер защищает от случайной отвязки."
            good = false
        }
        null -> {
            // No device id (no active session) or a transport error — both fail-closed.
            message = "Не удалось отвязать: нет активной сессии или связи с сервером."
            good = false
        }
        else -> {
            message = "Не удалось отвязать. Попробуйте позже."
            good = false
        }
    }
}

// ---------------- confirmations ----------------

private fun MainActivity.confirmRevoke(childDeviceId: String) {
    BrandDialog.show(
        this,
        "Отвязать устройство?",
        "Детское устройство перестанет быть привязанным к этому родителю.",
        confirm = "Отвязать",
        cancel = "Отмена",
        onConfirm = { familyRevoke(childDeviceId); true },
    )
}

private fun MainActivity.confirmDetachThisDevice() {
    BrandDialog.show(
        this,
        "Отвязать это устройство?",
        "Это устройство перестанет быть привязанным к родителю. Это НЕ снимает активную блокировку BRICK и не укорачивает серию. Сервер может попросить заново войти.",
        confirm = "Отвязать",
        cancel = "Отмена",
        onConfirm = { familyDetachThisDevice(); true },
    )
}

// ---------------- small helpers ----------------

private fun MainActivity.copyToClipboard(label: String, text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** A readable short form of a long device UUID for the linked-devices list. */
private fun shortId(id: String): String = if (id.length <= 14) id else id.take(8) + "…" + id.takeLast(4)

package app.mama.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mama.account.FamilyClient
import app.mama.account.MamaAccount
import java.util.concurrent.Executors

/** The two roles share the existing device-key account, over HTTPS only. */
class FamilyActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var invitation: EditText
    private lateinit var links: LinearLayout
    private val actions = mutableListOf<Button>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        label("Семья").textSize = 24f
        label("Подключение работает по Wi‑Fi. Для привязки требуется проверка полномочий и согласий.")
        status = label("")
        details = label("").apply { setTextIsSelectable(true) }
        button("Войти или создать аккаунт") {
            runFamily({ identity() }) {
                details.text = "Аккаунт: ${it.userId}\nУстройство: ${it.deviceId}"
                status.text = "Передайте эти идентификаторы проверяющему через согласованный канал."
            }
        }
        label("На устройстве родителя").textSize = 20f
        label("Попросите владельца детского устройства создать приглашение после проверки согласий.")
        invitation = EditText(this).apply {
            hint = "Код приглашения"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(43))
            isSingleLine = true
        }
        content.addView(invitation)
        button("Подключить детское устройство") {
            val code = invitation.text.toString().trim()
            runFamily({ link(code) }) { child ->
                invitation.text.clear()
                status.text = "Устройство подключено: $child"
            }
        }
        button("Проверить подключённые устройства") { refreshLinks() }
        links = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(links)
        label("На детском устройстве").textSize = 20f
        button("Создать приглашение") {
            confirm("Разрешить привязку?", "Создайте приглашение только для проверенного родителя. Оно действует пять минут.") {
                runFamily({ invite() }) {
                    details.text = it.code
                    status.text = "Приглашение готово. Передайте код только проверенному родителю."
                }
            }
        }
        button("Отозвать связь с родителем") {
            confirm("Отозвать связь?", "Родитель потеряет доступ к записи об этом устройстве.") {
                runFamily({ revokeOwnParent() }) { status.text = "Связь отозвана." }
            }
        }
        if (!MamaAccount.isConfigured) {
            status.text = "Семейная привязка требует Android 13 или новее и настроенный сервер MAMA."
            actions.forEach { it.isEnabled = false }
        }
    }

    private fun refreshLinks() {
        runFamily({ devices() }) { devices ->
            for (index in 0 until links.childCount) (links.getChildAt(index) as? Button)?.let { actions.remove(it) }
            links.removeAllViews()
            devices.forEach { id ->
                button("Отвязать устройство $id", links) {
                    confirm("Отвязать устройство?", "Связь с этим устройством будет отозвана.") {
                        runFamily({ revoke(id) }) {
                            status.text = "Связь отозвана."
                            refreshLinks()
                        }
                    }
                }
            }
            status.text = if (devices.isEmpty()) "Подключённых устройств нет." else "Устройства проверены."
        }
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        content.addView(this)
    }

    private fun button(value: String, parent: LinearLayout = content, action: () -> Unit) {
        val button = Button(this).apply {
            text = value
            setOnClickListener { action() }
        }
        actions.add(button)
        parent.addView(button)
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подтвердить") { _, _ -> action() }.show()
    }

    private fun <T> runFamily(operation: FamilyClient.() -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        actions.forEach { it.isEnabled = false }
        status.text = "Обращаемся к серверу…"
        worker.execute {
            val result = runCatching {
                val client = MamaAccount.familyClient() ?: throw FamilyClient.Denied(0)
                client.operation()
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                actions.forEach { it.isEnabled = true }
                result.fold(success) { error ->
                    status.text = when ((error as? FamilyClient.Denied)?.status) {
                        404 -> "Связь или приглашение недоступны. Проверьте срок, код и согласия."
                        403 -> "Приглашение недоступно. Если вы уже запрашивали код, дождитесь истечения пяти минут."
                        503 -> "Проверка полномочий и согласий временно недоступна."
                        else -> "Результат не подтверждён. Восстановите соединение и проверьте список устройств перед повтором."
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}

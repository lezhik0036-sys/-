package app.mama.ui

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.mama.lock.GuardService
import app.mama.platform.Alarms
import app.mama.platform.MamaDeviceAdmin

/** Everything the strict lock depends on, with a way to grant each item. */
enum class Requirement(val title: String, val why: String, val required: Boolean, val icon: Icon) {
    OVERLAY("Поверх других приложений", "Экран MAMA закрывает всё остальное во время блокировки.", true, Icon.LAYERS),
    GUARD("Специальные возможности", "Не даёт открыть шторку, «Недавние» и настройки во время блокировки.", true, Icon.ACCESSIBILITY),
    ADMIN("Администратор устройства", "MAMA нельзя удалить, пока идёт блокировка.", true, Icon.SHIELD),
    SMS("Телефон, SMS и звонки", "Код для выхода приходит доверенному контакту. Звонки работают с экрана блокировки.", true, Icon.PHONE),
    EXACT_ALARMS("Будильники и напоминания", "Блокировка начинается и заканчивается точно вовремя.", true, Icon.ALARM),
    BATTERY("Работа без ограничений батареи", "Система не усыпит MAMA ночью.", false, Icon.BATTERY),
    NOTIFICATIONS("Уведомления", "Напомним, когда блокировка начнётся и закончится.", false, Icon.BELL),
    ;

    fun isGranted(context: Context): Boolean = when (this) {
        OVERLAY -> Settings.canDrawOverlays(context)
        GUARD -> {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val me = ComponentName(context, GuardService::class.java)
            // Switched on in Settings is not enough: it must actually be running.
            enabled.split(':').any { ComponentName.unflattenFromString(it) == me } && GuardService.running
        }
        ADMIN -> context.getSystemService(DevicePolicyManager::class.java)!!
            .isAdminActive(ComponentName(context, MamaDeviceAdmin::class.java))
        SMS -> smsPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        EXACT_ALARMS -> Alarms.canScheduleExact(context)
        BATTERY -> context.getSystemService(PowerManager::class.java)!!
            .isIgnoringBatteryOptimizations(context.packageName)
        NOTIFICATIONS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun request(activity: Activity) {
        val pkg = Uri.parse("package:${activity.packageName}")
        when (this) {
            OVERLAY -> activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            GUARD -> activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            ADMIN -> activity.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(activity, MamaDeviceAdmin::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Так MAMA нельзя будет удалить во время блокировки."),
            )
            SMS -> activity.requestPermissions(smsPermissions, 1)
            EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            }
            BATTERY -> activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
    }

    /** Runtime permissions of this item (asked in one system dialog), or empty for a settings screen. */
    val runtimePermissions: Array<String>
        get() = when (this) {
            SMS -> smsPermissions
            NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
            else -> emptyArray()
        }

    /** What to do on the settings screen this item opens (shown as a hint). */
    val hint: String?
        get() = when (this) {
            OVERLAY -> "Включите «MAMA» в списке."
            GUARD -> "Установленные приложения → «MAMA — защита блокировки» → включить. " +
                "Если серое: Приложения → MAMA → ⋮ → «Разрешить ограниченные настройки»."
            ADMIN -> "Нажмите «Активировать»."
            EXACT_ALARMS -> "Включите разрешение для MAMA."
            BATTERY -> "Нажмите «Разрешить»."
            else -> null
        }

    companion object {
        private val smsPermissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
        )

        fun missingRequired(context: Context) = entries.filter { it.required && !it.isGranted(context) }
    }
}

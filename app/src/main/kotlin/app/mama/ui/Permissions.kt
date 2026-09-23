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
enum class Requirement(val title: String, val why: String, val required: Boolean) {
    OVERLAY("Поверх других приложений", "Экран блокировки закрывает всё остальное.", true),
    GUARD(
        "Специальные возможности: MAMA",
        "Закрывает шторку и настройки во время блокировки. " +
            "Если пункт неактивен (Android 13+): О приложении → ⋮ → «Разрешить доступ к ограниченным настройкам».",
        true,
    ),
    ADMIN("Администратор устройства", "Нельзя удалить MAMA во время блокировки.", true),
    SMS("SMS и звонки", "Код уходит контакту по SMS; звонки принимаются и завершаются прямо с экрана блокировки.", true),
    EXACT_ALARMS("Будильники и напоминания", "Блокировка начинается и заканчивается точно вовремя.", true),
    BATTERY("Без ограничений батареи", "Система не усыпит MAMA ночью.", false),
    NOTIFICATIONS("Уведомления", "Статус блокировки в шторке.", false),
    ;

    fun isGranted(context: Context): Boolean = when (this) {
        OVERLAY -> Settings.canDrawOverlays(context)
        GUARD -> {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val me = ComponentName(context, GuardService::class.java)
            enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
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

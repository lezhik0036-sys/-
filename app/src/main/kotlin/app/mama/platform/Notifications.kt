package app.mama.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.mama.ui.MainActivity

object Notifications {
    const val CHANNEL = "lock"
    const val LOCK_ID = 1

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Блокировка", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывается, пока идёт блокировка MAMA"
                setShowBadge(false)
            },
        )
    }

    const val BALANCE_CHANNEL = "balance"
    private const val BALANCE_ID = 2

    /** "Часть суммы вернулась на баланс MAMA" (the 24-hour partial return of "Цена слова"). */
    fun balanceReturn(context: Context, amountKop: Long) {
        if (amountKop <= 0) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)!!
            nm.createNotificationChannel(
                NotificationChannel(BALANCE_CHANNEL, "Баланс MAMA", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Возвраты на внутренний баланс MAMA"
                },
            )
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                BALANCE_ID,
                Notification.Builder(context, BALANCE_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle("Часть суммы вернулась на баланс MAMA")
                    .setContentText("${app.mama.ui.Texts.money(amountKop)} доступны внутри приложения.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    fun lock(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("MAMA")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}

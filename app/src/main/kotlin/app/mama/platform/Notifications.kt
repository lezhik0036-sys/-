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

    /** "MAMA вернула тебе …": the partial return of "Цена слова", only once it is credited (never announced before). */
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
                    .setContentTitle(app.mama.ui.returnTitle(amountKop))
                    .setContentText(app.mama.ui.RETURN_TEXT)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private const val SERIES_CHANNEL = "series"
    private const val ATTENTION_ID = 3

    /** NEEDS_ATTENTION: the start time came but strict protection could not be switched on. */
    fun seriesNotStarted(context: Context) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)!!
            nm.createNotificationChannel(
                NotificationChannel(SERIES_CHANNEL, "Серия", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Когда серия не смогла начаться"
                },
            )
            val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                ATTENTION_ID,
                Notification.Builder(context, SERIES_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle("Серия не началась")
                    .setContentText("MAMA не смогла включить защиту.")
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

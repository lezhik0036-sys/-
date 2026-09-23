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

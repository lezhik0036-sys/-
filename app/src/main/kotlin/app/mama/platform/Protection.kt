package app.mama.platform

import android.content.Context
import android.os.UserManager
import app.mama.ui.Requirement

/**
 * strictProtectionReady: the single definition of "the strict lock really
 * works". A series becomes ACTIVE only while it holds. It contains only the
 * critical mechanisms, from the app's existing checks ([Requirement.strict]):
 * - the lock screen is up, run by the foreground lock service;
 * - "Поверх других приложений": the lock screen covers every app;
 * - the guard ("Специальные возможности") is running: no shade, quick
 *   settings, recents or Settings during the lock;
 * - device administrator: MAMA cannot be uninstalled during the lock;
 * - phone and SMS: Trusted Exit works (the code reaches the contact), so a
 *   binding series always has its way out;
 * - "Контроль исходящих звонков" (call redirection role, Android 10+): during
 *   the lock only emergency numbers and the trusted contact can be called, so
 *   the dialer of an emergency call is no way around the lock.
 * Exact alarms, battery and notifications are not part of it: a lock that is
 * already running does not depend on them.
 */
object Protection {
    val strict: List<Requirement> get() = Requirement.entries.filter { it.strict }

    /** Mandatory items switched off in settings (also readable before the first unlock). */
    fun missingConfiguration(context: Context): List<Requirement> = strict.filter { !it.isConfigured(context) }

    /** Mandatory items not working right now (the guard must actually run). */
    fun missing(context: Context): List<Requirement> = strict.filter { !it.isGranted(context) }

    fun strictProtectionReady(context: Context, lockScreenShown: Boolean, lockServiceInForeground: Boolean): Boolean =
        lockScreenShown && lockServiceInForeground && missing(context).isEmpty()

    /** The phone was unlocked at least once since boot; before that the guard cannot run. */
    fun userUnlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
}

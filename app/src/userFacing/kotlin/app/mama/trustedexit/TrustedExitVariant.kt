package app.mama.trustedexit

import android.content.Context

internal object TrustedExitVariant {
    fun initialBackend(): TrustedExitBackend = ReleaseTrustedExitDisabled
    fun localBackend(context: Context): TrustedExitBackend = ReleaseTrustedExitDisabled
    fun probe(): Boolean = false
}

object TestCodeSink {
    fun codeFor(challengeId: String?): String? = null
    fun clear() = Unit
}

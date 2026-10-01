package app.mama.billing

import android.app.Activity
import app.mama.core.SeriesKind

/**
 * What MAMA can sell. Nothing here ever unlocks a phone: leaving a lock with
 * the trusted contact's code is always free. No subscriptions, no ads.
 */
sealed interface Product {
    /** Immediate Restart of a broken 3/5/7-day series. */
    data class Restart(val kind: SeriesKind) : Product {
        init {
            require(kind.restartPriceRub != null) { "$kind has no Restart" }
        }
    }

    /** One-off FLEX package (7 periods within 30 days), not auto-renewed. */
    data object FlexPackage : Product

    val priceRub: Int
        get() = when (this) {
            is Restart -> kind.restartPriceRub!!
            FlexPackage -> SeriesKind.FLEX.packagePriceRub!!
        }
}

sealed interface PurchaseResult {
    /** Paid and confirmed by the store. Only then may the product be granted. */
    data object Paid : PurchaseResult
    data object Cancelled : PurchaseResult
    data class Failed(val reason: String) : PurchaseResult

    /** Payments are not available in this build. */
    data object Unavailable : PurchaseResult
}

interface Payments {
    val available: Boolean
    fun purchase(activity: Activity, product: Product, onResult: (PurchaseResult) -> Unit)
}

/** Feature flags for monetisation. */
object FeatureFlags {
    /**
     * TODO(payments): set to true once a real store integration (Google Play
     * Billing, one-time in-app products "restart_3", "restart_5", "restart_7",
     * "flex_package") is implemented in a [Payments] subclass.
     */
    const val PAYMENTS_ENABLED = false
}

/** Used until a real integration exists: never pretends a payment succeeded. */
object NoPayments : Payments {
    override val available = false
    override fun purchase(activity: Activity, product: Product, onResult: (PurchaseResult) -> Unit) {
        onResult(PurchaseResult.Unavailable)
    }
}

object Billing {
    // TODO(payments): return the real implementation when FeatureFlags.PAYMENTS_ENABLED.
    val payments: Payments = NoPayments
}

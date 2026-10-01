package app.mama.billing

import android.app.Activity
import app.mama.core.GrantSource
import app.mama.core.SeriesKind
import app.mama.ui.BrandDialog

/**
 * What MAMA can sell. Nothing here ever unlocks a phone: Trusted Exit (the
 * contact's code) is always free. No subscriptions, no ads, no auto-renewal.
 */
sealed interface Product {
    /** Immediate Restart of a failed 3/5/7-day series. */
    data class Restart(val kind: SeriesKind) : Product

    /** One-off FLEX package: 7 periods on any days within 30 days. */
    data object FlexPackage : Product

    val priceRub: Int
        get() = when (this) {
            is Restart -> kind.restartPriceRub
            FlexPackage -> app.mama.core.FlexPackage.PRICE_RUB
        }
}

sealed interface PurchaseResult {
    /** Paid and confirmed by the store. Only then may a paid product be granted. */
    data object Paid : PurchaseResult
    data object Cancelled : PurchaseResult
    data class Failed(val reason: String) : PurchaseResult
    data object Unavailable : PurchaseResult
}

/** A store integration (Google Play Billing, RuStore, …) implements this later. */
interface Payments {
    val available: Boolean
    fun purchase(activity: Activity, product: Product, onResult: (PurchaseResult) -> Unit)
}

object FeatureFlags {
    /**
     * Real payments are paused for the test version. TODO(payments): plug a
     * store [Payments] implementation in [Billing] and switch this on.
     */
    const val PAYMENTS_ENABLED = false

    /**
     * Test builds only: products can be activated for testing WITHOUT payment.
     * Shown to the user as a test activation, never as a successful payment.
     * Must be false in any build with real payments.
     */
    const val TEST_GRANTS_ENABLED = true

    /**
     * Free 15-minute test mode, only while MAMA is being tested. Not a series,
     * not FLEX, no payment and no Restart. TODO(release): decide whether to keep it.
     */
    const val TEST_MODE_ENABLED = true
}

/** Used while payments are paused: never reports a successful payment. */
object NoPayments : Payments {
    override val available = false
    override fun purchase(activity: Activity, product: Product, onResult: (PurchaseResult) -> Unit) {
        onResult(PurchaseResult.Unavailable)
    }
}

object Billing {
    val payments: Payments = NoPayments
}

/**
 * The single way the UI obtains a product. Calls [onGranted] with where the
 * grant came from: a confirmed payment, or a clearly labelled test grant.
 */
object Checkout {
    fun obtain(activity: Activity, product: Product, title: String, onGranted: (GrantSource) -> Unit) {
        val payments = Billing.payments
        if (FeatureFlags.PAYMENTS_ENABLED && payments.available) {
            payments.purchase(activity, product) { result ->
                when (result) {
                    PurchaseResult.Paid -> onGranted(GrantSource.PAID)
                    PurchaseResult.Cancelled -> Unit
                    is PurchaseResult.Failed -> info(activity, "Оплата не прошла", result.reason)
                    PurchaseResult.Unavailable -> info(activity, "Оплата недоступна", "Попробуйте позже.")
                }
            }
            return
        }
        if (FeatureFlags.TEST_GRANTS_ENABLED) {
            BrandDialog.show(
                activity,
                "$title — ${product.priceRub} ₽",
                message = "Тестовая версия: оплата не подключена и не проводится.\n\n" +
                    "Можно активировать для проверки без оплаты. В рабочей версии здесь будет оплата " +
                    "${product.priceRub} ₽.",
                confirm = "Активировать для теста",
                cancel = "Отмена",
            ) {
                onGranted(GrantSource.TEST_NO_PAYMENT)
                true
            }
            return
        }
        info(activity, "Оплата пока недоступна", "В этой версии оплата не подключена.")
    }

    private fun info(activity: Activity, title: String, message: String) {
        BrandDialog.show(activity, title, message)
    }
}

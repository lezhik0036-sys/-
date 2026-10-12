package app.mama.billing

import android.app.Activity
import app.mama.core.GrantSource
import app.mama.core.SeriesKind
import app.mama.platform.Mama
import app.mama.ui.BrandDialog
import app.mama.ui.Kit
import app.mama.ui.Texts

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

    /**
     * Internal MAMA balance and "Цена слова" (pilot). Internal only: no
     * withdrawal, no cash-out, no transfers. TODO(legal/billing): review
     * before any real money is accepted.
     */
    const val WALLET_ENABLED = true

    /** Pilot only: test credits can be added to the balance. Never real money. */
    const val WALLET_TEST_TOPUP_ENABLED = true

    /** Pilot: Restart and FLEX may be paid from the internal balance. */
    const val WALLET_SPEND_ENABLED = true
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
    /**
     * [reserveAheadKop]: balance that must stay available after paying (the
     * "Цена слова" about to be reserved), so paying from the balance never
     * leaves too little for it.
     */
    fun obtain(activity: Activity, product: Product, title: String, reserveAheadKop: Long = 0, onGranted: (GrantSource) -> Unit) {
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
        val priceKop = product.priceRub * 100L
        val available = Mama.wallet(activity).availableKop
        val fromBalance = FeatureFlags.WALLET_ENABLED && FeatureFlags.WALLET_SPEND_ENABLED && available - reserveAheadKop >= priceKop
        if (FeatureFlags.TEST_GRANTS_ENABLED || fromBalance) {
            val kit = Kit(activity)
            val content = kit.column()
            lateinit var handle: app.mama.ui.BrandDialogHandle
            if (fromBalance) {
                content.addView(kit.primaryButton("Оплатить с баланса MAMA") { handle.dismiss(); onGranted(GrantSource.INTERNAL_BALANCE) }, kit.fill())
                content.addView(kit.caption("На балансе ${Texts.money(available)}. Тестовые кредиты, не деньги."), kit.gap(6))
            }
            if (FeatureFlags.TEST_GRANTS_ENABLED) {
                content.addView(kit.secondaryButton("Активировать для теста") { handle.dismiss(); onGranted(GrantSource.TEST_NO_PAYMENT) }, kit.gap(if (fromBalance) 14 else 0))
                content.addView(kit.caption("Тестовая версия: оплата не подключена и не проводится."), kit.gap(6))
            }
            handle = BrandDialog.show(activity, "$title — ${Texts.price(product.priceRub)}", content = content, confirm = null, cancel = "Отмена")
            return
        }
        info(activity, "Оплата пока недоступна", "В этой версии оплата не подключена.")
    }

    private fun info(activity: Activity, title: String, message: String) {
        BrandDialog.show(activity, title, message)
    }
}

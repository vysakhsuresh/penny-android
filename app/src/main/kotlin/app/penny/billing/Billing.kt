package app.penny.billing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MO-01…MO-05 store billing. Product ids:
 *  - Plus: "plus_monthly", "plus_yearly" (subscriptions), "plus_lifetime" (one-time).
 *  - Tip jar: "tip_small", "tip_medium", "tip_large" (consumable, never gate anything).
 * Prices are set per region in the Play Console from the pack price points (MO-02). Nothing here unlocks features
 * except bank sync and storage, which are cost pass-through (NN-1).
 */
object Billing {
    const val PLUS_MONTHLY = "plus_monthly"
    const val PLUS_YEARLY = "plus_yearly"
    const val PLUS_LIFETIME = "plus_lifetime"
    private val TIPS = setOf("tip_small", "tip_medium", "tip_large")
    private val SUBS = setOf(PLUS_MONTHLY, PLUS_YEARLY)

    private var client: BillingClient? = null
    private var pending: ((Boolean) -> Unit)? = null
    private val _plus = MutableStateFlow(false)
    val plus: StateFlow<Boolean> = _plus
    /** Latest Plus purchase (token, product id) so the relay can grant bank sync on every device (MO-03). */
    private val _lastPurchase = MutableStateFlow<Pair<String, String>?>(null)
    val lastPurchase: StateFlow<Pair<String, String>?> = _lastPurchase
    private val _status = MutableStateFlow<String?>(null)
    /** Last user-visible billing problem (store not available, cancelled…), for the UI to show plainly. */
    val status: StateFlow<String?> = _status

    private fun client(context: Context): BillingClient = client ?: BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> onPurchases(context.applicationContext, result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build().also { client = it }

    private fun connected(context: Context, then: (BillingClient) -> Unit) {
        val c = client(context)
        if (c.isReady) { then(c); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) then(c) else { _status.value = r.debugMessage.ifBlank { "unavailable" }; pending?.invoke(false); pending = null }
            }
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun buy(context: Context, productId: String, done: (Boolean) -> Unit) {
        val activity = context.findActivity() ?: return done(false)
        pending = done
        connected(context) { c ->
            val type = if (productId in SUBS) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP
            val q = QueryProductDetailsParams.newBuilder().setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(type).build())).build()
            c.queryProductDetailsAsync(q) { r, details ->
                val d: ProductDetails = details.productDetailsList.firstOrNull() ?: run { _status.value = r.debugMessage.ifBlank { "not_found" }; done(false); pending = null; return@queryProductDetailsAsync }
                val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).apply {
                    d.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let(::setOfferToken)
                }.build()
                activity.runOnUiThread { c.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p)).build()) }
            }
        }
    }

    /** MO-03 restore purchases: re-reads owned subscriptions and lifetime. */
    fun restore(context: Context, done: (Boolean) -> Unit = {}) {
        connected(context) { c ->
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { _, subs ->
                c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { _, inapp ->
                    val owned = (subs + inapp).filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { p -> p in SUBS || p == PLUS_LIFETIME } }
                    _plus.value = owned.isNotEmpty()
                    owned.firstOrNull()?.let { _lastPurchase.value = it.purchaseToken to it.products.first() }
                    done(owned.isNotEmpty())
                }
            }
        }
    }

    private fun onPurchases(context: Context, result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            pending?.invoke(false); pending = null; return
        }
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { p ->
            val c = client(context)
            if (p.products.any { it in TIPS }) {
                c.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { _, _ -> }
            } else {
                if (!p.isAcknowledged) c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
                _plus.value = true
                _lastPurchase.value = p.purchaseToken to p.products.first()
            }
        }
        pending?.invoke(true); pending = null
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}

package app.penny.billing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Buy me a coffee": optional tips through the store, the only money that ever reaches Penny (decision 55). A tip is
 * the user's wish; it unlocks nothing and nobody gets fewer features for not giving. Penny has no subscription, no
 * paid tier and nothing to buy. Product ids: "tip_small", "tip_medium", "tip_large" (consumable).
 */
object Billing {
    private val TIPS = setOf("tip_small", "tip_medium", "tip_large")

    private var client: BillingClient? = null
    private var pending: ((Boolean) -> Unit)? = null
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

    /** Starts a tip. Anything that is not one of the tip ids is refused: Penny sells nothing. */
    fun buy(context: Context, productId: String, done: (Boolean) -> Unit) {
        if (productId !in TIPS) return done(false)
        val activity = context.findActivity() ?: return done(false)
        pending = done
        connected(context) { c ->
            val q = QueryProductDetailsParams.newBuilder().setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(BillingClient.ProductType.INAPP).build())).build()
            c.queryProductDetailsAsync(q) { r, details ->
                val d: ProductDetails = details.productDetailsList.firstOrNull() ?: run { _status.value = r.debugMessage.ifBlank { "not_found" }; done(false); pending = null; return@queryProductDetailsAsync }
                val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
                activity.runOnUiThread { c.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p)).build()) }
            }
        }
    }

    private fun onPurchases(context: Context, result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            pending?.invoke(false); pending = null; return
        }
        // Tips are consumed straight away so the same tip can be given again; nothing is recorded or unlocked.
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { p -> p in TIPS } }.forEach { p ->
            client(context).consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { _, _ -> }
        }
        pending?.invoke(true); pending = null
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}

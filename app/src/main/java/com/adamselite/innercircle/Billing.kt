package com.adamselite.innercircle

import android.app.Activity
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing for the one-time "Pro" upgrade.
 *
 * In Play Console, create a one-time product (Monetize > Products > In-app products)
 * with the product ID [PRO_PRODUCT_ID]. [onProOwned] is called with true when the player
 * owns Pro, and with false when Play says they don't.
 */
class Billing(
    private val activity: Activity,
    private val onProOwned: (Boolean) -> Unit
) : PurchasesUpdatedListener {

    companion object {
        const val PRO_PRODUCT_ID = "inner_circle_pro"
    }

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var proDetails: ProductDetails? = null

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refresh()
                    loadProduct()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Auto-reconnection is enabled; nothing to do.
            }
        })
    }

    /** Re-checks what the player owns (also restores Pro on a new phone). */
    fun refresh() {
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases, fromQuery = true)
            }
        }
    }

    private fun loadProduct(then: (() -> Unit)? = null) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                proDetails = queryResult.productDetailsList.firstOrNull()
            }
            then?.let { activity.runOnUiThread(it) }
        }
    }

    /** Opens Google Play's purchase screen for Pro. */
    fun purchasePro() {
        if (proDetails != null) {
            launchPurchase()
        } else if (client.isReady) {
            loadProduct { launchPurchase() }
        } else {
            toast("The Play Store isn't available right now. Try again in a moment.")
        }
    }

    private fun launchPurchase() {
        val details = proDetails
        if (details == null) {
            toast("Pro isn't available yet. Try again later.")
            return
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        client.launchBillingFlow(activity, flow)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handlePurchases(purchases.orEmpty(), fromQuery = false)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> toast("The purchase didn't go through. You weren't charged.")
        }
    }

    private fun handlePurchases(purchases: List<Purchase>, fromQuery: Boolean) {
        var owned = false
        for (p in purchases) {
            if (PRO_PRODUCT_ID !in p.products) continue
            when (p.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    owned = true
                    // Purchases must be acknowledged within 3 days or Google refunds them.
                    if (!p.isAcknowledged) {
                        val ack = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(p.purchaseToken)
                            .build()
                        client.acknowledgePurchase(ack) { }
                    }
                }
                Purchase.PurchaseState.PENDING ->
                    if (!fromQuery) toast("Payment pending. Pro unlocks as soon as it completes.")
            }
        }
        // A fresh query is the full truth (owned or not); a new purchase only ever turns Pro on.
        if (fromQuery || owned) activity.runOnUiThread { onProOwned(owned) }
        if (owned && !fromQuery) toast("Thanks! Pro is unlocked.")
    }

    fun end() = client.endConnection()

    private fun toast(msg: String) {
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }
    }
}

package com.eco.musicplayer.audioplayer.music

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "OldBillingManager"

class OldBillingManager(
    private val activity: Activity,
    application: Application,
    private val billingListener: BillingListener
) : BillingManagerInterface, PurchasesUpdatedListener {

    override val productDetailsMap: StateFlow<Map<String, Any>>
        get() = _skuDetailsMap.asStateFlow()
    private val _purchasedProductIds = MutableStateFlow<Set<String>>(emptySet())
    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())
    private val context = application.applicationContext

    internal val billingClient by lazy { createBillingClient() }


    init {
        setupBillingConnection()
    }

    // region Billing Client Setup
    private fun createBillingClient(): BillingClient {
        return BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enablePrepaidPlans()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
    }

    override fun setupBillingConnection() {
        if (!billingClient.isReady) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    when (billingResult.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            Log.d(TAG, "Billing connection success")
                            queryAllSkuDetails()
                            queryUserPurchases()
                        }
                        else -> Log.e(TAG, "Billing connection error: ${billingResult.responseCode}")
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing service disconnected")
                    retryConnect()
                }
            })
        } else {
            queryAllSkuDetails()
        }
    }
    // endregion

    private fun retryConnect() {
        setupBillingConnection()
    }

    // region Purchases
    private fun queryUserPurchases() {
        queryPurchases(BillingClient.SkuType.SUBS)
        queryPurchases(BillingClient.SkuType.INAPP)
    }

    private fun queryPurchases(productType: String) {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(productType)
                .build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handleAllPurchases(purchasesList)
                if (productType == BillingClient.SkuType.INAPP) {
                    purchasesList.forEach { purchase ->
                        if (purchase.products.contains(PRODUCT_ID_LIFETIME) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            Log.d(TAG, "Found lifetime purchase: ${purchase.purchaseToken}")
                            // Chỉ tiêu thụ nếu cần (ví dụ: thử nghiệm hoặc chính sách cụ thể)
                            // consumePurchase(purchase)
                        }
                    }
                }
            } else {
                Log.e(TAG, "Failed to query $productType purchases: ${billingResult.debugMessage}")
            }
        }
    }

    private fun handleAllPurchases(purchases: List<Purchase>) {
        val newPurchasedProductIds = purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED || it.purchaseState == Purchase.PurchaseState.PENDING }
            .flatMap { it.products }
            .toSet()

        _purchasedProductIds.value = newPurchasedProductIds
        _purchases.value = purchases
        Log.d(TAG, "Updated purchasedProductIds: $newPurchasedProductIds")
    }

    private fun getOldPurchaseToken(productId: String): String? {
        return _purchases.value
            .filter { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .maxByOrNull { it.purchaseTime }
            ?.purchaseToken
    }
    // endregion

    // region Billing Flow
    override fun launchBillingFlow(productDetails: Any, offerToken: String?) {
        if (productDetails !is SkuDetails) {
            Log.e(TAG, "Invalid productDetails type: ${productDetails.javaClass}")
            return
        }
        // Logic hiện tại của launchBillingFlow
        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient is not ready")
            Toast.makeText(context, "Dịch vụ thanh toán không sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }

        if (productDetails.sku.isEmpty()) {
            Log.e(TAG, "Invalid SkuDetails: SKU is empty")
            Toast.makeText(context, "Thông tin sản phẩm không hợp lệ", Toast.LENGTH_SHORT).show()
            return
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(productDetails)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${result.responseCode}")
            Toast.makeText(context, "Không thể khởi động thanh toán: ${result.responseCode}", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "Billing flow launched for ${productDetails.sku}")
        }
    }

    override fun launchBillingFlowForUpgrade(productDetails: Any, oldProductId: String) {
        if (productDetails !is SkuDetails) {
            Log.e(TAG, "Invalid productDetails type: ${productDetails.javaClass}")
            return
        }
        // Logic hiện tại của launchBillingFlowForUpgrade
        val oldPurchaseToken = getOldPurchaseToken(oldProductId) ?: run {
            Log.e(TAG, "No valid purchase token found for $oldProductId")
            Toast.makeText(context, "Không tìm thấy giao dịch trước đó", Toast.LENGTH_SHORT).show()
            return
        }

        if (productDetails.type != BillingClient.SkuType.SUBS) {
            Log.e(TAG, "Unsupported product type for upgrade: ${productDetails.type}")
            Toast.makeText(context, "Sản phẩm không phải là đăng ký", Toast.LENGTH_SHORT).show()
            return
        }

        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient is not ready")
            Toast.makeText(context, "Dịch vụ thanh toán không sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(productDetails)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${result.responseCode}")
            Toast.makeText(context, "Không thể khởi động thanh toán", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "Billing flow launched for upgrade to ${productDetails.sku}, old purchase token: $oldPurchaseToken")
        }
    }

    fun launchBillingFlowForDowngrade(skuDetails: SkuDetails, oldSkuId: String) {
        val oldPurchaseToken = getOldPurchaseToken(oldSkuId) ?: run {
            Log.e(TAG, "No valid purchase token found for $oldSkuId")
            return
        }

        if (skuDetails.type != BillingClient.SkuType.SUBS) {
            Log.e(TAG, "Unsupported product type for downgrade: ${skuDetails.type}")
            return
        }

        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient is not ready")
            return
        }

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(skuDetails)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${result.responseCode}")
        } else {
            Log.d(TAG, "Billing flow launched for downgrade to ${skuDetails.sku}, old purchase token: $oldPurchaseToken")
        }
    }
    // endregion

    // region Purchase Handling
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.let {
                    handleAllPurchases(it)
                    it.forEach { purchase ->
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            if (!purchase.isAcknowledged) {
                                acknowledgePurchase(purchase)
                            }
                            billingListener.onPurchaseSuccess(purchase)
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> billingListener.onPurchaseCancelled()
            else -> billingListener.onPurchaseError(billingResult.responseCode, billingResult.debugMessage)
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged successfully")
            } else {
                Log.e(TAG, "Failed to acknowledge purchase: ${result.debugMessage}")
            }
        }
    }
    // endregion

    private fun consumePurchase(purchase: Purchase) {
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.consumeAsync(consumeParams) { billingResult, _ ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Đã tiêu thụ purchase: ${purchase.purchaseToken}")
            } else {
                Log.e(TAG, "Lỗi khi tiêu thụ: ${billingResult.debugMessage}")
            }
        }
    }

    override fun endConnection() {
        if (billingClient.isReady) {
            billingClient.endConnection()
            Log.d(TAG, "Billing connection closed")
        }
    }
}
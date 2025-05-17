package com.eco.musicplayer.audioplayer.music.manager

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.*
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_LIFETIME
import com.eco.musicplayer.audioplayer.music.extension._productDetailsMap
import com.eco.musicplayer.audioplayer.music.extension.queryAllProductDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "BillingManager"

class BillingManager(
    private val activity: Activity,
    application: Application,
    private val billingListener: BillingListener
) : BillingManagerInterface, PurchasesUpdatedListener {

    private val context = application.applicationContext
    private val _purchasedProductIds = MutableStateFlow<Set<String>>(emptySet())
    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())
    override val productDetailsMap: StateFlow<Map<String, Any>>
        get() = _productDetailsMap.asStateFlow()

    internal val billingClient by lazy { createBillingClient() }

    init {
        setupBillingConnection()
    }

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
                            queryAllProductDetails()
                            queryUserPurchases()
                        }
                        else -> Log.e(
                            TAG,
                            "Billing connection error: ${billingResult.responseCode}"
                        )
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing service disconnected")
                    retryConnect()
                }
            })
        } else {
            queryAllProductDetails()
        }
    }

    private fun retryConnect() {
        setupBillingConnection()
    }

    // Kiểm tra các giao dịch đang hoạt động và trả về danh sách productId hợp lệ
    /**
     * Hàm này truy vấn cả subs và in-app purchase, chỉ lấy các giao dịch đã hoàn tất và đã được xác nhận.
     * Kết quả trả về sẽ là 1 set chứa các productID của các gói đang hoạt động.
     * Hàm này sẽ được gọi ở PaywallViewModel khi khởi tạo để đồng bộ SharedPreferences.
     */
    suspend fun checkActivePurchases(): Set<String> = suspendCancellableCoroutine { continuation ->
        val activeProductIds = mutableSetOf<String>()
        var subsQueryCompleted = false
        var inAppQueryCompleted = false

        fun checkCompletion() {
            if (subsQueryCompleted && inAppQueryCompleted) {
                continuation.resume(activeProductIds)
            }
        }

        // Truy vấn subscriptions
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                activeProductIds.addAll(
                    purchasesList
                        .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.isAcknowledged }
                        .flatMap { it.products }
                )
            } else {
                Log.e(TAG, "Failed to query SUBS purchases: ${billingResult.debugMessage}")
            }
            subsQueryCompleted = true
            checkCompletion()
        }

        // Truy vấn in-app purchases
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                activeProductIds.addAll(
                    purchasesList
                        .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.isAcknowledged }
                        .flatMap { it.products }
                )
            } else {
                Log.e(TAG, "Failed to query INAPP purchases: ${billingResult.debugMessage}")
            }
            inAppQueryCompleted = true
            checkCompletion()
        }
    }

    private fun queryUserPurchases() {
        queryPurchases(BillingClient.ProductType.SUBS)
        queryPurchases(BillingClient.ProductType.INAPP)
    }

    private fun queryPurchases(productType: String) {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(productType)
                .build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handleAllPurchases(purchasesList)
                if (productType == BillingClient.ProductType.INAPP) {
                    purchasesList.forEach { purchase ->
                        if (purchase.products.contains(PRODUCT_ID_LIFETIME)) {
                            Log.d("Cancel Lifetime", "Cancel Lifetime")
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

    override fun launchBillingFlow(productDetails: Any, offerToken: String?) {
        if (productDetails !is ProductDetails) {
            Log.e(TAG, "Invalid productDetails type: ${productDetails.javaClass}")
            billingListener.onPurchaseError(
                BillingClient.BillingResponseCode.DEVELOPER_ERROR,
                "Invalid productDetails type"
            )
            return
        }
        Log.d(TAG, "Launching billing flow for product: ${productDetails.productId}")
        val paramsDetailBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)

        if (productDetails.productType == BillingClient.ProductType.SUBS) {
            val subscriptionOffer = productDetails.subscriptionOfferDetails?.firstOrNull()
            if (subscriptionOffer == null) {
                Log.e(TAG, "No subscription offer details available for ${productDetails.productId}")
                billingListener.onPurchaseError(
                    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
                    "No subscription offer available"
                )
                return
            }
            val selectedOfferToken = subscriptionOffer.offerToken
            if (selectedOfferToken.isEmpty()) {
                Log.e(TAG, "Invalid offerToken for subscription: ${productDetails.productId}")
                billingListener.onPurchaseError(
                    BillingClient.BillingResponseCode.DEVELOPER_ERROR,
                    "Invalid offerToken"
                )
                return
            }
            paramsDetailBuilder.setOfferToken(selectedOfferToken)
        }

        val paramsDetail = paramsDetailBuilder.build()
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsDetail))
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${result.debugMessage}")
            billingListener.onPurchaseError(result.responseCode, result.debugMessage)
        }
    }

    override fun launchBillingFlowForUpgrade(productDetails: Any, oldProductId: String) {
        if (productDetails !is ProductDetails) {
            Log.e(TAG, "Invalid productDetails type: ${productDetails.javaClass}")
            return
        }
        val oldPurchaseToken = getOldPurchaseToken(oldProductId) ?: run {
            Log.e(TAG, "No valid purchase token found for $oldProductId")
            Toast.makeText(context, "Không tìm thấy giao dịch trước đó", Toast.LENGTH_SHORT).show()
            return
        }

        if (productDetails.productType != BillingClient.ProductType.SUBS) {
            Log.e(TAG, "Unsupported product type for upgrade: ${productDetails.productType}")
            return
        }

        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: run {
            Log.e(TAG, "No offer token for subscription: ${productDetails.title}")
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()

        val subscriptionUpdateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
            .setOldPurchaseToken(oldPurchaseToken)
            .setSubscriptionReplacementMode(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_FULL_PRICE)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setSubscriptionUpdateParams(subscriptionUpdateParams)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    fun launchBillingFlowForDowngrade(productDetails: ProductDetails, oldProductId: String) {
        val oldPurchaseToken = getOldPurchaseToken(oldProductId) ?: run {
            Log.e(TAG, "No valid purchase token found for $oldProductId")
            Toast.makeText(context, "Không tìm thấy giao dịch trước đó", Toast.LENGTH_SHORT).show()
            return
        }

        if (productDetails.productType != BillingClient.ProductType.SUBS) {
            Log.e(TAG, "Unsupported product type for downgrade: ${productDetails.productType}")
            return
        }

        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: run {
            Log.e(TAG, "No offer token for subscription: ${productDetails.title}")
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()

        val subscriptionUpdateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
            .setOldPurchaseToken(oldPurchaseToken)
            .setSubscriptionReplacementMode(
                BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
            )
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setSubscriptionUpdateParams(subscriptionUpdateParams)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.let {
                    handleAllPurchases(it)
                    it.forEach { purchase ->
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            if (!purchase.isAcknowledged) {
                                acknowledgePurchase(purchase)
                                billingListener.onPurchaseSuccess(purchase)
                            }
                        }
                    }
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> billingListener.onPurchaseCancelled()
            else -> billingListener.onPurchaseError(
                billingResult.responseCode,
                billingResult.debugMessage
            )
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged successfully")
            }
        }
    }

    private fun consumePurchase(purchase: Purchase) {
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.consumeAsync(consumeParams) { billingResult, _ ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Đã tiêu thụ purchase: ${purchase.purchaseToken}")
            } else {
                Log.i(TAG, "Lỗi khi tiêu thụ: ${billingResult.debugMessage}")
            }
        }
    }

    override fun endConnection() {
        if (billingClient.isReady) {
            billingClient.endConnection()
            Log.d(TAG, "Billing connection closed")
        }
    }

    fun checkVersion() {
        val result = billingClient.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS)
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
        } else {
        }
    }
}
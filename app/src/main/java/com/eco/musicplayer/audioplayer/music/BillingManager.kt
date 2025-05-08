package com.eco.musicplayer.audioplayer.music

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BillingManager"

class BillingManager(
    private val activity: Activity,
    application: Application,
    private val billingListener: BillingListener
) : PurchasesUpdatedListener {

    private val context = application.applicationContext

    // State flows (chỉ giữ lại những state cần thiết trong BillingManager)
    private val _purchasedProductIds = MutableStateFlow<Set<String>>(emptySet())
    val purchasedProductIds: StateFlow<Set<String>> = _purchasedProductIds.asStateFlow()
    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())
    val purchases: StateFlow<List<Purchase>> = _purchases.asStateFlow()

    internal val billingClient by lazy { createBillingClient() } // Để internal cho extension function dùng


    // Thêm TrialEligibilityChecker
    lateinit var trialEligibilityChecker: TrialEligibilityChecker

    /*// StateFlow để theo dõi trạng thái đủ điều kiện dùng thử
    val trialEligibilityMap: StateFlow<Map<String, Boolean>>
        get() = trialEligibilityChecker.trialEligibilityMap*/

    init {
        initTrialEligibilityChecker()
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

    private fun setupBillingConnection() {
        if (!billingClient.isReady) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    when (billingResult.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            Log.d(TAG, "Billing connection success")
                            queryAllProductDetails() // Gọi extension function
                            queryUserPurchases()
                            // Gọi refreshTrialEligibility sau khi đã có cả thông tin sản phẩm và giao dịch mua
                            refreshTrialEligibility()
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
            queryAllProductDetails() // Gọi extension function
        }
    }
    // endregion

    private fun retryConnect() {
        setupBillingConnection()
    }

    // region Purchases
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
                        if (purchase.products.contains("musicplayer_vip_lifetime")) {
                            Log.d("Cancel Lifetime", "Cancel Lifetime")
                            //Hủy Lifetime phía dev
                            //consumePurchase(purchase)
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

    //tạo tham số chi tiết sản phẩm cho luồng thanh toán
    fun createProductDetailsParams(productDetails: ProductDetails): BillingFlowParams.ProductDetailsParams? {
        return BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .apply {
                if (productDetails.productType == BillingClient.ProductType.SUBS) {
                    productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let {
                        setOfferToken(it)
                    } ?: run {
                        Log.e(TAG, "No offer token for subscription: ${productDetails.title}")
                        return null
                    }
                }
            }
            .build()
    }
    fun launchBillingFlow(productDetails: ProductDetails, offerToken: String) {
        //val productDetailsParams = createProductDetailsParams(productDetails) ?: return
        val paramsDetail = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsDetail))
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    // Quy trình cấp gói
    fun launchBillingFlowForUpgrade(productDetails: ProductDetails, oldProductId: String) {
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


    // Quy trình hạ cấp gói
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
                BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION
            )
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setSubscriptionUpdateParams(subscriptionUpdateParams)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
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
                                refreshTrialEligibility()
                            }
                            /*checkAndRecordTrialUsage(purchase)
                            billingListener.onPurchaseSuccess(purchase)*/
                        }
                        // Cập nhật trạng thái đủ điều kiện dùng thử
                        if (this::trialEligibilityChecker.isInitialized) {
                            trialEligibilityChecker.updateAfterPurchase(purchase)
                        }
                        billingListener.onPurchaseSuccess(purchase)
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
    // endregion

    // Khởi tạo trong constructor hoặc một phương thức init
    private fun initTrialEligibilityChecker() {
        trialEligibilityChecker = TrialEligibilityChecker(billingClient)

        // Liên kết với productDetailsMap
        TrialEligibilityChecker.listenToProductDetails(productDetailsMap)
    }

    // Làm mới trạng thái đủ điều kiện dùng thử
    fun refreshTrialEligibility() {
        if (this::trialEligibilityChecker.isInitialized) {
            trialEligibilityChecker.refreshTrialEligibility(_productDetailsMap.value)
        }
    }

    // Kiểm tra nhanh xem người dùng có đủ điều kiện dùng thử một sản phẩm không
    fun isEligibleForTrial(productId: String): Boolean {
        return if (this::trialEligibilityChecker.isInitialized) {
            trialEligibilityChecker.isEligibleForTrial(productId)
        } else false
    }

    fun getTrialTimeInfo(productId: String): TrialTimeInfo {
        return if (this::trialEligibilityChecker.isInitialized) {
            trialEligibilityChecker.getTrialTimeInfo(productId)
        } else {
            TrialTimeInfo(false, 0, 0)
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
}



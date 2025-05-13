package com.eco.musicplayer.audioplayer.music

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "OldBillingManager"

class OldBillingManager(
    private val activity: Activity,
    application: Application,
    private val billingListener: BillingListener
) : PurchasesUpdatedListener {

    private val context = application.applicationContext

    // State flows
    private val _purchasedProductIds = MutableStateFlow<Set<String>>(emptySet())
    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())

    internal val billingClient by lazy { createBillingClient() }


    // Thêm TrialEligibilityChecker
    lateinit var trialEligibilityChecker: TrialEligibilityChecker

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

    fun setupBillingConnection() {
        if (!billingClient.isReady) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    when (billingResult.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            Log.d(TAG, "Billing connection success")
                            queryAllSkuDetails() // Gọi extension function
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
            queryAllSkuDetails() // Gọi extension function
        }
    }
    // endregion

    private fun retryConnect() {
        setupBillingConnection()
    }

    // region Purchases
    // Truy vấn tất cả giao dịch của người dùng (cho cả sản phẩm in-app và subscriptions)
    private fun queryUserPurchases() {
        queryPurchases(BillingClient.SkuType.SUBS)
        queryPurchases(BillingClient.SkuType.INAPP)
    }

    // Truy vấn giao dịch theo loại sản phẩm
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
                        if (purchase.products.contains(PRODUCT_ID_LIFETIME)) {
                            Log.d("Cancel Lifetime", "Cancel Lifetime")
                            //Hủy Lifetime phía dev
                            consumePurchase(purchase)
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

    // Lấy purchaseToken của giao dịch gần nhất cho một sản phẩm cụ thể.
    private fun getOldPurchaseToken(productId: String): String? {
        return _purchases.value
            .filter { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .maxByOrNull { it.purchaseTime }
            ?.purchaseToken
    }
    // endregion

    // region Billing Flow
    fun launchBillingFlow(skuDetails: SkuDetails) {
        // Kiểm tra trạng thái BillingClient
        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient is not ready")
            Toast.makeText(context, "Dịch vụ thanh toán không sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }

        // Kiểm tra xem SkuDetails có hợp lệ không
        if (skuDetails.sku.isEmpty()) {
            Log.e(TAG, "Invalid SkuDetails: SKU is empty")
            Toast.makeText(context, "Thông tin sản phẩm không hợp lệ", Toast.LENGTH_SHORT).show()
            return
        }

        // Tạo BillingFlowParams cho sản phẩm
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(skuDetails)
            .build()

        // Khởi chạy quy trình thanh toán
        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${result.responseCode}")
            Toast.makeText(context, "Không thể khởi động thanh toán: ${result.responseCode}", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "Billing flow launched for ${skuDetails.sku}")
        }
    }

    // Quy trình cấp gói
    fun launchBillingFlowForUpgrade(skuDetails: SkuDetails, oldSkuId: String) {
        // Lấy purchase token của đăng ký cũ
        val oldPurchaseToken = getOldPurchaseToken(oldSkuId) ?: run {
            Log.e(TAG, "No valid purchase token found for $oldSkuId")
            Toast.makeText(context, "Không tìm thấy giao dịch trước đó", Toast.LENGTH_SHORT).show()
            return
        }

        // Kiểm tra xem sản phẩm có phải là đăng ký hay không
        if (skuDetails.type != BillingClient.SkuType.SUBS) {
            Log.e(TAG, "Unsupported product type for upgrade: ${skuDetails.type}")
            Toast.makeText(context, "Sản phẩm không phải là đăng ký", Toast.LENGTH_SHORT).show()
            return
        }

        // Kiểm tra trạng thái BillingClient
        if (!billingClient.isReady()) {
            Log.e(TAG, "BillingClient is not ready")
            Toast.makeText(context, "Dịch vụ thanh toán không sẵn sàng", Toast.LENGTH_SHORT).show()
            return
        }

        // Tạo BillingFlowParams cho sản phẩm mới
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(skuDetails)
            // Trong Billing Library < 5.0, không cần setOldSku hoặc SubscriptionUpdateParams
            // Google Play tự xử lý nâng cấp dựa trên giao dịch hiện tại
            .build()

        // Khởi chạy quy trình thanh toán
        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${result.responseCode}")
            Toast.makeText(context, "Không thể khởi động thanh toán", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "Billing flow launched for upgrade to ${skuDetails.sku}, old purchase token: $oldPurchaseToken")
        }
    }


    // Quy trình hạ cấp gói
    fun launchBillingFlowForDowngrade(skuDetails: SkuDetails, oldSkuId: String) {
        // Lấy purchase token của đăng ký cũ
        val oldPurchaseToken = getOldPurchaseToken(oldSkuId) ?: run {
            Log.e(TAG, "No valid purchase token found for $oldSkuId")
            return
        }

        // Kiểm tra xem sản phẩm có phải là đăng ký hay không
        if (skuDetails.type != BillingClient.SkuType.SUBS) {
            Log.e(TAG, "Unsupported product type for downgrade: ${skuDetails.type}")
            return
        }

        // Kiểm tra trạng thái BillingClient
        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient is not ready")
            return
        }

        // Tạo BillingFlowParams cho sản phẩm mới
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setSkuDetails(skuDetails)
            // Trong Billing Library < 5.0, không cần setOldSku, Google Play tự xử lý dựa trên purchaseToken
            .build()

        // Khởi chạy quy trình thanh toán
        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch billing flow: ${result.responseCode}")
        } else {
            Log.d(TAG, "Billing flow launched for downgrade to ${skuDetails.sku}, old purchase token: $oldPurchaseToken")
        }
    }
    // endregion

    // region Purchase Handling
    // Xử lý kết quả của các giao dịch (mới, nâng cấp, hạ cấp).
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

    // Xác nhận giao dịch với Google Play để hoàn tất quá trình mua hàng.
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
        TrialEligibilityChecker.listenToSkuDetails(skuDetailsMap)
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


    // Tiêu thụ giao dịch (dùng cho sản phẩm in-app tiêu thụ được).
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

    fun endConnection() {
        if (billingClient.isReady) {
            billingClient.endConnection()
            Log.d(TAG, "Billing connection closed")
        }
    }
}



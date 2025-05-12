package com.eco.musicplayer.audioplayer.music

import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchaseHistoryRecord
import com.android.billingclient.api.QueryPurchaseHistoryParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "TrialEligibility"

/**
 * Quản lý trạng thái đủ điều kiện dùng thử cho người dùng
 */
class TrialEligibilityChecker(private val billingClient: BillingClient) {

    // StateFlow chứa trạng thái đủ điều kiện dùng thử cho từng sản phẩm
    private val _trialEligibilityMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val trialEligibilityMap: StateFlow<Map<String, Boolean>> = _trialEligibilityMap.asStateFlow()

    // Lưu trữ lịch sử mua hàng của người dùng
    private var purchaseHistoryRecords: List<PurchaseHistoryRecord> = emptyList()

    // Lưu trữ các đăng ký hiện tại của người dùng
    private var activePurchases: List<Purchase> = emptyList()

    /**
     * Cập nhật và kiểm tra trạng thái đủ điều kiện dùng thử cho tất cả sản phẩm
     * Gọi hàm này sau khi BillingClient đã kết nối và queryProductDetails hoàn tất
     */
    fun refreshTrialEligibility(productDetailsMap: Map<String, ProductDetails>) {
        // Truy vấn lịch sử mua hàng và đăng ký hiện tại
        queryPurchaseHistory {
            queryActivePurchases {
                // Sau khi có đủ dữ liệu, tính toán trạng thái đủ điều kiện cho từng sản phẩm
                calculateTrialEligibility(productDetailsMap)
            }
        }
    }

    /**
     * Kiểm tra nhanh xem người dùng có đủ điều kiện dùng thử một sản phẩm cụ thể không
     * @return true nếu có thể dùng thử, false nếu không
     */
    fun isEligibleForTrial(productId: String): Boolean {
        return _trialEligibilityMap.value[productId] ?: false
    }

    /**
     * Kiểm tra xem một sản phẩm có cung cấp giai đoạn dùng thử không
     */
    fun hasTrialOffer(product: ProductDetails): Boolean {
        return product.subscriptionOfferDetails?.any { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: false
    }

    /**
     * Truy vấn lịch sử mua hàng của người dùng
     */
    private fun queryPurchaseHistory(onComplete: () -> Unit) {
        val params = QueryPurchaseHistoryParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchaseHistoryAsync(params) { billingResult, purchaseHistoryList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase history retrieved: ${purchaseHistoryList?.size ?: 0} items")
                purchaseHistoryRecords = purchaseHistoryList ?: emptyList()
            } else {
                Log.e(TAG, "Failed to query purchase history: ${billingResult.debugMessage}")
                purchaseHistoryRecords = emptyList()
            }
            onComplete()
        }
    }

    /**
     * Truy vấn các đăng ký hiện tại của người dùng
     */
    private fun queryActivePurchases(onComplete: () -> Unit) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Active purchases retrieved: ${purchasesList.size} items")
                activePurchases = purchasesList
            } else {
                Log.e(TAG, "Failed to query active purchases: ${billingResult.debugMessage}")
                activePurchases = emptyList()
            }
            onComplete()
        }
    }

    /**
     * Tính toán trạng thái đủ điều kiện dùng thử cho tất cả sản phẩm
     */
    private fun calculateTrialEligibility(productDetailsMap: Map<String, ProductDetails>) {
        val eligibilityMap = mutableMapOf<String, Boolean>()

        // Kiểm tra từng sản phẩm
        productDetailsMap.forEach { (productId, productDetails) ->
            // Kiểm tra sản phẩm có giai đoạn dùng thử không
            val hasTrialPhase = hasTrialOffer(productDetails)

            if (!hasTrialPhase) {
                // Sản phẩm không có giai đoạn dùng thử
                eligibilityMap[productId] = false
                return@forEach
            }

            // Kiểm tra người dùng đã từng dùng thử sản phẩm này chưa
            val hasUsedTrial = hasUserUsedTrialBefore(productId)

            // Kiểm tra người dùng có đang trong giai đoạn dùng thử không
            val isCurrentlyInTrial = isUserCurrentlyInTrial(productId)

            // Đủ điều kiện dùng thử nếu sản phẩm có trial và người dùng chưa từng dùng
            eligibilityMap[productId] = hasTrialPhase && !hasUsedTrial && !isCurrentlyInTrial

            Log.d(TAG, "Trial eligibility for $productId: ${eligibilityMap[productId]}")
            Log.d(TAG, "- Has trial phase: $hasTrialPhase")
            Log.d(TAG, "- Has used trial before: $hasUsedTrial")
            Log.d(TAG, "- Is currently in trial: $isCurrentlyInTrial")
        }

        // Cập nhật StateFlow
        _trialEligibilityMap.update { eligibilityMap }
    }

    /**
     * Kiểm tra người dùng đã từng dùng thử sản phẩm này chưa
     */
    private fun hasUserUsedTrialBefore(productId: String): Boolean {
        // Kiểm tra trong lịch sử mua hàng
        return purchaseHistoryRecords.any { record ->
            record.products.contains(productId)
        }
    }

    /**
     * Kiểm tra người dùng có đang trong giai đoạn dùng thử không
     */
    private fun isUserCurrentlyInTrial(productId: String): Boolean {
        // Kiểm tra trong các đăng ký hiện tại
        val purchase = activePurchases.find { it.products.contains(productId) } ?: return false

        // Kiểm tra thông tin đăng ký xem có đang trong giai đoạn thử hay không
        // Thường thì Google Play không cung cấp trực tiếp thông tin này trong Purchase object
        // Bạn có thể cần phải kiểm tra từ backend server của mình nếu có lưu trữ thông tin này

        // Đây là một cách tiếp cận đơn giản dựa trên thời gian đăng ký
        // Nếu đăng ký mới được thực hiện (ví dụ: trong vòng 1 ngày) và có giá trị trial
        // thì có khả năng cao là đang trong giai đoạn thử

        val currentTimeMillis = System.currentTimeMillis()
        val purchaseTimeMillis = purchase.purchaseTime
        val isRecentPurchase = (currentTimeMillis - purchaseTimeMillis) < (24 * 60 * 60 * 1000) // 1 ngày

        // Logic này cần được điều chỉnh theo chính sách cụ thể của ứng dụng
        return isRecentPurchase
    }

    /**
     * Cập nhật trạng thái đủ điều kiện sau khi người dùng mua hàng
     */
    fun updateAfterPurchase(purchase: Purchase) {
        // Cập nhật danh sách đăng ký hiện tại
        activePurchases = activePurchases.filter { it.purchaseToken != purchase.purchaseToken } + purchase

        // Làm mới trạng thái đủ điều kiện
        val productDetailsMap = _productDetailsMap.value
        if (productDetailsMap.isNotEmpty()) {
            calculateTrialEligibility(productDetailsMap)
        }
    }


    fun getTrialTimeInfo(productId: String): TrialTimeInfo {
        // Tìm purchase hiện tại
        val purchase = activePurchases.find { it.products.contains(productId) } ?:
        return TrialTimeInfo(false, 0, 0)

        // Tìm product details để lấy thông tin về trial period
        val productDetails = _productDetailsMap.value[productId] ?:
        return TrialTimeInfo(false, 0, 0)

        // Lấy trial phase (phase có giá = 0)
        val trialPhase = productDetails.subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.find { it.priceAmountMicros == 0L } ?: return TrialTimeInfo(false, 0, 0)

        // Parse billingPeriod (ví dụ: P3D, P1M)
        val billingPeriod = trialPhase.billingPeriod
        val totalTrialDays = when {
            billingPeriod.startsWith("P") && billingPeriod.endsWith("D") -> {
                billingPeriod.substring(1, billingPeriod.length - 1).toIntOrNull() ?: 0
            }
            billingPeriod.startsWith("P") && billingPeriod.endsWith("M") -> {
                val months = billingPeriod.substring(1, billingPeriod.length - 1).toIntOrNull() ?: 0
                months * 30 // Ước lượng 30 ngày cho 1 tháng
            }
            else -> 0
        }

        if (totalTrialDays <= 0) return TrialTimeInfo(false, 0, 0)

        // Tính thời gian đã trôi qua từ khi mua
        val currentTimeMillis = System.currentTimeMillis()
        val purchaseTimeMillis = purchase.purchaseTime
        val elapsedDays = ((currentTimeMillis - purchaseTimeMillis) / (24 * 60 * 60 * 1000)).toInt()

        // Tính ngày còn lại
        val remainingDays = maxOf(0, totalTrialDays - elapsedDays)

        // Kiểm tra còn trong giai đoạn trial không
        val isInTrial = remainingDays > 0

        return TrialTimeInfo(isInTrial, remainingDays, totalTrialDays)
    }

    private fun isCurrentlyInTrial(productId: String): Boolean {
        return getTrialTimeInfo(productId).isInTrial
    }

    companion object {
        // Tham chiếu tới StateFlow chứa thông tin sản phẩm
        private val _productDetailsMap = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())

        // Đăng ký lắng nghe thay đổi từ BillingManager
        fun listenToProductDetails(productDetailsMap: StateFlow<Map<String, ProductDetails>>) {
            // Trong thực tế, bạn sẽ sử dụng coroutines hoặc RxJava để collect StateFlow này
            // Đây chỉ là ví dụ đơn giản cho việc cập nhật giá trị
            _productDetailsMap.value = productDetailsMap.value
        }
    }
}
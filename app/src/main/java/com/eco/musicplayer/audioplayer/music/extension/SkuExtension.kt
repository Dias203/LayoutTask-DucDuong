package com.eco.musicplayer.audioplayer.music.extension

import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams
import com.eco.musicplayer.audioplayer.music.constants.ConstantsProductID
import com.eco.musicplayer.audioplayer.music.manager.OldBillingManager
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_FREE_TRIAL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BillingManager"

// StateFlow dùng để quan sát danh sách chi tiết sản phẩm và ánh xạ productId -> SkuDetails
private val _skuDetailsList = MutableStateFlow<List<SkuDetails>>(emptyList())
val _skuDetailsMap = MutableStateFlow<Map<String, SkuDetails>>(emptyMap())

// Expose dữ liệu skuDetailsMap ra ngoài dưới dạng StateFlow
val OldBillingManager.skuDetailsMap: StateFlow<Map<String, SkuDetails>>
    get() = _skuDetailsMap.asStateFlow()

val allSkuDetails = mutableListOf<SkuDetails>()

// Truy vấn toàn bộ thông tin chi tiết sản phẩm từ Google Play (cả Subscription và In-App Purchase)
fun OldBillingManager.queryAllSkuDetails() {
    var subsQueryCompleted = ConstantsProductID.subsListProduct.isEmpty()
    var inAppQueryCompleted = ConstantsProductID.inAppListProduct.isEmpty()

    // Hàm kiểm tra cả hai truy vấn hoàn thành để xử lý tiếp
    fun checkAndProcess() {
        if (subsQueryCompleted && inAppQueryCompleted) {
            processAllSkuDetails(allSkuDetails)
        }
    }

    // Truy vấn subscription
    if (ConstantsProductID.subsListProduct.isNotEmpty()) {
        querySkuDetails(
            productIds = ConstantsProductID.subsListProduct,
            productType = BillingClient.SkuType.SUBS,
            onComplete = { skus ->
                allSkuDetails.addAll(skus)
                subsQueryCompleted = true
                checkAndProcess()
            }
        )
    }

    // Truy vấn sản phẩm mua một lần
    if (ConstantsProductID.inAppListProduct.isNotEmpty()) {
        querySkuDetails(
            productIds = ConstantsProductID.inAppListProduct,
            productType = BillingClient.SkuType.INAPP,
            onComplete = { skus ->
                allSkuDetails.addAll(skus)
                inAppQueryCompleted = true
                checkAndProcess()
            }
        )
    }
}

// Hàm thực hiện truy vấn SkuDetails từ Google Play với danh sách ID sản phẩm
private fun OldBillingManager.querySkuDetails(
    productIds: List<String>,
    productType: String,
    onComplete: (List<SkuDetails>) -> Unit
) {
    val params = SkuDetailsParams.newBuilder()
        .setSkusList(productIds)
        .setType(productType)
        .build()

    // Gửi truy vấn bất đồng bộ
    billingClient.querySkuDetailsAsync(params) { billingResult, skuDetailsList ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            onComplete(skuDetailsList ?: emptyList())
        } else {
            Log.e(TAG, "Failed to retrieve $productType sku details: ${billingResult.debugMessage}")
            onComplete(emptyList())
        }
    }
}

// Xử lý danh sách SkuDetails sau khi truy vấn xong
private fun processAllSkuDetails(skuDetailsList: List<SkuDetails>) {
    Log.i(TAG, "All sku details retrieved: ${skuDetailsList.size}")
    _skuDetailsList.value = skuDetailsList
    _skuDetailsMap.value = skuDetailsList.associateBy { it.sku }

    // Log thông tin và phân tích gói dùng thử nếu có
    skuDetailsList.forEach {
        logSkuDetails(it)
        if (it.sku == PRODUCT_ID_FREE_TRIAL) {
            analyzeFreeTrialProduct(it)
        }
    }
}

// Thông tin chi tiết sản phẩm
private fun logSkuDetails(skuDetails: SkuDetails) {
    when (skuDetails.type) {
        BillingClient.SkuType.SUBS -> {
            // Log thông tin đăng ký
            val logMessage = buildString {
                append("Subscription - ${skuDetails.sku}: ${skuDetails.price}")
                if (skuDetails.subscriptionPeriod.isNotEmpty()) {
                    append(", period: ${skuDetails.subscriptionPeriod}")
                }
                if (skuDetails.freeTrialPeriod.isNotEmpty()) {
                    append(", trial: ${skuDetails.freeTrialPeriod}")
                }
                if (skuDetails.introductoryPrice.isNotEmpty()) {
                    append(", intro price: ${skuDetails.introductoryPrice}")
                    append(", intro period: ${skuDetails.introductoryPricePeriod}")
                    append(", intro cycles: ${skuDetails.introductoryPriceCycles}")
                }
            }
            Log.d(TAG, logMessage)
        }
        BillingClient.SkuType.INAPP -> {
            // Log thông tin sản phẩm in-app
            Log.d(TAG, "One-time - ${skuDetails.sku}: ${skuDetails.price}")
        }
        else -> {
            Log.w(TAG, "Unknown product type: ${skuDetails.type} for ${skuDetails.title}")
        }
    }
}

// Phân tích các gói dùng thử (free trial) trong sản phẩm
private fun analyzeFreeTrialProduct(skuDetails: SkuDetails) {
    Log.i(TAG, "\n=== Free Trial Analysis ===")

    // Chỉ xử lý sản phẩm đăng ký (subs)
    if (skuDetails.type != BillingClient.SkuType.SUBS) {
        Log.i(TAG, "Product ${skuDetails.sku} is not a subscription. Skipping free trial analysis.")
        return
    }

    // Kiểm tra xem sản phẩm có free trial hoặc ưu đãi hay không
    val hasFreeTrial = skuDetails.freeTrialPeriod.isNotEmpty()
    val hasIntroductoryPrice = skuDetails.introductoryPrice.isNotEmpty()

    if (!hasFreeTrial && !hasIntroductoryPrice) {
        Log.i(TAG, "Original price only (no promotions)")
        logOfferDetails(skuDetails)
    } else {
        Log.i(TAG, "Promotional offers available!")
        logOfferDetails(skuDetails)
        if (hasFreeTrial) {
            checkForFreeTrial(skuDetails)
        }
    }
}

// Thông tin chi tiết từng offer (gói)
private fun logOfferDetails(skuDetails: SkuDetails) {
    val logMessage = buildString {
        append("Offer for ${skuDetails.sku}: ")
        append("Price: ${skuDetails.price}")
        if (skuDetails.subscriptionPeriod.isNotEmpty()) {
            append(", Period: ${skuDetails.subscriptionPeriod}")
        }
        if (skuDetails.freeTrialPeriod.isNotEmpty()) {
            append(", Free Trial: ${skuDetails.freeTrialPeriod}")
        }
        if (skuDetails.introductoryPrice.isNotEmpty()) {
            append(", Intro Price: ${skuDetails.introductoryPrice}")
            append(", Intro Period: ${skuDetails.introductoryPricePeriod}")
            append(", Intro Cycles: ${skuDetails.introductoryPriceCycles}")
        }
    }
    Log.i(TAG, logMessage)
}

// Kiểm tra và log thông tin nếu offer có giai đoạn dùng thử miễn phí
private fun checkForFreeTrial(skuDetails: SkuDetails) {
    if (skuDetails.freeTrialPeriod.isNotEmpty()) {
        Log.i(TAG, "Free trial available: ${skuDetails.freeTrialPeriod}")
    } else {
        Log.i(TAG, "No free trial for ${skuDetails.sku}")
    }
}
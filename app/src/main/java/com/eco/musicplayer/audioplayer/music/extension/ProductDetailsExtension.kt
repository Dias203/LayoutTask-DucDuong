
package com.eco.musicplayer.audioplayer.music.extension

import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.eco.musicplayer.audioplayer.music.manager.BillingManager
import com.eco.musicplayer.audioplayer.music.constants.ConstantsProductID
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_FREE_TRIAL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BillingManager"

// StateFlow dùng để quan sát danh sách chi tiết sản phẩm và ánh xạ productId -> ProductDetails
private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
val _productDetailsMap = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())

// Expose dữ liệu productDetailsMap ra ngoài dưới dạng StateFlow
val BillingManager.productDetailsMap: StateFlow<Map<String, ProductDetails>>
    get() = _productDetailsMap.asStateFlow()

val allProductDetails = mutableListOf<ProductDetails>()

// Truy vấn toàn bộ thông tin chi tiết sản phẩm từ Google Play (cả Subscription và In-App Purchase)
fun BillingManager.queryAllProductDetails() {

    var subsQueryCompleted = ConstantsProductID.subsListProduct.isEmpty() // -> false
    var inAppQueryCompleted = ConstantsProductID.inAppListProduct.isEmpty() // -> false

    // Hàm kiểm tra cả hai truy vấn hoàn thành để xử lý tiếp
    fun checkAndProcess() {
        if (subsQueryCompleted && inAppQueryCompleted) {
            Log.d(TAG, "checkAndProcess: 1")
            processAllProductDetails(allProductDetails)
        }
    }

    // Truy vấn subscription
    queryProductDetails(
        productIds = ConstantsProductID.subsListProduct,
        productType = BillingClient.ProductType.SUBS,
        onComplete = { products ->
            allProductDetails.addAll(products)
            subsQueryCompleted = true
            Log.d(TAG, "queryAllProductDetails: 2")
            checkAndProcess()
        }
    )

    // Truy vấn sản phẩm mua một lần
    queryProductDetails(
        productIds = ConstantsProductID.inAppListProduct,
        productType = BillingClient.ProductType.INAPP,
        onComplete = { products ->
            allProductDetails.addAll(products)
            inAppQueryCompleted = true
            checkAndProcess()
        }
    )
}

// Hàm thực hiện truy vấn ProductDetails từ Google Play với danh sách ID sản phẩm
private fun BillingManager.queryProductDetails(
    productIds: List<String>,
    productType: String,
    onComplete: (List<ProductDetails>) -> Unit
) {
    val params = QueryProductDetailsParams.newBuilder()
        .setProductList(
            productIds.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it)
                    .setProductType(productType)
                    .build()
            }
        )
        .build()

    // Gửi truy vấn bất đồng bộ
    billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            onComplete(productDetailsList)
        } else {
            Log.e(TAG, "Failed to retrieve $productType product details: ${billingResult.debugMessage}")
            onComplete(emptyList())
        }
    }
}

// Xử lý danh sách ProductDetails sau khi truy vấn xong
private fun processAllProductDetails(productDetailsList: List<ProductDetails>) {
    Log.i(TAG, "All product details retrieved: ${productDetailsList.size}")
    _productDetailsList.value = productDetailsList
    _productDetailsMap.value = productDetailsList.associateBy { it.productId }

    // Log thông tin và phân tích gói dùng thử nếu có
    productDetailsList.forEach {
        logProductDetails(it)
        if (it.productId == PRODUCT_ID_FREE_TRIAL) {
            analyzeFreeTrialProduct(it)
        }
    }
}

// Thông tin chi tiết sản phẩm
private fun logProductDetails(product: ProductDetails) {
    when (product.productType) {
        BillingClient.ProductType.SUBS -> {
            product.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()
                ?.let { phase ->
                    Log.d(TAG, "Subscription - ${product.productId}: ${phase.formattedPrice}, ${phase.billingPeriod}, ")
                } ?: Log.e(TAG, "No pricing phases available for ${product.productId}")
        }
        BillingClient.ProductType.INAPP -> {
            product.oneTimePurchaseOfferDetails?.let { offer ->
                Log.d(TAG, "One-time - ${product.productId}: ${offer.formattedPrice}")
            } ?: Log.e(TAG, "No offer details for ${product.productId}")
        }
        else -> Log.w(TAG, "Unknown product type: ${product.productType} for ${product.title}")
    }
}

// Phân tích các gói dùng thử (free trial) trong sản phẩm
private fun analyzeFreeTrialProduct(product: ProductDetails) {
    val offers = product.subscriptionOfferDetails ?: emptyList()
    Log.i(TAG, "\n=== Free Trial Analysis ===")
    Log.i(TAG, "Total offers: ${offers.size}")

    if (offers.isEmpty()) {
        Log.i(TAG, "No offers available for free trial")
        return
    }

    if (isOriginalPriceOnly(offers)) {
        Log.i(TAG, "Original price only (no promotions)")
        logOfferDetails(offers.first())
    } else {
        Log.i(TAG, "Promotional offers available!")
        offers.forEach { offer ->
            logOfferDetails(offer)
            checkForFreeTrial(offer)
        }
    }
}

// Kiểm tra xem có phải chỉ có giá gốc, không có khuyến mãi
private fun isOriginalPriceOnly(offers: List<ProductDetails.SubscriptionOfferDetails>): Boolean {
    return offers.size == 1 && offers[0].offerId == null
}

// Thông tin chi tiết từng offer (gói)
private fun logOfferDetails(offer: ProductDetails.SubscriptionOfferDetails) {
    Log.i(TAG, "\n==================Offer ID: ${offer.offerId}==================")
    Log.i(TAG, "Base Plan ID: ${offer.basePlanId}")
    Log.i(TAG, "Offer Token: ${offer.offerToken}")

    offer.pricingPhases.pricingPhaseList.forEachIndexed { index, phase ->
        Log.i(TAG, "Phase ${index + 1}:")
        Log.i(TAG, "Price: ${phase.formattedPrice}")
        Log.i(TAG, "Billing Period: ${phase.billingPeriod}")
        Log.i(TAG, "Recurrence Mode: ${phase.recurrenceMode}")
        Log.i(TAG, "Cycle Count: ${phase.billingCycleCount}")
    }
}

// Kiểm tra và log thông tin nếu offer có giai đoạn dùng thử miễn phí
private fun checkForFreeTrial(offer: ProductDetails.SubscriptionOfferDetails) {
    val freePhases = offer.pricingPhases.pricingPhaseList.filter { it.priceAmountMicros == 0L }

    if (freePhases.isNotEmpty()) {
        Log.i(TAG, "FREE TRIAL DETECTED!")
        freePhases.forEach { phase ->
            Log.i(TAG, "Free Period: ${phase.billingPeriod}")
            Log.i(TAG, "Free Cycle Count: ${phase.billingCycleCount}")
            Log.i(TAG, "Price Amount Micros: ${phase.priceAmountMicros}")
        }
    } else {
        Log.i(TAG, "No free trial in this offer")
    }
}
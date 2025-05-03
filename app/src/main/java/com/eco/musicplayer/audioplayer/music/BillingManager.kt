package com.eco.musicplayer.audioplayer.music

import android.app.Activity
import android.app.Application
import android.util.Log
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BillingManager(
    private val activity: Activity,
    application: Application,
    private val billingListener: BillingListener
) : PurchasesUpdatedListener {
    private val TAG = "BillingManager"

    val listIDProduct = listOf( // Sử dụng listOf immutable nếu không cần thay đổi bên ngoài
        "musicplayer_vip_lifetime", "vip_year", "vip_month"
    )
    private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
    private val context = application.applicationContext
    private val _productDetailsMap = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetailsMap: StateFlow<Map<String, ProductDetails>> = _productDetailsMap.asStateFlow()
    private val _purchasedProductIds = MutableStateFlow<Set<String>>(emptySet())

    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enablePrepaidPlans()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    init {
        if (!billingClient.isReady) {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.e(TAG, "Billing connection success: ${billingResult.responseCode}")
                        queryAllProductDetails()
                        queryUserPurchases()
                    } else {
                        Log.e(TAG, "Billing connection error: ${billingResult.responseCode}")
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "onBillingServiceDisconnected")
                }
            })
        } else {
            queryAllProductDetails()
        }
    }

    private fun queryAllProductDetails() {
        val subsProducts = listIDProduct.filter { it in listOf("vip_year", "vip_month") }
        val inappProducts = listIDProduct.filter { it == "musicplayer_vip_lifetime" }
        val allProductDetails = mutableListOf<ProductDetails>()
        var subsQueryCompleted = subsProducts.isEmpty()
        var inappQueryCompleted = inappProducts.isEmpty()

        fun checkAndProcess() {
            if (subsQueryCompleted && inappQueryCompleted) {
                processAllProductDetails(allProductDetails)
            }
        }

        if (subsProducts.isNotEmpty()) {
            val subsParams = QueryProductDetailsParams.newBuilder()
                .setProductList(subsProducts.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                })
                .build()

            billingClient.queryProductDetailsAsync(subsParams) { billingResult, productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    allProductDetails.addAll(productDetailsList)
                } else {
                    Log.e(
                        TAG,
                        "Failed to retrieve SUBS product details: ${billingResult.debugMessage}"
                    )
                }
                subsQueryCompleted = true
                checkAndProcess()
            }
        } else {
            checkAndProcess()
        }

        if (inappProducts.isNotEmpty()) {
            val inappParams = QueryProductDetailsParams.newBuilder()
                .setProductList(inappProducts.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                })
                .build()

            billingClient.queryProductDetailsAsync(inappParams) { billingResult, productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    allProductDetails.addAll(productDetailsList)
                } else {
                    Log.e(
                        TAG,
                        "Failed to retrieve INAPP product details: ${billingResult.debugMessage}"
                    )
                }
                inappQueryCompleted = true
                checkAndProcess()
            }
        } else {
            checkAndProcess()
        }
    }

    private fun queryUserPurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handleAllPurchases(purchasesList)
            } else {
                Log.e(TAG, "Failed to query SUBS purchases: ${billingResult.debugMessage}")
            }
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handleAllPurchases(purchasesList)
            } else {
                Log.e(TAG, "Failed to query INAPP purchases: ${billingResult.debugMessage}")
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
        Log.d("BillingManager", "Updated purchasedProductIds: $newPurchasedProductIds")
    }

    private fun getOldPurchaseToken(productId: String): String? {
        return _purchases.value
            .filter { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .maxByOrNull { it.purchaseTime }
            ?.purchaseToken
    }

    private fun processAllProductDetails(productDetailsList: List<ProductDetails>) {
        Log.d(TAG, "All product details retrieved: ${productDetailsList.size}")
        _productDetailsList.value = productDetailsList
        _productDetailsMap.value = productDetailsList.associateBy { it.productId }

        productDetailsList.forEach { product ->
            when (val type = product.productType) {
                BillingClient.ProductType.SUBS -> {
                    product.subscriptionOfferDetails?.firstOrNull()?.let { offerDetails ->
                        offerDetails.pricingPhases.pricingPhaseList.firstOrNull()?.let { phase ->
                            Log.d(
                                TAG,
                                "Price: ${phase.formattedPrice}, Period: ${phase.billingPeriod}"
                            )
                        } ?: Log.e(TAG, "No pricing phases available for ${product.productId}")
                    } ?: Log.e(TAG, "No subscription offer details for ${product.productId}")
                }

                BillingClient.ProductType.INAPP -> {
                    product.oneTimePurchaseOfferDetails?.let { oneTimeOffer ->
                        Log.d(
                            TAG,
                            "INAPP - Price: ${oneTimeOffer.formattedPrice} for ${product.productId}"
                        )
                    } ?: Log.e(TAG, "No inapp offer details for ${product.productId}")
                }

                else -> {
                    Log.w(TAG, "Unknown product type: $type for ${product.title}")
                }
            }
        }
    }

    fun launchBillingFlow(productDetails: ProductDetails) {
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .apply {
                    if (productDetails.productType == BillingClient.ProductType.SUBS) {
                        productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let {
                            setOfferToken(it)
                        } ?: run {
                            Log.e(TAG, "No offer token for subscription: ${productDetails.title}")
                            return
                        }
                    }
                }
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    fun launchBillingFlowForUpgrade(productDetails: ProductDetails, oldProductId: String) {
        val oldPurchaseToken = getOldPurchaseToken(oldProductId)
        if (oldPurchaseToken == null) {
            Log.e(TAG, "No valid purchase token found for $oldProductId")
            Toast.makeText(context, "Không tìm thấy giao dịch trước đó", Toast.LENGTH_SHORT).show()
            return
        }

        if (productDetails.productType != BillingClient.ProductType.SUBS) {
            Log.e(TAG, "Unsupported product type for upgrade: ${productDetails.productType}")
            return
        }

        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            Log.e(TAG, "No offer token for subscription: ${productDetails.title}")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()
        )

        val subscriptionUpdateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
            .setOldPurchaseToken(oldPurchaseToken)
            .setSubscriptionReplacementMode(0) // Cần xem xét lại ReplacementMode
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .setSubscriptionUpdateParams(subscriptionUpdateParams)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handleAllPurchases(purchases)
            purchases.forEach { handlePurchase(it); billingListener.onPurchaseSuccess(it) }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            billingListener.onPurchaseCancelled()
        } else {
            billingListener.onPurchaseError(billingResult.responseCode, billingResult.debugMessage)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            billingClient.acknowledgePurchase(acknowledgePurchaseParams) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Purchase acknowledged successfully")
                    // Mở khóa tính năng tại đây
                }
            }
        }
    }
}
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

    private val PRODUCT_IDS = listOf(
        "musicplayer_vip_lifetime",
        "vip_year",
        "vip_month"
    )


    private val context = application.applicationContext

    // State flows
    private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
    private val _productDetailsMap = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetailsMap: StateFlow<Map<String, ProductDetails>> = _productDetailsMap.asStateFlow()

    private val _purchasedProductIds = MutableStateFlow<Set<String>>(emptySet())
    private val _purchases = MutableStateFlow<List<Purchase>>(emptyList())

    private val billingClient by lazy { createBillingClient() }

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

    private fun setupBillingConnection() {
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
                }
            })
        } else {
            queryAllProductDetails()
        }
    }
    // endregion

    // region Product Details
    private fun queryAllProductDetails() {
        val subsProducts = PRODUCT_IDS.filter { it in listOf("vip_year", "vip_month") }
        val inappProducts = PRODUCT_IDS.filter { it == "musicplayer_vip_lifetime" }

        val allProductDetails = mutableListOf<ProductDetails>()
        var subsQueryCompleted = subsProducts.isEmpty()
        var inappQueryCompleted = inappProducts.isEmpty()

        fun checkAndProcess() {
            if (subsQueryCompleted && inappQueryCompleted) {
                processAllProductDetails(allProductDetails)
            }
        }

        if (subsProducts.isNotEmpty()) {
            queryProductDetails(
                productIds = subsProducts,
                productType = BillingClient.ProductType.SUBS,
                onComplete = { products ->
                    allProductDetails.addAll(products)
                    subsQueryCompleted = true
                    checkAndProcess()
                }
            )
        }

        if (inappProducts.isNotEmpty()) {
            queryProductDetails(
                productIds = inappProducts,
                productType = BillingClient.ProductType.INAPP,
                onComplete = { products ->
                    allProductDetails.addAll(products)
                    inappQueryCompleted = true
                    checkAndProcess()
                }
            )
        }
    }

    private fun queryProductDetails(
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

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                onComplete(productDetailsList ?: emptyList())
            } else {
                Log.e(
                    TAG,
                    "Failed to retrieve $productType product details: ${billingResult.debugMessage}"
                )
                onComplete(emptyList())
            }
        }
    }

    private fun processAllProductDetails(productDetailsList: List<ProductDetails>) {
        Log.d(TAG, "All product details retrieved: ${productDetailsList.size}")
        _productDetailsList.value = productDetailsList
        _productDetailsMap.value = productDetailsList.associateBy { it.productId }

        productDetailsList.forEach { logProductDetails(it) }
    }

    private fun logProductDetails(product: ProductDetails) {
        when (product.productType) {
            BillingClient.ProductType.SUBS -> {
                product.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()
                    ?.let { phase ->
                        Log.d(
                            TAG,
                            "Subscription - ${product.productId}: ${phase.formattedPrice}, ${phase.billingPeriod}"
                        )
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
    // endregion

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
    fun launchBillingFlow(productDetails: ProductDetails) {
        val productDetailsParams = createProductDetailsParams(productDetails) ?: return
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

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
            .setSubscriptionReplacementMode(0)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setSubscriptionUpdateParams(subscriptionUpdateParams)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    private fun createProductDetailsParams(productDetails: ProductDetails): BillingFlowParams.ProductDetailsParams? {
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
    // endregion

    // region Purchase Handling
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.let {
                    handleAllPurchases(it)
                    it.forEach { purchase ->
                        handlePurchase(purchase)
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

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            acknowledgePurchase(purchase)
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
}
package com.eco.musicplayer.audioplayer.music

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaywallViewModel(
    private val sharedPreferences: SharedPreferences,
    activity: Activity,
    application: Application
) : ViewModel(), BillingListener {

    companion object {
        const val PURCHASED_PRODUCTS_KEY = "purchasedProducts"
    }

    private val billingManager: BillingManager = BillingManager(activity, application, this)

    // State flows for UI observation
    private val _productDetailsMap = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetailsMap: StateFlow<Map<String, ProductDetails>> = _productDetailsMap.asStateFlow()

    private val _purchasedProducts = MutableStateFlow<Set<String>>(emptySet())
    val purchasedProducts: StateFlow<Set<String>> = _purchasedProducts.asStateFlow()

    private val _selectedProductId = MutableStateFlow<String?>(null)
    val selectedProductId: StateFlow<String?> = _selectedProductId.asStateFlow()

    private val _selectedOfferToken = MutableStateFlow<String?>(null)
    val selectedOfferToken: StateFlow<String?> = _selectedOfferToken.asStateFlow()

    private val _uiState = MutableStateFlow<PaywallUiState>(PaywallUiState.Loading)
    val uiState: StateFlow<PaywallUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
        observeBillingData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            loadPurchasedProductsFromPrefs()
            billingManager.setupBillingConnection()
            // Chờ productDetailsMap có dữ liệu
            billingManager.productDetailsMap.collect { map ->
                if (map.isNotEmpty() && _selectedProductId.value == null && _purchasedProducts.value.isEmpty()) {
                    _selectedProductId.value = PRODUCT_ID_MONTH
                    selectPlan(PRODUCT_ID_MONTH) // Gọi để cập nhật offerToken
                }
            }
        }
    }

    private suspend fun loadPurchasedProductsFromPrefs() {
        withContext(Dispatchers.IO) {
            val savedProducts = sharedPreferences.getStringSet(PURCHASED_PRODUCTS_KEY, emptySet()) ?: emptySet()
            _purchasedProducts.value = savedProducts
            _uiState.value = PaywallUiState.Loaded
        }
    }

    private fun observeBillingData() {
        viewModelScope.launch {
            billingManager.productDetailsMap.collect { map ->
                _productDetailsMap.value = map
                _uiState.value = PaywallUiState.Loaded
            }
        }
    }

    fun selectPlan(productId: String) {
        if (!_purchasedProducts.value.contains(productId)) {
            _selectedProductId.value = productId
            val offerToken = _productDetailsMap.value[productId]?.subscriptionOfferDetails?.firstOrNull()?.offerToken
            _selectedOfferToken.value = offerToken
        }
    }

    fun purchaseSelectedProduct() {
        val productId = _selectedProductId.value ?: return
        val productDetails = _productDetailsMap.value[productId] ?: return
        val offerToken = _selectedOfferToken.value ?: ""

        viewModelScope.launch {
            val currentSubscription = _purchasedProducts.value.firstOrNull {
                it in ConstantsProductID.subsListProduct
            }

            when {
                productId == PRODUCT_ID_LIFETIME -> {
                    billingManager.launchBillingFlow(productDetails = productDetails)
                }
                currentSubscription != null && productId in ConstantsProductID.subsListProduct -> {
                    if (isUpgradeAllowed(currentSubscription, productId)) {
                        billingManager.launchBillingFlowForUpgrade(
                            productDetails = productDetails,
                            oldProductId = currentSubscription
                        )
                    } else {
                        _uiState.value = PaywallUiState.Error("Bạn chỉ có thể nâng cấp lên gói cao hơn!")
                    }
                }
                else -> {
                    billingManager.launchBillingFlow(
                        productDetails = productDetails,
                        offerToken = offerToken
                    )
                }
            }
        }
    }

    private fun isUpgradeAllowed(currentProductId: String, newProductId: String): Boolean {
        return getSubscriptionLevel(newProductId) > getSubscriptionLevel(currentProductId)
    }

    private fun getSubscriptionLevel(productId: String): Int {
        return when (productId) {
            PRODUCT_ID_MONTH -> 1
            PRODUCT_ID_YEAR -> 2
            PRODUCT_ID_LIFETIME -> 3
            else -> 0
        }
    }

    override fun onPurchaseSuccess(purchase: Purchase) {
        viewModelScope.launch {
            val productId = purchase.products.firstOrNull() ?: return@launch
            val currentProducts = sharedPreferences.getStringSet(PURCHASED_PRODUCTS_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()

            currentProducts.add(productId)
            sharedPreferences.edit().putStringSet(PURCHASED_PRODUCTS_KEY, currentProducts).apply()

            _purchasedProducts.value = currentProducts
            _uiState.value = PaywallUiState.PurchaseSuccess(productId)
        }
    }

    override fun onPurchaseError(errorCode: Int, errorMessage: String) {
        _uiState.value = PaywallUiState.Error("Purchase error: $errorMessage (Code: $errorCode)")
    }

    override fun onPurchaseCancelled() {
        _uiState.value = PaywallUiState.Error("Purchase cancelled")
    }
}

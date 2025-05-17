package com.eco.musicplayer.audioplayer.viewmodel

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.eco.musicplayer.audioplayer.music.constants.ConstantsProductID
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_LIFETIME
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_MONTH
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_YEAR
import com.eco.musicplayer.audioplayer.music.manager.BillingManagerFactory.createBillingClientAndConnect
import com.eco.musicplayer.audioplayer.music.manager.BillingListener
import com.eco.musicplayer.audioplayer.music.manager.BillingManager
import com.eco.musicplayer.audioplayer.music.manager.BillingManagerFactory
import com.eco.musicplayer.audioplayer.music.manager.BillingManagerInterface
import com.eco.musicplayer.audioplayer.music.state.PaywallUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PURCHASED_PRODUCTS_KEY = "purchasedProducts"
private const val TAG = "PaywallViewModel"

class PaywallViewModel(
    private val sharedPreferences: SharedPreferences,
    activity: Activity,
    application: Application
) : ViewModel(), BillingListener {

    private val billingManager: BillingManagerInterface by lazy {
        BillingManagerFactory.setupBillingManager(
            activity,
            application,
            this
        )
    }

    private val _detailsMap = MutableStateFlow<Map<String, Any>>(emptyMap())
    val detailsMap: StateFlow<Map<String, Any>> = _detailsMap.asStateFlow()

    private val _purchasedProducts = MutableStateFlow<Set<String>>(emptySet())
    val purchasedProducts: StateFlow<Set<String>> = _purchasedProducts.asStateFlow()

    private val _selectedProductId = MutableStateFlow<String?>(null)
    val selectedProductId: StateFlow<String?> = _selectedProductId.asStateFlow()

    private val _uiState = MutableStateFlow<PaywallUiState?>(null)
    val uiState: StateFlow<PaywallUiState?> = _uiState.asStateFlow()

    init {
        createBillingClientAndConnect(context = application) {
            loadInitialData()
            syncPurchasesWithGooglePlay()
            observeBillingData()
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            loadPurchasedProductsFromPrefs()
            billingManager.productDetailsMap.collect { map ->
                if (map.isNotEmpty() && _selectedProductId.value == null && _purchasedProducts.value.isEmpty()) {
                    _selectedProductId.value = PRODUCT_ID_MONTH
                    selectPlan(PRODUCT_ID_MONTH)
                }
                _detailsMap.value = map
            }
        }
    }

    private suspend fun loadPurchasedProductsFromPrefs() {
        withContext(Dispatchers.IO) {
            val savedProducts = sharedPreferences.getStringSet(PURCHASED_PRODUCTS_KEY, emptySet()) ?: emptySet()
            _purchasedProducts.value = savedProducts
        }
    }

    // Đồng bộ danh sách mua hàng với Google Play
    /**
     * Đồng bộ SharedPreferences với danh sách gói hợp lệ trong syncPurchasesWithGooglePlay.
     * Cho phép mua lại các gói đã hủy bằng cách cập nhật logic chọn và mua trong PaywallViewModel và PaywallActivity.
     */
    private fun syncPurchasesWithGooglePlay() {
        viewModelScope.launch {
            val activeProductIds = (billingManager as? BillingManager)?.checkActivePurchases() ?: emptySet()
            withContext(Dispatchers.IO) {
                val currentProducts = sharedPreferences.getStringSet(PURCHASED_PRODUCTS_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
                val updatedProducts = currentProducts.intersect(activeProductIds)
                sharedPreferences.edit().putStringSet(PURCHASED_PRODUCTS_KEY, updatedProducts).apply()
                _purchasedProducts.value = updatedProducts
                Log.d(TAG, "Synced purchased products: $updatedProducts")
            }
        }
    }

    private fun observeBillingData() {
        viewModelScope.launch {
            billingManager.productDetailsMap.collect { map ->
                _detailsMap.value = map
            }
        }
    }

    fun selectPlan(productId: String) {
        if (!_purchasedProducts.value.contains(productId)) {
            _selectedProductId.value = productId
            Log.d(TAG, "Selected plan: $productId")
        }
    }

    fun purchaseSelectedProduct() {
        val productId = _selectedProductId.value ?: run {
            _uiState.value = PaywallUiState.Error("No product selected")
            Log.e(TAG, "No product selected")
            return
        }
        val productDetails = _detailsMap.value[productId] ?: run {
            _uiState.value = PaywallUiState.Error("Product details not found for $productId")
            Log.e(TAG, "Product details not found for $productId")
            return
        }

        viewModelScope.launch {
            val currentSubscription = _purchasedProducts.value.firstOrNull {
                it in ConstantsProductID.subsListProduct
            }

            when {
                productId == PRODUCT_ID_LIFETIME -> {
                    Log.d(TAG, "Launching billing flow for LIFETIME")
                    billingManager.launchBillingFlow(productDetails)
                }
                currentSubscription != null && productId in ConstantsProductID.subsListProduct -> {
                    if (isUpgradeAllowed(currentSubscription, productId)) {
                        Log.d(
                            TAG,
                            "Launching billing flow for upgrade from $currentSubscription to $productId"
                        )
                        billingManager.launchBillingFlowForUpgrade(
                            productDetails = productDetails,
                            oldProductId = currentSubscription
                        )
                    }
                }
                else -> {
                    Log.d(TAG, "Launching billing flow for subscription")
                    billingManager.launchBillingFlow(productDetails)
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
            Log.d(TAG, "Purchase successful: $productId")
        }
    }

    override fun onPurchaseError(errorCode: Int, errorMessage: String) {
        _uiState.value = PaywallUiState.Error("Purchase error: $errorMessage (Code: $errorCode)")
        Log.e(TAG, "Purchase error: $errorMessage (Code: $errorCode)")
    }

    override fun onPurchaseCancelled() {
        _uiState.value = PaywallUiState.Error("Purchase cancelled")
        Log.d(TAG, "Purchase cancelled")
    }

    fun endBilling() {
        billingManager.endConnection()
    }
}
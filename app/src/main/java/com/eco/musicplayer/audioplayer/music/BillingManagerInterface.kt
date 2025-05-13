package com.eco.musicplayer.audioplayer.music

import com.android.billingclient.api.BillingClient
import kotlinx.coroutines.flow.StateFlow

interface BillingManagerInterface {
    val productDetailsMap: StateFlow<Map<String, Any>> // Map<String, ProductDetails> hoặc Map<String, SkuDetails>
    fun setupBillingConnection()
    fun launchBillingFlow(productDetails: Any, offerToken: String? = null) // Any để hỗ trợ cả ProductDetails và SkuDetails
    fun launchBillingFlowForUpgrade(productDetails: Any, oldProductId: String)
    fun isEligibleForTrial(productId: String): Boolean
    fun getTrialTimeInfo(productId: String): TrialTimeInfo
}
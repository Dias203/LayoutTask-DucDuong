package com.eco.musicplayer.audioplayer.music.manager

import kotlinx.coroutines.flow.StateFlow

interface BillingManagerInterface {
    val productDetailsMap: StateFlow<Map<String, Any>>
    fun setupBillingConnection()
    fun launchBillingFlow(productDetails: Any, offerToken: String? = null)
    fun launchBillingFlowForUpgrade(productDetails: Any, oldProductId: String)
    fun endConnection() // Thêm phương thức này
}
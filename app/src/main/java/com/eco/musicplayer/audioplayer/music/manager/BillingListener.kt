package com.eco.musicplayer.audioplayer.music.manager

import com.android.billingclient.api.Purchase

interface BillingListener {
    fun onPurchaseSuccess(purchase: Purchase)
    fun onPurchaseError(errorCode: Int, errorMessage: String)
    fun onPurchaseCancelled()
}
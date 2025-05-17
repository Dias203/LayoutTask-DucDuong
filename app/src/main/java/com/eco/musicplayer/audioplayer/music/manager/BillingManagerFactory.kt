package com.eco.musicplayer.audioplayer.music

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams

object BillingManagerFactory {

    private var billingClient: BillingClient? = null
    private var supportsProductDetails: Boolean = false

    fun setupBillingManager(
        activity: Activity,
        application: Application,
        billingListener: BillingListener,

    ): BillingManagerInterface {
        return if (supportsProductDetails) {
            Log.d("TAG", "createBillingManager: Using BillingManager")
            BillingManager(activity, application, billingListener)
        } else {
            Log.d("TAG", "createBillingManager: Using OldBillingManager")
            OldBillingManager(activity, application, billingListener)
        }
    }

    fun createBillingClientAndConnect(context: Context,complete: () -> Unit){
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(context)
                .setListener { _, _ -> }
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        .enablePrepaidPlans()
                        .enableOneTimeProducts()
                        .build()
                )
                .build()

            billingClient?.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() {
                    Log.d("TAG", "Billing Service Disconnected")
                    // Xử lý logic khi kết nối bị ngắt, ví dụ: thử kết nối lại
                }

                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d("TAG", "Billing Setup Finished successfully")
                        val featureResult =
                            billingClient?.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS)
                        Log.d("TAG", "onBillingSetupFinished: $featureResult")
                        supportsProductDetails =
                            featureResult?.responseCode == BillingClient.BillingResponseCode.OK
                        Log.d("TAG", "PRODUCT_DETAILS supported: $supportsProductDetails")
                    } else {
                        Log.e("TAG", "Billing Setup Failed: ${billingResult.debugMessage}")
                    }
                    complete()
                }
            })

        }
    }
}

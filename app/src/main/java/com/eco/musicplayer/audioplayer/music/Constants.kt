package com.eco.musicplayer.audioplayer.music

const val PRODUCT_ID_MONTH = "vip_month"
const val PRODUCT_ID_YEAR = "vip_year"
const val PRODUCT_ID_LIFETIME = "musicplayer_vip_lifetime"
const val PRODUCT_ID_FREE_TRIAL = "free_123"

object Constants {
    val subsListProduct = mutableListOf<String>(
        PRODUCT_ID_MONTH,
        PRODUCT_ID_YEAR,
        PRODUCT_ID_FREE_TRIAL
    )
    val inAppListProduct = mutableListOf<String>(
        PRODUCT_ID_LIFETIME
    )
}
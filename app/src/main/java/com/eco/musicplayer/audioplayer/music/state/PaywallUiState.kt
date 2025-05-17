package com.eco.musicplayer.audioplayer.music

sealed class PaywallUiState {
    data class PurchaseSuccess(val productId: String) : PaywallUiState()
    data class Error(val message: String) : PaywallUiState()
}
package com.eco.musicplayer.audioplayer.music

sealed class PaywallUiState {
    data object Loading : PaywallUiState()
    data object Loaded : PaywallUiState()
    data class PurchaseSuccess(val productId: String) : PaywallUiState()
    data class Error(val message: String) : PaywallUiState()
}
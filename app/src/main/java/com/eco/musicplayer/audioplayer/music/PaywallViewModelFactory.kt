package com.eco.musicplayer.audioplayer.music

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class PaywallViewModelFactory(
    private val sharedPreferences: SharedPreferences,
    private val activity: Activity,
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PaywallViewModel(sharedPreferences, activity, application) as T
    }
}
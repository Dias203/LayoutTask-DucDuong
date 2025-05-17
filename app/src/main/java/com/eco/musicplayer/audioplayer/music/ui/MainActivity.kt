package com.eco.musicplayer.audioplayer.music.ui

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.eco.musicplayer.audioplayer.music.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(){
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)




        binding.showBottomSheet.setOnClickListener {
            clickOpenBottomSheetDialog()
        }
    }

    @SuppressLint("InflateParams")
    private fun clickOpenBottomSheetDialog() {
        val bottomSheetDialog = OfferBottomSheetDialog(this)
        bottomSheetDialog.show()
    }

}



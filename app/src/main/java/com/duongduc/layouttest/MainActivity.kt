package com.duongduc.layouttest

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.duongduc.layouttest.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
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



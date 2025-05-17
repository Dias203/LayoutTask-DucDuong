package com.eco.musicplayer.audioplayer.music.ui

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StrikethroughSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.eco.musicplayer.audioplayer.music.R
import com.eco.musicplayer.audioplayer.music.databinding.LayoutBottomSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

class OfferBottomSheetDialog(context: Context) : BottomSheetDialog(context) {
    private val binding: LayoutBottomSheetBinding = LayoutBottomSheetBinding.inflate(LayoutInflater.from(context))
    private val appContext = context.applicationContext
    init {
        setContentView(binding.root)

        setOnShowListener {
            val bottomSheet =
                findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }
        setupOldPrice()
        setupSlipText()
        setTextTermsAndPrivacy()
    }

    private fun setupOldPrice() {
        setupStrikethroughText(binding.tvOldPrice)
    }
    private fun setupStrikethroughText(textView: TextView) {
        val text = textView.text.toString()
        val spannableString = SpannableString(text)

        val strikethroughSpan = StrikethroughSpan()
        spannableString.setSpan(strikethroughSpan, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        textView.text = spannableString

    }

    private fun setupSlipText() {
        val str = "I’ll let this deal slip away"
        val spannableString = SpannableString(str)
        val startIndex = str.indexOf(str)
        val endIndex = startIndex + str.length

        spannableString.setSpan(
            createClickableSpan { dismiss() },
            startIndex,
            endIndex,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.tv5.text = spannableString
        binding.tv5.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setTextTermsAndPrivacy() {
        val fullText = appContext.getString(R.string.full_text)
        val spannable = SpannableString(fullText)

        val termsStart = fullText.indexOf("Terms")
        val termsEnd = termsStart + "Terms".length

        val privacyStart = fullText.indexOf("Privacy")
        val privacyEnd = privacyStart + "Privacy".length

        spannable.setSpan(
            createClickableSpan {
                Toast.makeText(appContext, "Terms clicked", Toast.LENGTH_SHORT).show()
            },
            termsStart,
            termsEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
            createClickableSpan {
                Toast.makeText(appContext, "Privacy clicked", Toast.LENGTH_SHORT).show()
            },
            privacyStart,
            privacyEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.tv6.text = spannable
        binding.tv6.movementMethod = LinkMovementMethod.getInstance()
        binding.tv6.highlightColor = Color.TRANSPARENT
    }


    private fun createClickableSpan(onClick: () -> Unit): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) = onClick()

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = Color.parseColor("#313131")
                ds.isUnderlineText = true
            }
        }
    }
}


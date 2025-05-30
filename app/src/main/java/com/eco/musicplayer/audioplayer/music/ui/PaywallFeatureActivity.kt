package com.eco.musicplayer.audioplayer.music.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import com.eco.musicplayer.audioplayer.music.R
import com.eco.musicplayer.audioplayer.music.databinding.ActivityPaywallFeatureBinding

class PaywallFeatureActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPaywallFeatureBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallFeatureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInitialSelectedPlan()
        setupPlanSelection()
        setupPlanTexts()
        setupLimitedVersionText()
        setupTermsAndPrivacyText()
    }

    private fun setupInitialSelectedPlan() {
        selectPlan(binding.btn3Day)
    }

    private fun setupPlanSelection() {
        binding.btn3Day.setOnClickListener { selectPlan(binding.btn3Day) }
        binding.btnMonthly.setOnClickListener { selectPlan(binding.btnMonthly) }
        binding.btnLifetime.setOnClickListener { selectPlan(binding.btnLifetime) }
    }

    private fun selectPlan(selectedBtn: ViewGroup) {
        val buttons = listOf(binding.btn3Day, binding.btnMonthly, binding.btnLifetime)
        for (button in buttons) {
            val isSelected = button == selectedBtn
            val background = if (isSelected) R.drawable.bg_selected_paywall_feature else R.drawable.bg_unselected_paywall
            val icon = if (isSelected) R.drawable.ic_checked_gold else R.drawable.ic_uncheck
            button.setBackgroundResource(background)
            button.findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(icon)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupPlanTexts() {
        binding.btnMonthly.apply {
            findViewById<TextView>(R.id.tv2).text = "Monthly"
            findViewById<TextView>(R.id.tv4).text = "$8.99"
            findViewById<TextView>(R.id.tv5).text = "per month"
        }

        binding.btnLifetime.apply {
            findViewById<TextView>(R.id.tv2).text = "Lifetime"
            findViewById<TextView>(R.id.tv3).text = "One-time payment"
            findViewById<TextView>(R.id.tv4).text = "$20.99"
            findViewById<TextView>(R.id.tv5).text = "forever"
        }
    }

    private fun setupLimitedVersionText() {
        val str = "or Use limited version"
        val spannableString = SpannableString(str)
        spannableString.setSpan(
            createClickableSpan {
                Toast.makeText(this, "Use Limited Version", Toast.LENGTH_SHORT).show()
            },
            0, str.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tv6.text = spannableString
        binding.tv6.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setupTermsAndPrivacyText() {
        val fullText = getString(R.string.full_text)
        val spannable = SpannableString(fullText)

        setClickableText(spannable, fullText, "Terms") {
            Toast.makeText(this, "Terms clicked", Toast.LENGTH_SHORT).show()
        }
        setClickableText(spannable, fullText, "Privacy policies") {
            Toast.makeText(this, "Privacy policies clicked", Toast.LENGTH_SHORT).show()
        }

        binding.tv7.text = spannable
        binding.tv7.movementMethod = LinkMovementMethod.getInstance()
        binding.tv7.highlightColor = Color.TRANSPARENT
    }

    private fun setClickableText(spannable: SpannableString, fullText: String, keyword: String, onClick: () -> Unit) {
        val start = fullText.indexOf(keyword)
        if (start >= 0) {
            val end = start + keyword.length
            spannable.setSpan(
                createClickableSpanGray(onClick),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun createClickableSpan(onClick: () -> Unit): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) = onClick()
            override fun updateDrawState(ds: TextPaint) {
                ds.color = Color.parseColor("#313131")
                ds.isUnderlineText = true
            }
        }
    }

    private fun createClickableSpanGray(onClick: () -> Unit): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) = onClick()
            override fun updateDrawState(ds: TextPaint) {
                ds.color = Color.parseColor("#9E9E9E")
                ds.isUnderlineText = true
            }
        }
    }
}
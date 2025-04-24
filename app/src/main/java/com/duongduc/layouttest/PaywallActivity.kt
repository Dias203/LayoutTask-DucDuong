package com.duongduc.layouttest

import android.content.Intent
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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.duongduc.layouttest.databinding.ActivityPaywallBinding

class PaywallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPaywallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /*// Cho phép layout tràn lên status bar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Làm status bar trong suốt
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )*/
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInitialSelectedPlan()
        setupPlanSelection()
        setupPlanTexts()
        setupLimitedVersionText()
        setupTermsAndPrivacyText()
        binding.btnStartFreeTrial.setOnClickListener {
            startActivity(Intent(this, PaywallFeatureActivity::class.java))
        }
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
            val background = if (isSelected) R.drawable.bg_selected_paywall else R.drawable.bg_unselected_paywall
            val icon = if (isSelected) R.drawable.ic_checked else R.drawable.ic_uncheck
            button.setBackgroundResource(background)
            button.findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(icon)
        }
    }

    private fun setupPlanTexts() {
        binding.btnMonthly.apply {
            findViewById<TextView>(R.id.tv2).text = "Monthly"
            findViewById<TextView>(R.id.tv4).text = "60,000đ"
            findViewById<TextView>(R.id.tv5).text = "per month"
        }

        binding.btnLifetime.apply {
            findViewById<TextView>(R.id.tv2).text = "Lifetime"
            findViewById<TextView>(R.id.tv3).text = "One-time payment"
            findViewById<TextView>(R.id.tv4).text = "600,000đ"
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
                ds.color = Color.parseColor("#C81212")
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

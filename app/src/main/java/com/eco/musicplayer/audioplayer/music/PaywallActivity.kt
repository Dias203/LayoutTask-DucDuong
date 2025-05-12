package com.eco.musicplayer.audioplayer.music

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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.ProductDetails
import com.eco.musicplayer.audioplayer.music.databinding.ActivityPaywallBinding
import kotlinx.coroutines.launch

class PaywallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPaywallBinding

    private val viewModel: PaywallViewModel by viewModels {
        PaywallViewModelFactory(
            getSharedPreferences("BillingPrefs", MODE_PRIVATE),
            this,
            application
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initUI()
        setupObservers()
    }

    private fun initUI() {
        setupLimitedVersionText()
        setupTermsAndPrivacyText()
        setupPlanSelection()
    }

    private fun setupObservers() {
        // Quan sát trạng thái UI
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is PaywallUiState.Loading -> showLoading()
                    is PaywallUiState.Loaded -> hideLoading()
                    is PaywallUiState.PurchaseSuccess -> handlePurchaseSuccess(state.productId)
                    is PaywallUiState.Error -> showError(state.message)
                }
            }
        }

        // Quan sát chi tiết sản phẩm
        lifecycleScope.launch {
            viewModel.productDetailsMap.collect { map ->
                if (map.isNotEmpty()) {
                    setupPlanTexts(map)
                }
            }
        }

        // Quan sát sản phẩm đã mua
        lifecycleScope.launch {
            viewModel.purchasedProducts.collect { products ->
                updatePlanSelectionBasedOnPurchases(products)
            }
        }

        // Quan sát sản phẩm đã chọn
        lifecycleScope.launch {
            viewModel.selectedProductId.collect { productId ->
                productId?.let { updateSelectedPlanUi(it) }
            }
        }
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.progressBar.visibility = View.GONE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun handlePurchaseSuccess(productId: String) {
        Toast.makeText(this, "Purchase successful!", Toast.LENGTH_SHORT).show()

        when (productId) {
            PRODUCT_ID_MONTH -> binding.btnMonthly.disablePurchasedButton()
            PRODUCT_ID_YEAR -> binding.btnYearly.disablePurchasedButton()
            PRODUCT_ID_LIFETIME -> binding.btnLifetime.disablePurchasedButton()
        }

        if (productId == PRODUCT_ID_MONTH) {
            binding.bestDeal.apply {
                text = "Đã mua"
                setBackgroundResource(R.drawable.bg_disable)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupPlanTexts(productDetailsMap: Map<String, ProductDetails>) {
        // Xử lý gói MONTHLY
        productDetailsMap[PRODUCT_ID_MONTH]?.let { monthlyPlan ->
            binding.btnMonthly.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(monthlyPlan.productId)

                val firstOffer = monthlyPlan.subscriptionOfferDetails?.firstOrNull()
                val lastOffer = monthlyPlan.subscriptionOfferDetails?.lastOrNull()

                // Kiểm tra xem có firstOffer hay không
                if (firstOffer?.pricingPhases?.pricingPhaseList?.firstOrNull() != null && viewModel.selectedProductId.value == PRODUCT_ID_MONTH) {
                    // Trường hợp có firstOffer (ưu đãi)
                    firstOffer.pricingPhases.pricingPhaseList.first().let { phase ->
                        findViewById<TextView>(R.id.tv3).apply {
                            text = "${phase.formattedPrice} cho 14 ngày, gia hạn sau ${parsePeriodToReadableText(phase.billingPeriod)}"
                            visibility = View.VISIBLE
                            // Cập nhật tvSub với nội dung của tv3
                            binding.tvSub.text = text
                            binding.tvSub.visibility = View.VISIBLE
                        }
                    }
                } else if (lastOffer?.pricingPhases?.pricingPhaseList?.firstOrNull() != null && viewModel.selectedProductId.value == PRODUCT_ID_MONTH) {
                    // Trường hợp không có firstOffer, sử dụng giá từ tv4 và chu kỳ từ tv5
                    lastOffer.pricingPhases.pricingPhaseList.first().let { phase ->
                        findViewById<TextView>(R.id.tv3).visibility = View.GONE
                        val tv4 = findViewById<TextView>(R.id.tv4).apply {
                            text = phase.formattedPrice
                        }
                        val tv5 = findViewById<TextView>(R.id.tv5).apply {
                            text = parsePeriodToReadableText(phase.billingPeriod)
                        }
                        // Cập nhật tvSub với giá từ tv4 và chu kỳ từ tv5
                        binding.tvSub.text = "${tv4.text}/${tv5.text}"
                        binding.tvSub.visibility = View.VISIBLE
                    }
                } else {
                    // Trường hợp không có dữ liệu hợp lệ
                    findViewById<TextView>(R.id.tv3).visibility = View.GONE
                    if (viewModel.selectedProductId.value == PRODUCT_ID_MONTH) {
                        binding.tvSub.visibility = View.GONE
                    }
                }

                // Set dữ liệu cho tv4 và tv5 từ lastOffer (nếu có)
                lastOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv4).text = phase.formattedPrice
                    findViewById<TextView>(R.id.tv5).text = parsePeriodToReadableText(phase.billingPeriod)
                }
            }
        }

        // Xử lý gói YEARLY
        productDetailsMap[PRODUCT_ID_YEAR]?.let { yearlyPlan ->
            binding.btnYearly.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(yearlyPlan.productId)

                val firstOffer = yearlyPlan.subscriptionOfferDetails?.firstOrNull()
                val lastOffer = yearlyPlan.subscriptionOfferDetails?.lastOrNull()

                firstOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv3).apply {
                        text = "${phase.formattedPrice} cho 1 năm, gia hạn sau ${parsePeriodToReadableText(phase.billingPeriod)}"
                        visibility = View.VISIBLE
                    }
                } ?: run {
                    findViewById<TextView>(R.id.tv3).visibility = View.GONE
                }

                lastOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv4).text = phase.formattedPrice
                    findViewById<TextView>(R.id.tv5).text = parsePeriodToReadableText(phase.billingPeriod)
                }
            }
        }

        // Xử lý gói LIFETIME
        productDetailsMap[PRODUCT_ID_LIFETIME]?.let { lifetimePlan ->
            binding.btnLifetime.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(lifetimePlan.productId)
                findViewById<TextView>(R.id.tv3).text = "One-time payment"

                lifetimePlan.oneTimePurchaseOfferDetails?.let { offer ->
                    findViewById<TextView>(R.id.tv4).text = offer.formattedPrice
                    findViewById<TextView>(R.id.tv5).text = "forever"
                }
            }
        }
    }

    private fun setupPlanSelection() {
        binding.btnMonthly.setOnClickListener {
            viewModel.selectPlan(PRODUCT_ID_MONTH)
        }

        binding.btnYearly.setOnClickListener {
            viewModel.selectPlan(PRODUCT_ID_YEAR)
        }

        binding.btnLifetime.setOnClickListener {
            viewModel.selectPlan(PRODUCT_ID_LIFETIME)
        }

        binding.btnStartFreeTrial.setOnClickListener {
            viewModel.purchaseSelectedProduct()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updatePlanSelectionBasedOnPurchases(currentPurchasedProducts: Set<String>) {
        val buttons = listOf(
            binding.btnMonthly to PRODUCT_ID_MONTH,
            binding.btnYearly to PRODUCT_ID_YEAR,
            binding.btnLifetime to PRODUCT_ID_LIFETIME
        )

        if (currentPurchasedProducts.contains(PRODUCT_ID_LIFETIME)) {
            buttons.forEach { (button, productId) ->
                if (productId == PRODUCT_ID_LIFETIME) {
                    button.disablePurchasedButton()
                } else {
                    button.isEnabled = false
                    button.alpha = 0.5f
                }
            }
            binding.btnStartFreeTrial.apply {
                isEnabled = false
                text = "Đã mua"
            }
            return
        }

        val currentSubscription =
            currentPurchasedProducts.firstOrNull { it in ConstantsProductID.subsListProduct }

        buttons.forEach { (button, productId) ->
            when {
                currentPurchasedProducts.contains(productId) -> {
                    button.disablePurchasedButton()
                    when (productId) {
                        PRODUCT_ID_MONTH -> {
                            binding.bestDeal.apply {
                                text = "Đã m"
                                setBackgroundResource(R.drawable.bg_disable)
                            }
                        }

                        PRODUCT_ID_YEAR -> {
                            binding.bestDeal2.apply {
                                visibility = View.VISIBLE
                                text = "Purchased"
                                setBackgroundResource(R.drawable.bg_disable)
                            }
                        }

                        else -> {
                            binding.bestDeal3.apply {
                                visibility = View.VISIBLE
                                text = "Purchased"
                                setBackgroundResource(R.drawable.bg_disable)
                            }
                        }
                    }
                }

                currentSubscription != null && getSubscriptionLevel(productId) <= getSubscriptionLevel(
                    currentSubscription
                ) -> {
                    button.isEnabled = false
                    button.alpha = 0.5f
                }

                else -> {
                    val isSelected = viewModel.selectedProductId.value == productId
                    button.apply {
                        setBackgroundResource(
                            if (isSelected) R.drawable.bg_selected_paywall
                            else R.drawable.bg_unselected_paywall
                        )
                        findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(
                            if (isSelected) R.drawable.ic_checked
                            else R.drawable.ic_uncheck
                        )
                        isEnabled = true
                        alpha = 1.0f
                    }
                }
            }
        }

        binding.btnStartFreeTrial.text =
            if (currentSubscription != null) "Upgrade Plan" else "Start Free Trial"
    }

    private fun updateSelectedPlanUi(productId: String) {
        val buttons = listOf(
            binding.btnMonthly to PRODUCT_ID_MONTH,
            binding.btnYearly to PRODUCT_ID_YEAR,
            binding.btnLifetime to PRODUCT_ID_LIFETIME
        )

        for ((button, id) in buttons) {
            if (viewModel.purchasedProducts.value.contains(id)) {
                continue
            }
            val isSelected = id == productId
            val background = if (isSelected) R.drawable.bg_selected_paywall else R.drawable.bg_unselected_paywall
            val icon = if (isSelected) R.drawable.ic_checked else R.drawable.ic_uncheck

            button.setBackgroundResource(background)
            button.findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(icon)

            if (isSelected) {
                val tv3OfSelectedButton = button.findViewById<TextView>(R.id.tv3)
                if (tv3OfSelectedButton.visibility == View.VISIBLE) {
                    // Nếu tv3 hiển thị (có firstOffer), dùng nội dung của tv3
                    binding.tvSub.text = tv3OfSelectedButton.text
                    binding.tvSub.visibility = View.VISIBLE
                } else if (id != PRODUCT_ID_LIFETIME) {
                    // Nếu không có firstOffer, dùng tv4 và tv5
                    val tv4 = button.findViewById<TextView>(R.id.tv4)
                    val tv5 = button.findViewById<TextView>(R.id.tv5)
                    binding.tvSub.text = "${tv4.text}/${tv5.text}"
                    binding.tvSub.visibility = View.VISIBLE
                } else {
                    // Trường hợp LIFETIME, ẩn tvSub
                    binding.tvSub.visibility = View.GONE
                }
            }
        }
    }

    private fun getSubscriptionLevel(productId: String): Int {
        return when (productId) {
            PRODUCT_ID_MONTH -> 1
            PRODUCT_ID_YEAR -> 2
            PRODUCT_ID_LIFETIME -> 3
            else -> 0
        }
    }

    private fun getTitleText(billingTitle: String): String {
        return when {
            billingTitle.contains(
                PRODUCT_ID_MONTH,
                ignoreCase = true
            ) -> getString(R.string.sub_week)

            billingTitle.contains(
                PRODUCT_ID_YEAR,
                ignoreCase = true
            ) -> getString(R.string.sub_year)

            billingTitle.contains(
                PRODUCT_ID_LIFETIME,
                ignoreCase = true
            ) -> getString(R.string.in_app)

            billingTitle.contains(PRODUCT_ID_FREE_TRIAL, ignoreCase = true) -> "Free Trial"
            else -> billingTitle
        }
    }

    private fun parsePeriodToReadableText(period: String): String {
        return when {
            period.contains("D") -> "${period.filter { it.isDigit() }} ${getString(R.string.text_day)}"
            period.contains("W") -> "${period.filter { it.isDigit() }} ${getString(R.string.text_week)}"
            period.contains("M") -> "${period.filter { it.isDigit() }} ${getString(R.string.text_month)}"
            period.contains("Y") -> "${period.filter { it.isDigit() }} ${getString(R.string.text_year)}"
            else -> "Không rõ"
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

    private fun setClickableText(
        spannable: SpannableString,
        fullText: String,
        keyword: String,
        onClick: () -> Unit
    ) {
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

    private fun ViewGroup.disablePurchasedButton() {
        isEnabled = false
        findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(R.drawable.ic_uncheck)
        setBackgroundResource(R.drawable.bg_disable)
        alpha = 0.8f
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.endBillingConnection()
    }
}

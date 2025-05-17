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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.SkuDetails
import com.eco.musicplayer.audioplayer.music.constants.ConstantsProductID
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_FREE_TRIAL
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_LIFETIME
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_MONTH
import com.eco.musicplayer.audioplayer.music.constants.PRODUCT_ID_YEAR
import com.eco.musicplayer.audioplayer.music.state.PaywallUiState
import com.eco.musicplayer.audioplayer.viewmodel.PaywallViewModel
import com.eco.musicplayer.audioplayer.viewmodel.PaywallViewModelFactory
import com.eco.musicplayer.audioplayer.music.R
import com.eco.musicplayer.audioplayer.music.databinding.ActivityPaywallBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PaywallActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPaywallBinding
    private val viewModel: PaywallViewModel by viewModels {
        PaywallViewModelFactory(getSharedPreferences("BillingPrefs", MODE_PRIVATE), this, application)
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
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is PaywallUiState.PurchaseSuccess -> handlePurchaseSuccess(state.productId)
                    is PaywallUiState.Error -> showToast(state.message)
                    else -> Unit
                }
            }
        }

        lifecycleScope.launch {
            viewModel.detailsMap.combine(viewModel.selectedProductId) { map, selectedId ->
                map to selectedId
            }.collect { (map, selectedId) ->
                if (map.isNotEmpty() && selectedId != null) {
                    setupPlanTexts(map)
                    updateSelectedPlanUi(selectedId)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.purchasedProducts.collect(::updatePlanSelectionBasedOnPurchases)
        }
    }

    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun handlePurchaseSuccess(productId: String) {
        showToast("Purchase successful!")
        val button = when (productId) {
            PRODUCT_ID_MONTH -> binding.btnMonthly
            PRODUCT_ID_YEAR -> binding.btnYearly
            else -> binding.btnLifetime
        }
        button.disablePurchasedButton()
        if (productId == PRODUCT_ID_MONTH) {
            binding.bestDeal.updatePurchasedBadge()
        }
    }

    private fun setupPlanTexts(detailsMap: Map<String, Any>) {
        listOf(
            PRODUCT_ID_MONTH to binding.btnMonthly,
            PRODUCT_ID_YEAR to binding.btnYearly,
            PRODUCT_ID_LIFETIME to binding.btnLifetime
        ).forEach { (id, button) ->
            detailsMap[id]?.let { button.setupPlanButton(it, id) } ?: showToast("Không tìm thấy thông tin gói $id")
        }
    }

    private fun ViewGroup.setupPlanButton(plan: Any, productId: String) {
        val tv2 = findViewById<TextView>(R.id.tv2).apply { text = getTitleText(productId) }
        val tv3 = findViewById<TextView>(R.id.tv3)
        val tv4 = findViewById<TextView>(R.id.tv4)
        val tv5 = findViewById<TextView>(R.id.tv5)

        when (plan) {
            is ProductDetails -> setupProductDetails(plan, productId, tv3, tv4, tv5)
            is SkuDetails -> setupSkuDetails(plan, productId, tv3, tv4, tv5)
            else -> setupUnknownPlan(productId, tv2, tv3, tv4, tv5)
        }
    }

    private fun setupProductDetails(
        plan: ProductDetails,
        productId: String,
        tv3: TextView,
        tv4: TextView,
        tv5: TextView
    ) {
        if (productId == PRODUCT_ID_LIFETIME) {
            plan.oneTimePurchaseOfferDetails?.let { offer ->
                tv3.setVisibleText("Thanh toán một lần")
                tv4.text = offer.formattedPrice
                tv5.text = "trọn đời"
            } ?: setupEmptyLifetime(tv3, tv4, tv5)
        } else {
            val firstOffer = plan.subscriptionOfferDetails?.firstOrNull()
            val lastOffer = plan.subscriptionOfferDetails?.lastOrNull()

            firstOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                val periodText = parsePeriodToReadableText(phase.billingPeriod ?: "")
                tv3.setVisibleText(
                    when {
                        phase.priceAmountMicros == 0L -> "Miễn phí"
                        periodText != "Không rõ" -> "${phase.formattedPrice}/$periodText"
                        else -> "Giá không xác định"
                    }
                )
            } ?: tv3.setVisibleText("N/A")

            lastOffer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0 }?.let { phase ->
                val periodText = parsePeriodToReadableText(phase.billingPeriod ?: "")
                tv4.text = phase.formattedPrice
                tv5.text = if (periodText != "Không rõ") periodText else "Không rõ"
            } ?: setupEmptySubscription(tv4, tv5)
        }

        if (viewModel.selectedProductId.value == productId) {
            binding.tvSub.apply {
                text = getSubscriptionSummary(plan, productId)
                visibility = View.VISIBLE
            }
        }
    }

    private fun setupSkuDetails(
        plan: SkuDetails,
        productId: String,
        tv3: TextView,
        tv4: TextView,
        tv5: TextView
    ) {
        when {
            plan.freeTrialPeriod.isNotEmpty() -> {
                tv3.setVisibleText("Miễn phí ${parsePeriodToReadableText(plan.freeTrialPeriod)}")
                tv4.text = plan.price
                tv5.text = parsePeriodToReadableText(plan.subscriptionPeriod)
            }
            plan.introductoryPrice.isNotEmpty() -> {
                tv3.setVisibleText("${plan.introductoryPrice}/${parsePeriodToReadableText(plan.subscriptionPeriod)}")
                tv4.text = plan.price
                tv5.text = parsePeriodToReadableText(plan.subscriptionPeriod)
            }
            else -> {
                tv3.visibility = View.GONE
                tv4.text = plan.price
                tv5.text = if (productId == PRODUCT_ID_LIFETIME) "trọn đời" else parsePeriodToReadableText(plan.subscriptionPeriod)
            }
        }

        if (viewModel.selectedProductId.value == productId) {
            binding.tvSub.apply {
                text = getSubscriptionSummary(plan, productId)
                visibility = View.VISIBLE
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupUnknownPlan(productId: String, tv2: TextView, tv3: TextView, tv4: TextView, tv5: TextView) {
        tv2.text = "Không hỗ trợ"
        tv3.visibility = View.GONE
        tv4.text = ""
        tv5.text = "Không rõ"
        if (viewModel.selectedProductId.value == productId) {
            binding.tvSub.apply {
                text = "N/A"
                visibility = View.VISIBLE
            }
        }
    }

    private fun getSubscriptionSummary(plan: Any, productId: String): String {
        return when (plan) {
            is ProductDetails -> {
                if (productId == PRODUCT_ID_LIFETIME) {
                    plan.oneTimePurchaseOfferDetails?.formattedPrice?.let { "Thanh toán một lần: $it" } ?: "N/A"
                } else {
                    val firstOffer = plan.subscriptionOfferDetails?.firstOrNull()
                    val lastOffer = plan.subscriptionOfferDetails?.lastOrNull()

                    val firstPhase = firstOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                    val lastPhase = lastOffer?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros > 0 }

                    when {
                        firstPhase != null && lastPhase != null -> {
                            val firstPeriod = parsePeriodToReadableText(firstPhase.billingPeriod)
                            val lastPeriod = parsePeriodToReadableText(lastPhase.billingPeriod)
                            val firstPrice = if (firstPhase.priceAmountMicros == 0L) "Miễn phí" else firstPhase.formattedPrice
                            if (firstPeriod != "Không rõ" && lastPeriod != "Không rõ") {
                                "$firstPrice/$firstPeriod đầu tiên, sau đó ${lastPhase.formattedPrice}/$lastPeriod"
                            } else {
                                "Giá không xác định"
                            }
                        }
                        lastPhase != null -> {
                            val lastPeriod = parsePeriodToReadableText(lastPhase.billingPeriod)
                            if (lastPeriod != "Không rõ") "${lastPhase.formattedPrice}/$lastPeriod" else "Giá không xác định"
                        }
                        else -> "N/A"
                    }
                }
            }
            is SkuDetails -> {
                when {
                    plan.freeTrialPeriod.isNotEmpty() -> {
                        "Miễn phí ${parsePeriodToReadableText(plan.freeTrialPeriod)}, sau đó ${plan.price}/${parsePeriodToReadableText(plan.subscriptionPeriod)}"
                    }
                    plan.introductoryPrice.isNotEmpty() -> {
                        "${plan.introductoryPrice}/${parsePeriodToReadableText(plan.subscriptionPeriod)}, sau đó ${plan.price}/${parsePeriodToReadableText(plan.subscriptionPeriod)}"
                    }
                    else -> {
                        if (productId == PRODUCT_ID_LIFETIME) "Thanh toán một lần: ${plan.price}"
                        else "${plan.price}/${parsePeriodToReadableText(plan.subscriptionPeriod)}"
                    }
                }
            }
            else -> "N/A"
        }
    }

    private fun setupPlanSelection() {
        binding.btnMonthly.setOnClickListener { viewModel.selectPlan(PRODUCT_ID_MONTH) }
        binding.btnYearly.setOnClickListener { viewModel.selectPlan(PRODUCT_ID_YEAR) }
        binding.btnLifetime.setOnClickListener { viewModel.selectPlan(PRODUCT_ID_LIFETIME) }
        binding.btnStartFreeTrial.setOnClickListener { viewModel.purchaseSelectedProduct() }
    }

    private fun updatePlanSelectionBasedOnPurchases(purchasedProducts: Set<String>) {
        val buttons = listOf(
            binding.btnMonthly to PRODUCT_ID_MONTH,
            binding.btnYearly to PRODUCT_ID_YEAR,
            binding.btnLifetime to PRODUCT_ID_LIFETIME
        )

        if (purchasedProducts.contains(PRODUCT_ID_LIFETIME)) {
            buttons.forEach { (button, id) ->
                if (id == PRODUCT_ID_LIFETIME) button.disablePurchasedButton() else button.disable()
            }
            binding.btnStartFreeTrial.apply {
                isEnabled = false
                text = "Đã mua"
            }
            return
        }

        val currentSubscription = purchasedProducts.firstOrNull { it in ConstantsProductID.subsListProduct }

        buttons.forEach { (button, productId) ->
            when {
                purchasedProducts.contains(productId) -> {
                    button.disablePurchasedButton()
                    updatePurchasedBadge(productId)
                }
                currentSubscription != null && getSubscriptionLevel(productId) <= getSubscriptionLevel(currentSubscription) -> {
                    button.disable()
                }
                else -> {
                    button.updateSelection(viewModel.selectedProductId.value == productId)
                }
            }
        }

        binding.btnStartFreeTrial.text = if (currentSubscription != null) "Nâng cấp gói" else "Bắt đầu dùng thử miễn phí"
    }

    private fun updatePurchasedBadge(productId: String) {
        when (productId) {
            PRODUCT_ID_MONTH -> binding.bestDeal.updatePurchasedBadge()
            PRODUCT_ID_YEAR -> binding.bestDeal2.updatePurchasedBadge()
            PRODUCT_ID_LIFETIME -> binding.bestDeal3.updatePurchasedBadge()
        }
    }

    private fun updateSelectedPlanUi(productId: String) {
        listOf(
            binding.btnMonthly to PRODUCT_ID_MONTH,
            binding.btnYearly to PRODUCT_ID_YEAR,
            binding.btnLifetime to PRODUCT_ID_LIFETIME
        ).forEach { (button, id) ->
            if (viewModel.purchasedProducts.value.contains(id)) return@forEach
            button.updateSelection(id == productId)
            if (id == productId) {
                val details = viewModel.detailsMap.value[id]
                binding.tvSub.apply {
                    text = details?.let { getSubscriptionSummary(it, id) } ?: "N/A"
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun getSubscriptionLevel(productId: String) = when (productId) {
        PRODUCT_ID_MONTH -> 1
        PRODUCT_ID_YEAR -> 2
        PRODUCT_ID_LIFETIME -> 3
        else -> 0
    }

    private fun getTitleText(billingTitle: String) = when {
        billingTitle.contains(PRODUCT_ID_MONTH, true) -> getString(R.string.sub_week)
        billingTitle.contains(PRODUCT_ID_YEAR, true) -> getString(R.string.sub_year)
        billingTitle.contains(PRODUCT_ID_LIFETIME, true) -> getString(R.string.in_app)
        billingTitle.contains(PRODUCT_ID_FREE_TRIAL, true) -> "Dùng thử miễn phí"
        else -> billingTitle
    }

    private fun parsePeriodToReadableText(period: String): String {
        if (period.isEmpty()) return "Không rõ"
        return when {
            period.contains("D") -> "${period.filter { it.isDigit() }} ${getString(R.string.text_day)}"
            period.contains("W") -> "${period.filter { it.isDigit() }} ${getString(R.string.text_week)}"
            period.contains("M") -> "${period.filter { it.isDigit() }} ${getString(R.string.text_month)}"
            period.contains("Y") -> "${period.filter { it.isDigit() }} ${getString(R.string.text_year)}"
            else -> "Không rõ"
        }
    }

    private fun setupLimitedVersionText() {
        binding.tv6.apply {
            text = SpannableString("hoặc Sử dụng phiên bản giới hạn").apply {
                setSpan(createClickableSpan { showToast("Sử dụng phiên bản giới hạn") }, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun setupTermsAndPrivacyText() {
        val fullText = getString(R.string.full_text)
        val spannable = SpannableString(fullText).apply {
            setClickableText(fullText, "Điều khoản") { showToast("Đã nhấn Điều khoản") }
            setClickableText(fullText, "Chính sách bảo mật") { showToast("Đã nhấn Chính sách bảo mật") }
        }
        binding.tv7.apply {
            text = spannable
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
    }

    private fun SpannableString.setClickableText(fullText: String, keyword: String, onClick: () -> Unit) {
        val start = fullText.indexOf(keyword)
        if (start >= 0) {
            setSpan(createClickableSpanGray(onClick), start, start + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun createClickableSpan(onClick: () -> Unit) = object : ClickableSpan() {
        override fun onClick(widget: View) = onClick()
        override fun updateDrawState(ds: TextPaint) {
            ds.color = Color.parseColor("#C81212")
            ds.isUnderlineText = true
        }
    }

    private fun createClickableSpanGray(onClick: () -> Unit) = object : ClickableSpan() {
        override fun onClick(widget: View) = onClick()
        override fun updateDrawState(ds: TextPaint) {
            ds.color = Color.parseColor("#9E9E9E")
            ds.isUnderlineText = true
        }
    }

    private fun ViewGroup.disablePurchasedButton() {
        isEnabled = false
        findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(R.drawable.ic_uncheck)
        setBackgroundResource(R.drawable.bg_disable)
        alpha = 0.8f
    }

    private fun ViewGroup.updateSelection(isSelected: Boolean) {
        setBackgroundResource(if (isSelected) R.drawable.bg_selected_paywall else R.drawable.bg_unselected_paywall)
        findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(if (isSelected) R.drawable.ic_checked else R.drawable.ic_uncheck)
        isEnabled = true
        alpha = 1.0f
    }

    private fun View.disable() {
        isEnabled = false
        alpha = 0.5f
    }

    private fun TextView.updatePurchasedBadge() {
        text = "Đã mua"
        setBackgroundResource(R.drawable.bg_disable)
        visibility = View.VISIBLE
    }

    private fun TextView.setVisibleText(text: String) {
        this.text = text
        visibility = View.VISIBLE
    }

    private fun setupEmptyLifetime(tv3: TextView, tv4: TextView, tv5: TextView) {
        tv3.visibility = View.GONE
        tv4.text = ""
        tv5.text = "trọn đời"
    }

    private fun setupEmptySubscription(tv4: TextView, tv5: TextView) {
        tv4.text = ""
        tv5.text = "Không rõ"
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.endBilling()
    }
}
package com.eco.musicplayer.audioplayer.music

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.eco.musicplayer.audioplayer.music.databinding.ActivityPaywallBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaywallActivity : AppCompatActivity(), BillingListener {
    private var _billingManager: BillingManager? = null
    private val billingManager: BillingManager
        get() {
            if (_billingManager == null) {
                _billingManager = BillingManager(this, application, this)
            }
            return _billingManager!!
        }
    private lateinit var binding: ActivityPaywallBinding
    private var productDetailsMap: Map<String, ProductDetails> = emptyMap()
    private var selectedProductId: String? = null
    private val _purchasedProducts = MutableStateFlow<Set<String>>(emptySet())
    private val purchasedProducts: StateFlow<Set<String>> = _purchasedProducts
    private val PREFS_NAME = "BillingPrefs"
    private val PURCHASED_PRODUCTS_KEY = "purchasedProducts"
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val uiScope = lifecycleScope


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initUI()
        loadInitialData()

    }

    private fun initUI() {
        setupInitialSelectedPlan()
        setupLimitedVersionText()
        setupTermsAndPrivacyText()
        setupPlanSelection()
        setupBillingButton() // Gọi setupBillingButton ở đây, logic sẽ chạy khi có productDetails
    }

    /**
     * Tải các sản phẩm đã mua từ SharedPreferences, sau đó quan sát dữ liệu billing.
     */
    private fun loadInitialData() {
        // Tải sản phẩm đã mua bất đồng bộ
        uiScope.launch(Dispatchers.IO) {
            loadPurchasedProductsFromPrefs()
            // Trì hoãn một chút trước khi bắt đầu theo dõi billing data
            observeBillingData()
            //observeFreeTrialAvailability()
        }
    }


    /**
     * Lấy danh sách gói đã mua từ bộ nhớ thiết bị và cập nhật lại UI trên luồng chính.
     */
    private suspend fun loadPurchasedProductsFromPrefs() {
        val savedProducts = prefs.getStringSet(PURCHASED_PRODUCTS_KEY, emptySet()) ?: emptySet()
        withContext(Dispatchers.Main) {
            _purchasedProducts.value = savedProducts
            updatePlanSelectionBasedOnPurchases(savedProducts)
        }
    }

    /**
     * Theo dõi dữ liệu billing từ BillingManager và cập nhật productDetailsMap.
     */
    private fun observeBillingData() {
        uiScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                billingManager.productDetailsMap.collectLatest { map ->
                    productDetailsMap = map
                    // Giờ đây setupBillingButton sẽ có dữ liệu
                    setupPlanTexts() // Gọi ở đây khi đã có dữ liệu
                    setupBillingButton() // Cập nhật lại nút thanh toán
                    observeFreeTrialAvailability()
                }
            }
        }
    }

    // Phân cấp cho các gói
    private fun getSubscriptionLevel(productId: String): Int {
        return when (productId) {
            PRODUCT_ID_MONTH -> 1
            PRODUCT_ID_YEAR -> 2
            PRODUCT_ID_LIFETIME -> 3
            else -> 0 // Không phải gói subscription
        }
    }

    private fun isUpgradeAllowed(currentProductId: String, newProductId: String): Boolean {
        return getSubscriptionLevel(newProductId) > getSubscriptionLevel(currentProductId)
    }

    /**
     * Gắn sự kiện click cho nút “Start Free Trial” để bắt đầu quy trình thanh toán.
     * Nếu đang có gói khác thì gọi nâng cấp (launchBillingFlowForUpgrade()).
     */
    private fun setupBillingButton() {
        binding.btnStartFreeTrial.setOnClickListener {
            selectedProductId?.let { newProductId ->
                productDetailsMap[newProductId]?.let { productDetails ->
                    uiScope.launch {
                        val currentSubscription = purchasedProducts.value.firstOrNull {
                            it in Constants.subsListProduct
                        }

                        if (currentSubscription != null && newProductId in Constants.subsListProduct
                        ) {
                            // Kiểm tra nếu là nâng cấp
                            if (isUpgradeAllowed(currentSubscription, newProductId)) {
                                billingManager.launchBillingFlowForUpgrade(
                                    productDetails,
                                    currentSubscription
                                )
                            } else {
                                Toast.makeText(
                                    applicationContext,
                                    "Bạn chỉ có thể nâng cấp lên gói cao hơn!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            // Nếu không phải subscription hoặc chưa có gói hiện tại, cho phép mua
                            billingManager.launchBillingFlow(productDetails)
                        }

                        /*if (currentSubscription != null && newProductId in listOf("vip_month", "vip_year")) {
                            if (isUpgradeAllowed(currentSubscription, newProductId)) {
                                billingManager.launchBillingFlowForUpgrade(productDetails, currentSubscription)
                            } else if (isDowngrade(currentSubscription, newProductId)) {
                                // Downgrade: chỉ áp dụng ở kỳ tiếp theo
                                billingManager.launchBillingFlow(productDetails)
                                Toast.makeText(
                                    applicationContext,
                                    "Gói mới sẽ được áp dụng sau khi gói hiện tại kết thúc.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    applicationContext,
                                    "Không thể chuyển đổi giữa các gói này!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }*/

                    }
                } ?: run {
                    Toast.makeText(
                        applicationContext,
                        "Không tìm thấy thông tin gói!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } ?: run {
                Toast.makeText(
                    applicationContext,
                    "Vui lòng chọn gói trước!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    /*private fun isDowngrade(currentProductId: String, newProductId: String): Boolean {
        val priority = mapOf(
            "vip_year" to 2,
            "vip_month" to 1
        )

        val currentPriority = priority[currentProductId] ?: 0
        val newPriority = priority[newProductId] ?: 0

        return newPriority < currentPriority
    }*/

    /**
     * Dựa trên các gói đã mua, vô hiệu hóa nút tương ứng và cập nhật lại giao diện,
     * hiển thị “Upgrade” nếu người dùng chọn gói cao hơn.
     */
    private fun updatePlanSelectionBasedOnPurchases(currentPurchasedProducts: Set<String>) {
        val buttons = listOf(
            binding.btnMonthly to PRODUCT_ID_MONTH,
            binding.btnYearly to PRODUCT_ID_YEAR,
            binding.btnLifetime to PRODUCT_ID_LIFETIME
        )

        // Kiểm tra nếu đã mua Lifetime -> Disable tất cả gói khác (không ẩn)
        if (currentPurchasedProducts.contains(PRODUCT_ID_LIFETIME)) {
            buttons.forEach { (button, productId) ->
                if (productId == PRODUCT_ID_LIFETIME) {
                    button.disablePurchasedButton() // Áp dụng hàm có sẵn
                } else {
                    button.isEnabled = false // Chỉ disable, không ẩn
                    button.alpha = 0.5f // Làm mờ nhẹ để phân biệt
                }
            }
            binding.btnStartFreeTrial.apply {
                isEnabled = false
                text = "Purchased"
            }
            return
        }

        // Xử lý các gói subscription thông thường
        val currentSubscription = currentPurchasedProducts.firstOrNull { it in listOf("vip_month", "vip_year") }

        buttons.forEach { (button, productId) ->
            when {
                // Nếu gói đã mua -> disable (dùng hàm có sẵn)
                currentPurchasedProducts.contains(productId) -> {
                    button.disablePurchasedButton()
                    when (productId) {
                        PRODUCT_ID_MONTH -> {
                            binding.bestDeal.apply {
                                text = "Purchased"
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
                // Nếu có gói hiện tại VÀ gói này thấp hơn -> disable (không ẩn)
                currentSubscription != null && getSubscriptionLevel(productId) <= getSubscriptionLevel(currentSubscription) -> {
                    button.isEnabled = false
                    button.alpha = 0.5f
                }
                // Gói có thể chọn (nâng cấp hoặc mua mới)
                else -> {
                    val isSelected = selectedProductId == productId
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

        // Cập nhật text nút thanh toán
        binding.btnStartFreeTrial.text = if (currentSubscription != null) "Upgrade Plan" else "Start Free Trial"
    }

    private fun setupInitialSelectedPlan() {
        uiScope.launch {
            purchasedProducts.collectLatest { products ->
                if (products.isEmpty()) {
                    selectPlan(binding.btnMonthly)
                    selectedProductId = PRODUCT_ID_MONTH
                } else {
                    selectedProductId = null
                    updatePlanSelectionBasedOnPurchases(products)
                }
            }
        }
    }

    /**
     * Gắn sự kiện click cho 3 nút gói VIP (tháng, năm, trọn đời),
     * nếu chưa mua thì chọn gói đó và cập nhật selectedProductId.
     */
    private fun setupPlanSelection() {
        binding.btnMonthly.setOnClickListener {
            if (!purchasedProducts.value.contains(PRODUCT_ID_MONTH)) {
                selectPlan(binding.btnMonthly)
                selectedProductId = PRODUCT_ID_MONTH
            }
        }

        binding.btnYearly.setOnClickListener {
            if (!purchasedProducts.value.contains(PRODUCT_ID_YEAR)) {
                selectPlan(binding.btnYearly)
                selectedProductId = PRODUCT_ID_YEAR
            }
        }

        binding.btnLifetime.setOnClickListener {
            if (!purchasedProducts.value.contains(PRODUCT_ID_LIFETIME)) {
                selectPlan(binding.btnLifetime)
                selectedProductId = PRODUCT_ID_LIFETIME
            }
        }
    }

    private fun selectPlan(selectedBtn: ViewGroup) {
        val buttons = listOf(
            binding.btnMonthly to PRODUCT_ID_MONTH,
            binding.btnYearly to PRODUCT_ID_YEAR,
            binding.btnLifetime to PRODUCT_ID_LIFETIME
        )

        for ((button, productId) in buttons) {
            if (purchasedProducts.value.contains(productId)) {
                continue
            }
            val isSelected = button == selectedBtn
            val background =
                if (isSelected) R.drawable.bg_selected_paywall else R.drawable.bg_unselected_paywall
            val icon = if (isSelected) R.drawable.ic_checked else R.drawable.ic_uncheck
            button.setBackgroundResource(background)
            button.findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(icon)
        }
    }
    // Hủy gói life time



    // Xử lý Free Trial
    private fun observeFreeTrialAvailability() {
        if (billingManager.isEligibleForTrial(PRODUCT_ID_FREE_TRIAL)) {
            val trialInfo = billingManager.getTrialTimeInfo(PRODUCT_ID_FREE_TRIAL)

            if (trialInfo.isInTrial) {
                val remainingDays = trialInfo.remainingDays
                val totalTrialDays = trialInfo.totalTrialDays
                Log.d("FreeTrial Available", "Còn $remainingDays/$totalTrialDays ngày dùng thử")
            }
        } else {
            Log.d("FreeTrial Available", "Bạn không còn lượt dùng thử")
        }
    }


    @SuppressLint("SetTextI18n")
    private fun setupPlanTexts() {
        val productDetailsMap = billingManager.productDetailsMap.value

        // Xử lý gói monthly (subscription)
        productDetailsMap[PRODUCT_ID_MONTH]?.let { monthlyPlan ->
            binding.btnMonthly.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(monthlyPlan.productId) // "VIP Month"

                /*// Lặp qua các offer có sẵn cho gói này
                monthlyPlan.subscriptionOfferDetails?.forEach { offerDetails ->
                    val offerId = offerDetails.offerId // Lấy offer ID
                    val offerToken = offerDetails.offerToken // Lấy offer Token quan trọng để launch billing flow

                    // Hiển thị thông tin offer (ví dụ: phase giá đầu tiên)
                    offerDetails.pricingPhases.pricingPhaseList.firstOrNull()?.let { phase ->
                        val offerTextView = TextView(context)
                        offerTextView.text = "Offer: $offerId - ${phase.formattedPrice} for ${phase.billingPeriod} ${phase.billingCycleCount}"
                        // Thêm offerTextView vào layout của nút (bạn có thể cần điều chỉnh layout)
                        addView(offerTextView)
                    }
                }*/

                monthlyPlan.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv4).text = phase.formattedPrice // "28,080đ"
                    findViewById<TextView>(R.id.tv5).text = "per ${getPeriodText(phase.billingPeriod)}"
                }
            }
        }

        // Xử lý gói yearly (subscription)
        productDetailsMap[PRODUCT_ID_YEAR]?.let { yearlyPlan ->
            binding.btnYearly.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(yearlyPlan.productId) // "VIP Year"

                yearlyPlan.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv4).text = phase.formattedPrice // "46,800đ"
                    findViewById<TextView>(R.id.tv5).text = "per ${getPeriodText(phase.billingPeriod)}"
                }
            }
        }

        // Xử lý gói lifetime (one-time)
        productDetailsMap[PRODUCT_ID_LIFETIME]?.let { lifetimePlan ->
            binding.btnLifetime.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(lifetimePlan.productId) // "VIP Lifetime"
                findViewById<TextView>(R.id.tv3).text = "One-time payment"

                lifetimePlan.oneTimePurchaseOfferDetails?.let { offer ->
                    findViewById<TextView>(R.id.tv4).text = offer.formattedPrice // "7,000đ"
                    findViewById<TextView>(R.id.tv5).text = "forever"
                }
            }
        }
    }

    // Hàm helper để chuyển billingPeriod thành text dễ đọc
    private fun getPeriodText(billingPeriod: String): String {
        return when {
            billingPeriod.contains("P1M") -> "month"
            billingPeriod.contains("P1Y") -> "year"
            billingPeriod.contains("P3M") -> "3 months"
            billingPeriod.contains("P6M") -> "6 months"
            else -> billingPeriod
        }
    }

    private fun getTitleText(billingTitle: String): String {
        return when {
            billingTitle.contains(PRODUCT_ID_MONTH, ignoreCase = true) -> "Vip Month"
            billingTitle.contains(PRODUCT_ID_YEAR, ignoreCase = true) -> "Vip Year"
            billingTitle.contains(PRODUCT_ID_LIFETIME, ignoreCase = true) -> "Vip Lifetime"
            else -> billingTitle
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

    override fun onPurchaseSuccess(purchase: Purchase) {
        Toast.makeText(this, "Purchase successful!", Toast.LENGTH_SHORT).show()

        val purchasedProduct = purchase.products.firstOrNull()
        purchasedProduct?.let { productId ->
            uiScope.launch(Dispatchers.IO) {
                val currentProducts = prefs.getStringSet(PURCHASED_PRODUCTS_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
                currentProducts.add(productId)
                prefs.edit().putStringSet(PURCHASED_PRODUCTS_KEY, currentProducts).apply()
                withContext(Dispatchers.Main) {
                    _purchasedProducts.value = currentProducts
                    updatePlanSelectionBasedOnPurchases(currentProducts)
                }
            }

            when (productId) {
                "vip_month" -> binding.btnMonthly.disablePurchasedButton()
                "vip_year" -> binding.btnYearly.disablePurchasedButton()
                "musicplayer_vip_lifetime" -> binding.btnLifetime.disablePurchasedButton()
            }
            if (productId == "vip_month") {
                binding.bestDeal.apply {
                    text = "Purchased"
                    setBackgroundResource(R.drawable.bg_disable)
                }
            }
        }
    }

    override fun onPurchaseError(errorCode: Int, errorMessage: String) {
        Toast.makeText(this, "Purchase error: $errorMessage (Code: $errorCode)", Toast.LENGTH_LONG)
            .show()
    }

    override fun onPurchaseCancelled() {
        Toast.makeText(this, "Purchase cancelled", Toast.LENGTH_SHORT).show()
    }

    /**
     * Vô hiệu hóa một nút gói VIP đã mua: làm mờ nút, không cho click, đổi biểu tượng radio.
     */
    private fun ViewGroup.disablePurchasedButton() {
        isEnabled = false
        findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(R.drawable.ic_uncheck)
        setBackgroundResource(R.drawable.bg_disable)
        alpha = 0.8f
    }
}
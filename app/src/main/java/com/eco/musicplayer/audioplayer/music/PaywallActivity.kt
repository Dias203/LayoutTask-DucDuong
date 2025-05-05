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
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.eco.musicplayer.audioplayer.music.databinding.ActivityPaywallBinding
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
        setupPlanTexts()
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
            delay(200)
            observeBillingData()
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
                }
            }
        }
    }

    private fun getSubscriptionLevel(productId: String): Int {
        return when (productId) {
            "vip_month" -> 1
            "vip_year" -> 2
            "musicplayer_vip_lifetime" -> 3
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
                            it in listOf("vip_month", "vip_year")
                        }

                        if (currentSubscription != null && newProductId in listOf(
                                "vip_month",
                                "vip_year"
                            )
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

    /**
     * Dựa trên các gói đã mua, vô hiệu hóa nút tương ứng và cập nhật lại giao diện,
     * hiển thị “Upgrade” nếu người dùng chọn gói cao hơn.
     */
    private fun updatePlanSelectionBasedOnPurchases(currentPurchasedProducts: Set<String>) {
        val buttons = listOf(
            binding.btnMonthly to "vip_month",
            binding.btnYearly to "vip_year",
            binding.btnLifetime to "musicplayer_vip_lifetime"
        )

        // Kiểm tra nếu đã mua Lifetime -> Disable tất cả gói khác (không ẩn)
        if (currentPurchasedProducts.contains("musicplayer_vip_lifetime")) {
            buttons.forEach { (button, productId) ->
                if (productId == "musicplayer_vip_lifetime") {
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
                        "vip_month" -> {
                            binding.bestDeal.apply {
                                text = "Purchased"
                                setBackgroundResource(R.drawable.bg_disable)
                            }
                        }
                        "vip_year" -> {
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
                    selectedProductId = "vip_month"
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
            if (!purchasedProducts.value.contains("vip_month")) {
                selectPlan(binding.btnMonthly)
                selectedProductId = "vip_month"
            }
        }

        binding.btnYearly.setOnClickListener {
            if (!purchasedProducts.value.contains("vip_year")) {
                selectPlan(binding.btnYearly)
                selectedProductId = "vip_year"
            }
        }

        binding.btnLifetime.setOnClickListener {
            if (!purchasedProducts.value.contains("musicplayer_vip_lifetime")) {
                selectPlan(binding.btnLifetime)
                selectedProductId = "musicplayer_vip_lifetime"
            }
        }
    }

    private fun selectPlan(selectedBtn: ViewGroup) {
        val buttons = listOf(
            binding.btnMonthly to "vip_month",
            binding.btnYearly to "vip_year",
            binding.btnLifetime to "musicplayer_vip_lifetime"
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





    @SuppressLint("SetTextI18n")
    private fun setupPlanTexts() {
        binding.btnYearly.apply {
            findViewById<TextView>(R.id.tv2).text = "Vip Year"
            findViewById<TextView>(R.id.tv4).text = "46,0800đ"
            findViewById<TextView>(R.id.tv5).text = "per year"
        }

        binding.btnLifetime.apply {
            findViewById<TextView>(R.id.tv2).text = "Vip Lifetime"
            findViewById<TextView>(R.id.tv3).text = "One-time payment"
            findViewById<TextView>(R.id.tv4).text = "7,000đ"
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
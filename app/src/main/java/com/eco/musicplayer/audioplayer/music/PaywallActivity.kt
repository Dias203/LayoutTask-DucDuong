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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PaywallActivity : AppCompatActivity(), BillingListener {
    private val billingManager by lazy {
        BillingManager(this, application, this)
    }
    private lateinit var binding: ActivityPaywallBinding
    private var productDetailsMap: Map<String, ProductDetails> = emptyMap()
    private var selectedProductId: String? = null
    private val purchasedProducts = mutableSetOf<String>()
    private val PREFS_NAME = "BillingPrefs"
    private val PURCHASED_PRODUCTS_KEY = "purchasedProducts"
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initUI()
        loadData()

        /**
        // Lắng nghe dữ liệu ProductDetails từ BillingManager
        lifecycleScope.launch {
            billingManager.productDetailsMap.collectLatest { map ->
                productDetailsMap = map // Gán dữ liệu product details mới nhất

                if (map.isNotEmpty()) {
                    binding.btnStartFreeTrial.setOnClickListener {
                        selectedProductId?.let { productId ->
                            // Nếu đã chọn plan, lấy ProductDetails tương ứng
                            productDetailsMap[productId]?.let { productDetails ->
                                // Bắt đầu quy trình thanh toán sản phẩm đã chọn
                                billingManager.launchBillingFlow(productDetails)
                            } ?: run {
                                // Nếu không tìm thấy ProductDetails, báo lỗi
                                Toast.makeText(
                                    applicationContext,
                                    "Product details not found for $productId",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } ?: run {
                            // Nếu chưa chọn plan nào, yêu cầu người dùng chọn
                            Toast.makeText(
                                applicationContext,
                                "Please select a plan first",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        */
    }
    private fun initUI() {
        binding = ActivityPaywallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInitialSelectedPlan()
        setupPlanTexts()
        setupLimitedVersionText()
        setupTermsAndPrivacyText()
        setupPlanSelection()
    }
    private fun loadData() {
        loadPurchasedProducts()
        observeBillingData()
    }

    private fun observeBillingData() {
        // Trì hoãn khởi tạo BillingManager
        lifecycleScope.launch {
            //đảm bảo rằng coroutine sẽ chỉ chạy khi Lifecycle ở trạng thái STARTED và sẽ tự động hủy khi trạng thái thay đổi,
            // giúp tiết kiệm tài nguyên hệ thống
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                billingManager.productDetailsMap.collectLatest { map ->
                    productDetailsMap = map
                    setupBillingButton(map)
                }
            }
        }
    }

    private fun loadPurchasedProducts() {
        lifecycleScope.launch {
            val savedProducts = prefs.getStringSet(PURCHASED_PRODUCTS_KEY, emptySet()) ?: emptySet()
            purchasedProducts.addAll(savedProducts)
            updatePlanSelectionBasedOnPurchases()
        }
    }
    private fun setupBillingButton(map: Map<String, ProductDetails>) {
        if (map.isNotEmpty()) {
            binding.btnStartFreeTrial.setOnClickListener {
                selectedProductId?.let { productId ->
                    // Tìm gói subscription hiện tại
                    val currentSubscription = purchasedProducts.firstOrNull { it in listOf("vip_month", "vip_year") }

                    productDetailsMap[productId]?.let { productDetails ->
                        if (currentSubscription != null && productId in listOf("vip_month", "vip_year") && productId != currentSubscription) {
                            // Gọi hàm nâng cấp
                            billingManager.launchBillingFlowForUpgrade(productDetails, currentSubscription)
                        } else {
                            // Gọi hàm mua mới
                            billingManager.launchBillingFlow(productDetails)
                        }
                    } ?: run {
                        Toast.makeText(
                            applicationContext,
                            "Product details not found for $productId",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } ?: run {
                    Toast.makeText(
                        applicationContext,
                        "Please select a plan first",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
    private fun updatePlanSelectionBasedOnPurchases() {
        val buttons = listOf(
            binding.btnMonthly to "vip_month",
            binding.btnYearly to "vip_year",
            binding.btnLifetime to "musicplayer_vip_lifetime"
        )

        for ((button, productId) in buttons) {
            if (purchasedProducts.contains(productId)) {
                button.disablePurchasedButton()
                if (productId == "vip_month") {
                    binding.bestDeal.apply {
                        text = "Purchased"
                        setBackgroundResource(R.drawable.bg_disable)
                    }
                }
            } else {
                // Nếu chưa mua, có thể cần reset lại trạng thái unselected nếu cần
                val isCurrentlySelected = selectedProductId == productId
                val background = if (isCurrentlySelected) R.drawable.bg_selected_paywall else R.drawable.bg_unselected_paywall
                val icon = if (isCurrentlySelected) R.drawable.ic_checked else R.drawable.ic_uncheck
                button.setBackgroundResource(background)
                button.findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(icon)
                button.isEnabled = true
                button.alpha = 1.0f
            }
        }
    }

    */
    private fun updatePlanSelectionBasedOnPurchases() {
        val buttons = listOf(
            binding.btnMonthly to "vip_month",
            binding.btnYearly to "vip_year",
            binding.btnLifetime to "musicplayer_vip_lifetime"
        )

        // Tìm gói subscription hiện tại
        var currentSubscription: String? = null
        for ((_, productId) in buttons) {
            if (purchasedProducts.contains(productId) && productId in listOf("vip_month", "vip_year")) {
                currentSubscription = productId
                break
            }
        }

        // Cập nhật giao diện
        for ((button, productId) in buttons) {
            if (purchasedProducts.contains(productId)) {
                button.disablePurchasedButton()
                if (productId == "vip_month") {
                    binding.bestDeal.apply {
                        text = "Purchased"
                        setBackgroundResource(R.drawable.bg_disable)
                    }
                }
            } else {
                val isCurrentlySelected = selectedProductId == productId
                val background = if (isCurrentlySelected) R.drawable.bg_selected_paywall else R.drawable.bg_unselected_paywall
                val icon = if (isCurrentlySelected) R.drawable.ic_checked else R.drawable.ic_uncheck
                button.setBackgroundResource(background)
                button.findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(icon)
                button.isEnabled = true
                button.alpha = 1.0f

                // Nếu có gói subscription hiện tại, hiển thị tùy chọn nâng cấp
                if (currentSubscription != null && productId in listOf("vip_month", "vip_year") && productId != currentSubscription) {
                    button.findViewById<TextView>(R.id.tv2).text = "Upgrade to ${productId.replace("vip_", "").capitalize()}"
                }
            }
        }

        // Cập nhật văn bản nút Start Free Trial
        if (currentSubscription != null) {
            binding.btnStartFreeTrial.text = "Upgrade Plan"
        } else {
            binding.btnStartFreeTrial.text = "Start Free Trial"
        }
    }


    private fun setupInitialSelectedPlan() {
        if (purchasedProducts.isEmpty()) {
            // Nếu chưa mua gì, chọn mặc định gói tháng
            selectPlan(binding.btnMonthly)
            selectedProductId = "vip_month"
        } else {
            // Nếu đã mua, không chọn mặc định
            selectedProductId = null // Reset selectedProductId
            updatePlanSelectionBasedOnPurchases() // Cập nhật giao diện để hiển thị trạng thái đã mua
        }
    }

    /**
    private fun setupPlanSelection() {
        binding.btnMonthly.setOnClickListener {
            selectPlan(binding.btnMonthly)
            selectedProductId = "vip_month"
        }

        binding.btnYearly.setOnClickListener {
            selectPlan(binding.btnYearly)
            selectedProductId = "vip_year"
        }

        binding.btnLifetime.setOnClickListener {
            selectPlan(binding.btnLifetime)
            selectedProductId = "musicplayer_vip_lifetime"
        }
    }
    */
    private fun setupPlanSelection() {
        binding.btnMonthly.setOnClickListener {
            if (!purchasedProducts.contains("vip_month")) {
                selectPlan(binding.btnMonthly)
                selectedProductId = "vip_month"
            }
        }

        binding.btnYearly.setOnClickListener {
            if (!purchasedProducts.contains("vip_year")) {
                selectPlan(binding.btnYearly)
                selectedProductId = "vip_year"
            }
        }

        binding.btnLifetime.setOnClickListener {
            if (!purchasedProducts.contains("musicplayer_vip_lifetime")) {
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
            if (purchasedProducts.contains(productId)) {
                // Nếu gói này đã mua => không update giao diện
                continue
            }
            val isSelected = button == selectedBtn
            val background = if (isSelected) R.drawable.bg_selected_paywall else R.drawable.bg_unselected_paywall
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
        if (purchasedProduct != null) {
            purchasedProducts.add(purchasedProduct)
            prefs.edit().putStringSet(PURCHASED_PRODUCTS_KEY, purchasedProducts).apply()
        }

        when (purchasedProduct) {
            "vip_month" -> {
                binding.btnMonthly.disablePurchasedButton()
                binding.bestDeal.apply {
                    text = "Purchased"
                    setBackgroundResource(R.drawable.bg_disable)
                }
            }
            "vip_year" -> binding.btnYearly.disablePurchasedButton()
            "musicplayer_vip_lifetime" -> binding.btnLifetime.disablePurchasedButton()
        }
        // Cập nhật lại giao diện sau khi nâng cấp
        updatePlanSelectionBasedOnPurchases()
    }

    override fun onPurchaseError(errorCode: Int, errorMessage: String) {
        Toast.makeText(this, "Purchase error: $errorMessage (Code: $errorCode)", Toast.LENGTH_LONG)
            .show()
        // Xử lý logic khi mua hàng lỗi
    }

    override fun onPurchaseCancelled() {
        Toast.makeText(this, "Purchase cancelled", Toast.LENGTH_SHORT).show()
        // Xử lý logic khi người dùng hủy giao dịch
    }

    private fun ViewGroup.disablePurchasedButton() {
        isEnabled = false
        findViewById<AppCompatImageView>(R.id.radioButton1).setImageResource(R.drawable.ic_uncheck)
        setBackgroundResource(R.drawable.bg_disable)
        alpha = 0.8f
    }
}
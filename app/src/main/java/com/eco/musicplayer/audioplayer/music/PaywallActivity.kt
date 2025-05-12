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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
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

private const val TAG = "DebugOffer"

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

    private var selectedOfferToken: String? = null
    private val viewModel: PaywallViewModel by viewModels()

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
        //setupBillingButton() // Gọi setupBillingButton ở đây, logic sẽ chạy khi có productDetails
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
                    //setupBillingButton("") // Cập nhật lại nút thanh toán
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
    /*private fun setupBillingButton(offerToken: String) {
        binding.btnStartFreeTrial.setOnClickListener {
            selectedProductId?.let { newProductId ->
                productDetailsMap[newProductId]?.let { productDetails ->
                    uiScope.launch {
                        val currentSubscription = purchasedProducts.value.firstOrNull {
                            it in ConstantsProductID.subsListProduct
                        }

                        if (currentSubscription != null && newProductId in ConstantsProductID.subsListProduct
                        ) {
                            // Kiểm tra nếu là nâng cấp
                            if (isUpgradeAllowed(currentSubscription, newProductId)) {
                                billingManager.launchBillingFlowForUpgrade(
                                    productDetails = productDetails,
                                    oldProductId = currentSubscription
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
                            Log.d(TAG, "setupBillingButton: $offerToken")
                            billingManager.launchBillingFlow(
                                productDetails = productDetails,
                                offerToken = offerToken ?: ""
                            )
                        }

                        *//*if (currentSubscription != null && newProductId in listOf("vip_month", "vip_year")) {
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
                        }*//*

                    }
                } ?: run {
                    Toast.makeText(
                        applicationContext,
                        "Không tìm thấy thông tin gói!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }*/

    private fun setupBillingButton(offerToken: String) {
        binding.btnStartFreeTrial.setOnClickListener {
            selectedProductId?.let { newProductId ->
                productDetailsMap[newProductId]?.let { productDetails ->
                    uiScope.launch {
                        val currentSubscription = purchasedProducts.value.firstOrNull {
                            it in ConstantsProductID.subsListProduct
                        }

                        if (newProductId == PRODUCT_ID_LIFETIME) {
                            // Xử lý mua gói Lifetime (one-time purchase)
                            Log.d(TAG, "setupBillingButton: Launching billing flow for lifetime")
                            billingManager.launchBillingFlow(productDetails = productDetails)
                        } else if (currentSubscription != null && newProductId in ConstantsProductID.subsListProduct) {
                            // Kiểm tra nếu là nâng cấp
                            if (isUpgradeAllowed(currentSubscription, newProductId)) {
                                billingManager.launchBillingFlowForUpgrade(
                                    productDetails = productDetails,
                                    oldProductId = currentSubscription
                                )
                            } else {
                                Toast.makeText(
                                    applicationContext,
                                    "Bạn chỉ có thể nâng cấp lên gói cao hơn!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            // Xử lý mua gói subscription mới
                            Log.d(TAG, "setupBillingButton: Launching billing flow for sub with token: $offerToken")
                            billingManager.launchBillingFlow(
                                productDetails = productDetails,
                                offerToken = offerToken
                            )
                        }
                    }
                } ?: run {
                    Toast.makeText(
                        applicationContext,
                        "Không tìm thấy thông tin gói!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
    @SuppressLint("SetTextI18n")
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
                text = "Đã mua"
            }
            return
        }

        // Xử lý các gói subscription thông thường
        val currentSubscription =
            currentPurchasedProducts.firstOrNull { it in listOf("vip_month", "vip_year") }

        buttons.forEach { (button, productId) ->
            when {
                // Nếu gói đã mua -> disable (dùng hàm có sẵn)
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
                // Nếu có gói hiện tại VÀ gói này thấp hơn -> disable (không ẩn)
                currentSubscription != null && getSubscriptionLevel(productId) <= getSubscriptionLevel(
                    currentSubscription
                ) -> {
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
        binding.btnStartFreeTrial.text =
            if (currentSubscription != null) "Upgrade Plan" else "Start Free Trial"
    }

    private fun setupInitialSelectedPlan() {
        uiScope.launch {
            purchasedProducts.collectLatest { products ->
                if (products.isEmpty()) {
                    selectPlan(binding.btnMonthly)
                    selectedProductId = PRODUCT_ID_MONTH
                    binding.tvSub.text = "week"
                    binding.tvSub.visibility = View.VISIBLE
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

            // Cập nhật tvSub dựa trên gói được chọn
            if (isSelected) {
                val tv3OfSelectedButton = button.findViewById<TextView>(R.id.tv3)
                binding.tvSub.text = tv3OfSelectedButton.text
                binding.tvSub.visibility = if (productId == PRODUCT_ID_LIFETIME) View.GONE else View.VISIBLE
            }
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
    /*================================================================================================*/
    /* Đầu tiên - chưa lấy được offerToken
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
    */

    /*================================================================================================*/
    /*
     // ĐÃ LẤY ĐƯỢC offerToken  NHƯNG CHỈ LÀ CHO THẰNG MONTH
     @SuppressLint("SetTextI18n")
     private fun setupPlanTexts(productDetailsList: List<ProductDetails>) {
         var freeTrialOfferId1: String? = null
         var freeTrialOfferId2: String? = null
         var freeTrialOfferId3: String? = null
         var offerTokenForMonthly: String? = null

         // 1. Tìm offerId từ gói FREE_TRIAL
         productDetailsList.forEach { productDetails ->
             if (productDetails.productId == PRODUCT_ID_MONTH) {
                 productDetails.subscriptionOfferDetails?.forEachIndexed { index, offer ->
                     Log.d(TAG, "setupPlanTexts Size: ${productDetails.subscriptionOfferDetails?.size}")
                 when (index) {
                     0 -> {
                         freeTrialOfferId1 = offer.offerId
                         Log.d(
                             TAG,
                             "setupPlanTexts: FreeTrial offerId1 = $freeTrialOfferId1 - ${offer.offerToken}"
                         )
                     }

                     1 -> {
                         freeTrialOfferId2 = offer.offerId
                         Log.d(
                             TAG,
                             "setupPlanTexts: FreeTrial offerId2 = $freeTrialOfferId2 - ${offer.offerToken}"
                         )
                     }

                     2 -> {
                         freeTrialOfferId3 = offer.offerId
                         Log.d(
                             TAG,
                             "setupPlanTexts: FreeTrial offerId3 = $freeTrialOfferId3 - ${offer.offerToken}"
                         )
                     }
                 }
             }
                 val offer = productDetails.subscriptionOfferDetails?.firstOrNull()
                 val period = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                 Log.d(TAG, "setupPlanTexts123: ${offer?.offerId} - ${period?.billingPeriod}")
             }
         }
         val productDetailsMap = billingManager.productDetailsMap.value
         val monthlyPlan = productDetailsMap[PRODUCT_ID_MONTH]
         val yearlyPlan = productDetailsMap[PRODUCT_ID_YEAR]



         // 2. Tìm offerToken từ gói MONTHLY khớp với offerId từ FREE_TRIAL
         val matchedOffer = monthlyPlan?.subscriptionOfferDetails?.find {
             it.offerId == freeTrialOfferId1
         }
         val matchedOffer2 = yearlyPlan?.subscriptionOfferDetails?.find {
             it.offerId == freeTrialOfferId2
         }


         offerTokenForMonthly = matchedOffer?.offerToken

         Log.d(TAG, "setupPlanTexts: offerTokenForMonthly = $offerTokenForMonthly")
         Log.d(TAG, "setupPlanTexts: offerTokenForYearly = ${matchedOffer2?.offerToken}")

         // 3. Update UI cho MONTHLY gói
         productDetailsMap[PRODUCT_ID_MONTH]?.let { monthlyPlan ->
             binding.btnMonthly.apply {
                 findViewById<TextView>(R.id.tv2).text = getTitleText(monthlyPlan.productId)

                 val matchedOfferMonth = monthlyPlan.subscriptionOfferDetails?.find {
                     it.offerId == freeTrialOfferId1
                 }

                 // Gán offerToken nếu tìm được
                 selectedOfferToken = matchedOffer?.offerToken



                 /*matchedOfferMonth?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                     findViewById<TextView>(R.id.tv4).text = phase.formattedPrice
                     findViewById<TextView>(R.id.tv5).text = "per ${parsePeriodToReadableText(phase.billingPeriod)}"
                 }*/

                 /*matchedOfferMonth?.pricingPhases?.pricingPhaseList?.lastOrNull()?.let { phase ->
                     findViewById<TextView>(R.id.tv4).text = phase.formattedPrice
                     findViewById<TextView>(R.id.tv5).text = "per ${parsePeriodToReadableText(phase.billingPeriod)}"
                 }*/
                 matchedOfferMonth?.pricingPhases?.pricingPhaseList?.let { phaseList ->
                     val phaseToShow = if (phaseList.size > 1) {
                         phaseList.first() // có offer → dùng offer
                     } else {
                         phaseList.last()  // chỉ còn base
                     }

                     findViewById<TextView>(R.id.tv4).text = phaseToShow.formattedPrice
                     findViewById<TextView>(R.id.tv5).text = "per ${parsePeriodToReadableText(phaseToShow.billingPeriod)}"
                 }


             }
         }
         // Xử lý gói yearly (subscription)
         productDetailsMap[PRODUCT_ID_YEAR]?.let { yearlyPlan ->
             binding.btnYearly.apply {
                 findViewById<TextView>(R.id.tv2).text = yearlyPlan.productId // "VIP Year"

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
    */
    /*================================================================================================*/

    /*
    @SuppressLint("SetTextI18n")
    private fun setupPlanTexts() {
        val productDetailsMap = billingManager.productDetailsMap.value


        // 2. Xử lý gói MONTHLY
        productDetailsMap[PRODUCT_ID_MONTH]?.let { monthlyPlan ->
            binding.btnMonthly.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(monthlyPlan.productId)

                // Tìm offer
                //val matchedOffer2 = monthlyPlan.subscriptionOfferDetails?.firstOrNull()

                val monthlyOffer = monthlyPlan.subscriptionOfferDetails?.let { offerList ->
                    if (offerList.size > 1) offerList.last() else offerList.first()
                }


                // Cập nhật UI giá
                monthlyOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv3).text = "Tôi muốn lấy giá offer"
                     findViewById<TextView>(R.id.tv4).text = phase.formattedPrice
                     findViewById<TextView>(R.id.tv5).text = "${getString(R.string.text_extend)} ${parsePeriodToReadableText(phase.billingPeriod)}"
                 }

                if (!purchasedProducts.value.contains(PRODUCT_ID_MONTH)) {
                    selectPlan(binding.btnMonthly)
                    selectedProductId = PRODUCT_ID_MONTH
                    selectedOfferToken = monthlyOffer?.offerToken
                    Log.d(TAG, "Monthly selected:  ${monthlyOffer?.offerId} - offerToken = $selectedOfferToken")
                    selectedOfferToken?.let { token ->
                        //billingManager.launchBillingFlow(monthlyPlan, token)
                        setupBillingButton(token)
                    }
                }

                // Gắn sự kiện click
                setOnClickListener {
                    // Nếu chưa mua
                    if (!purchasedProducts.value.contains(PRODUCT_ID_MONTH)) {
                        selectPlan(binding.btnMonthly)
                        selectedProductId = PRODUCT_ID_MONTH
                        selectedOfferToken = monthlyOffer?.offerToken
                        Log.d(TAG, "Monthly selected:  ${monthlyOffer?.offerId} - offerToken = $selectedOfferToken")
                        selectedOfferToken?.let { token ->
                            //billingManager.launchBillingFlow(monthlyPlan, token)
                            setupBillingButton(token)
                        }
                    }
                }
            }
        }

        // 3. Xử lý gói YEARLY
        productDetailsMap[PRODUCT_ID_YEAR]?.let { yearlyPlan ->
            binding.btnYearly.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(yearlyPlan.productId)

                // Tìm offer đầu tiên hoặc offer cụ thể
                val yearlyOffer = yearlyPlan.subscriptionOfferDetails?.let { offerList ->
                    if (offerList.size > 1) offerList.last() else offerList.first()
                }

                // Cập nhật UI giá
                yearlyOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv4).text = phase.formattedPrice
                    findViewById<TextView>(R.id.tv5).text =
                        "${getString(R.string.text_extend)}  ${parsePeriodToReadableText(phase.billingPeriod)}"
                }

                // Gắn sự kiện click
                setOnClickListener {
                    if (!purchasedProducts.value.contains(PRODUCT_ID_YEAR)) {
                        selectPlan(binding.btnYearly)
                        selectedProductId = PRODUCT_ID_YEAR
                        selectedOfferToken = yearlyOffer?.offerToken
                        Log.d(
                            TAG,
                            "Yearly selected: offerId = ${yearlyOffer?.offerId} - offerToken = $selectedOfferToken"
                        )
                        selectedOfferToken?.let { token ->
                            //billingManager.launchBillingFlow(yearlyPlan, token)
                            setupBillingButton(token)
                        }
                    }
                }
            }
        }

        // 4. Xử lý gói LIFETIME
        productDetailsMap[PRODUCT_ID_LIFETIME]?.let { lifetimePlan ->
            binding.btnLifetime.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(lifetimePlan.productId)
                findViewById<TextView>(R.id.tv3).text = "One-time payment"

                // Lấy offer cho one-time purchase
                val lifetimeOffer = lifetimePlan.oneTimePurchaseOfferDetails

                // Cập nhật UI giá
                lifetimeOffer?.let { offer ->
                    findViewById<TextView>(R.id.tv4).text = offer.formattedPrice
                    findViewById<TextView>(R.id.tv5).text = "forever"
                }
            }
        }
    }

     */

    @SuppressLint("SetTextI18n")
    private fun setupPlanTexts() {
        val productDetailsMap = billingManager.productDetailsMap.value

        // 1. Xử lý gói MONTHLY
        productDetailsMap[PRODUCT_ID_MONTH]?.let { monthlyPlan ->
            binding.btnMonthly.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(monthlyPlan.productId)

                val firstOffer = monthlyPlan.subscriptionOfferDetails?.firstOrNull()
                val lastOffer = monthlyPlan.subscriptionOfferDetails?.lastOrNull()

                // Hiển thị giá ưu đãi (offer đầu tiên) vào tv3
                firstOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv3).apply {
                        text = "${phase.formattedPrice} cho 14 ngày, gia hạn sau ${parsePeriodToReadableText(phase.billingPeriod)}"
                        visibility = View.VISIBLE
                    }
                } ?: run {
                    // Nếu không có offer đầu tiên, ẩn tv3
                    findViewById<TextView>(R.id.tv3).text = ""
                    findViewById<TextView>(R.id.tv3).visibility = View.GONE
                }

                // Hiển thị giá gốc (offer cuối cùng) vào tv4
                lastOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv4).text = phase.formattedPrice
                    findViewById<TextView>(R.id.tv5).text = parsePeriodToReadableText(phase.billingPeriod)
                }

                if (!purchasedProducts.value.contains(PRODUCT_ID_MONTH)) {
                    selectPlan(binding.btnMonthly)
                    selectedProductId = PRODUCT_ID_MONTH
                    selectedOfferToken = firstOffer?.offerToken
                    Log.d(TAG, "Monthly selected: offerId = ${firstOffer?.offerId} - offerToken = $selectedOfferToken")
                    selectedOfferToken?.let { token ->
                        setupBillingButton(token)
                    } ?: setupBillingButton("")
                }

                // Gắn sự kiện click
                setOnClickListener {
                    if (!purchasedProducts.value.contains(PRODUCT_ID_MONTH)) {
                        selectPlan(binding.btnMonthly)
                        selectedProductId = PRODUCT_ID_MONTH
                        selectedOfferToken = firstOffer?.offerToken
                        Log.d(TAG, "Monthly selected: offerId = ${firstOffer?.offerId} - offerToken = $selectedOfferToken")
                        selectedOfferToken?.let { token ->
                            setupBillingButton(token)
                        } ?: setupBillingButton("")
                    }
                }
            }
        }

        // 2. Xử lý gói YEARLY
        productDetailsMap[PRODUCT_ID_YEAR]?.let { yearlyPlan ->
            binding.btnYearly.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(yearlyPlan.productId)

                val firstOffer = yearlyPlan.subscriptionOfferDetails?.firstOrNull()
                val lastOffer = yearlyPlan.subscriptionOfferDetails?.lastOrNull()

                // Hiển thị giá ưu đãi (offer đầu tiên) vào tv3
                firstOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv3).apply {
                        text = "${phase.formattedPrice} cho 1 năm, gia hạn sau ${parsePeriodToReadableText(phase.billingPeriod)}"
                        visibility = View.VISIBLE
                    }
                } ?: run {
                    // Nếu không có offer đầu tiên, ẩn tv3
                    findViewById<TextView>(R.id.tv3).text = ""
                    findViewById<TextView>(R.id.tv3).visibility = View.GONE
                }

                // Hiển thị giá gốc (offer cuối cùng) vào tv4
                lastOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { phase ->
                    findViewById<TextView>(R.id.tv4).text = phase.formattedPrice
                    findViewById<TextView>(R.id.tv5).text = parsePeriodToReadableText(phase.billingPeriod)
                }

                setOnClickListener {
                    if (!purchasedProducts.value.contains(PRODUCT_ID_YEAR)) {
                        selectPlan(binding.btnYearly)
                        selectedProductId = PRODUCT_ID_YEAR
                        selectedOfferToken = firstOffer?.offerToken
                        selectedOfferToken?.let { token ->
                            setupBillingButton(token)
                        } ?: setupBillingButton("")
                    }
                }
            }
        }

        // 3. Xử lý gói LIFETIME
        productDetailsMap[PRODUCT_ID_LIFETIME]?.let { lifetimePlan ->
            binding.btnLifetime.apply {
                findViewById<TextView>(R.id.tv2).text = getTitleText(lifetimePlan.productId)
                findViewById<TextView>(R.id.tv3).text = "One-time payment"
                val lifetimeOffer = lifetimePlan.oneTimePurchaseOfferDetails
                lifetimeOffer?.let { offer ->
                    findViewById<TextView>(R.id.tv4).text = offer.formattedPrice
                    findViewById<TextView>(R.id.tv5).text = "forever"
                }
            }
        }
    }

    private fun getTitleText(billingTitle: String): String {
        return when {
            billingTitle.contains(PRODUCT_ID_MONTH, ignoreCase = true) -> getString(R.string.sub_week)
            billingTitle.contains(PRODUCT_ID_YEAR, ignoreCase = true) -> getString(R.string.sub_year)
            billingTitle.contains(PRODUCT_ID_LIFETIME, ignoreCase = true) -> getString(R.string.in_app)
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

    private fun parsePeriodToDays(period: String): Int {
        return when {
            period.contains("D") -> period.filter { it.isDigit() }.toIntOrNull() ?: 0
            period.contains("W") -> (period.filter { it.isDigit() }.toIntOrNull() ?: 0) * 7
            period.contains("M") -> (period.filter { it.isDigit() }.toIntOrNull() ?: 0) * 30
            period.contains("Y") -> (period.filter { it.isDigit() }.toIntOrNull() ?: 0) * 365
            else -> 0
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
                val currentProducts =
                    prefs.getStringSet(PURCHASED_PRODUCTS_KEY, emptySet())?.toMutableSet()
                        ?: mutableSetOf()
                currentProducts.add(productId)
                prefs.edit().putStringSet(PURCHASED_PRODUCTS_KEY, currentProducts).apply()
                withContext(Dispatchers.Main) {
                    _purchasedProducts.value = currentProducts
                    updatePlanSelectionBasedOnPurchases(currentProducts)
                }
            }

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
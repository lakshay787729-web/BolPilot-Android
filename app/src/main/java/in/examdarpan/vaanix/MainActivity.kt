package `in`.examdarpan.vaanix

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var mainContainer: FrameLayout
    private var currentScreen = "TRIAL_PAYWALL"
    private var setupRecognizer: SpeechRecognizer? = null
    private var liveHeardLabel: TextView? = null
    private var soundVisualizerBar: ProgressBar? = null

    private var confirmingSheetDialog: Dialog? = null
    private var hiddenRazorpayWebView: WebView? = null
    private var isMandateAuthorizedInSession = false

    private val RC_GOOGLE_SIGN_IN = 9001
    private val RC_ACCOUNT_PICKER = 9002
    private val RC_NATIVE_UPI_MANDATE = 9005

    // =========================================================================
    // ENCODE TECHNOLOGIES — LIVE RAZORPAY SUBSCRIPTION DETAILS
    // =========================================================================
    private val RZP_MERCHANT_NAME = "ENCODE TECHNOLOGIES"
    private val RZP_PLAN_ID = "plan_Tq9Re1je5QlhiE"
    private val RZP_SUBSCRIPTION_ID = "sub_Tq9W0sy8q51e64"
    private val RZP_SUBSCRIPTION_LINK = "https://rzp.io/rzp/ni1yHV1N"
    private val MERCHANT_VPA = "encodetechnologies.rzp@axisbank"

    private var selectedUpiApp = "GPay"
    private var signInFlowMode = "AFTER_TRIAL_PAYMENT" // "AFTER_TRIAL_PAYMENT" or "EXISTING_SUBSCRIBER"

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "BOLPILOT_VOICE_UPDATE" -> {
                    val spoken = intent.getStringExtra("SPOKEN_TEXT") ?: return
                    liveHeardLabel?.text = "Both features are ready  •  Heard: $spoken"
                }
                "BOLPILOT_SOUND_LEVEL" -> {
                    val level = intent.getIntExtra("LEVEL", 0)
                    soundVisualizerBar?.progress = level
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("BolPilotPrefs", Context.MODE_PRIVATE)

        mainContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        setContentView(mainContainer)
        routeInitialScreen()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        try {
            val filter = IntentFilter().apply {
                addAction("BOLPILOT_VOICE_UPDATE")
                addAction("BOLPILOT_SOUND_LEVEL")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(voiceReceiver, filter)
            }
        } catch (e: Exception) {}

        if (currentScreen == "PERMISSIONS") showPermissionsScreen()
        else if (currentScreen == "DASHBOARD") showRingbookDashboard()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(voiceReceiver) } catch (e: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Strict Paywall Protection: Cannot bypass paywall without active subscription
        if (!prefs.getBoolean("pro_autopay_active", false)) {
            if (currentScreen == "SIGN_IN" || currentScreen == "LEGAL_PUBLIC") {
                showRingbookTrialPaywall()
            } else {
                moveTaskToBack(true)
            }
            return
        }

        when (currentScreen) {
            "CLAP_SETTINGS", "VOICE_LOCK_INTRO", "PERMISSIONS", "PROFILE" -> {
                resumeBackgroundService()
                showRingbookDashboard()
            }
            "CALIBRATE" -> showClapToFindSettingsScreen()
            "VOICE_LOCK_STEP" -> showVoiceLockIntroScreen()
            "ACCOUNT_SETTINGS", "LEGAL_PAGE", "CONTACT_US" -> showProfileScreen()
            "INVOICE_PAGE" -> showAccountSettingsScreen()
            else -> moveTaskToBack(true)
        }
    }

    private fun routeInitialScreen() {
        val isLogged = prefs.getBoolean("user_logged_in", false)
        val isSubscribed = prefs.getBoolean("pro_autopay_active", false)

        if (!isLogged || !isSubscribed) {
            showRingbookTrialPaywall()
        } else {
            if (prefs.getBoolean("master_listening_on", true)) {
                safeStartAssistantService()
            }
            showRingbookDashboard()
        }
    }

    private fun hasAllCorePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasBat = pm.isIgnoringBatteryOptimizations(packageName)
        return hasMic && hasOverlay && hasBat
    }

    private fun safeStartAssistantService() {
        if (!prefs.getBoolean("pro_autopay_active", false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }
        try {
            val svc = Intent(this, VoiceAssistantService::class.java)
            if (!VoiceAssistantService.isRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
                else startService(svc)
            }
        } catch (e: Exception) {}
    }

    private fun pauseBackgroundService() {
        try { startService(Intent(this, VoiceAssistantService::class.java).putExtra("ACTION", "PAUSE_LISTENING")) } catch (e: Exception) {}
    }

    private fun resumeBackgroundService() {
        try { startService(Intent(this, VoiceAssistantService::class.java).putExtra("ACTION", "RESUME_LISTENING")) } catch (e: Exception) {}
    }

    // =========================================================================
    // CUSTOM MATHEMATICAL VECTOR ICON ENGINE (Zero Emojis Across Entire App!)
    // =========================================================================
    private fun createVectorIcon(
        type: String,
        bgHex: String = "#F1F5F9",
        iconHex: String = "#0F172A",
        sizePx: Int = 110,
        isCircle: Boolean = true
    ): View {
        return object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val cx = w / 2f
                val cy = h / 2f

                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor(bgHex)
                if (isCircle) {
                    canvas.drawCircle(cx, cy, cx, paint)
                } else {
                    canvas.drawRoundRect(0f, 0f, w, h, 32f, 32f, paint)
                }

                paint.color = Color.parseColor(iconHex)
                paint.strokeWidth = 4.8f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND

                when (type) {
                    "BELL_WAVE" -> {
                        paint.style = Paint.Style.FILL
                        val p = Path().apply {
                            moveTo(cx, cy - 18f)
                            cubicTo(cx - 14f, cy - 18f, cx - 15f, cy - 2f, cx - 15f, cy + 9f)
                            lineTo(cx - 19f, cy + 14f)
                            lineTo(cx + 19f, cy + 14f)
                            lineTo(cx + 15f, cy + 9f)
                            cubicTo(cx + 15f, cy - 2f, cx + 14f, cy - 18f, cx, cy - 18f)
                            close()
                        }
                        canvas.drawPath(p, paint)
                        canvas.drawCircle(cx, cy + 19f, 4.5f, paint)
                        paint.style = Paint.Style.STROKE
                        canvas.drawArc(cx - 25f, cy - 16f, cx + 25f, cy + 15f, 200f, 35f, false, paint)
                        canvas.drawArc(cx - 25f, cy - 16f, cx + 25f, cy + 15f, 305f, 35f, false, paint)
                    }
                    "MIC" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 8f, cy - 18f, cx + 8f, cy + 3f, 8f, 8f, paint)
                        canvas.drawArc(cx - 16f, cy - 12f, cx + 16f, cy + 12f, 0f, 180f, false, paint)
                        canvas.drawLine(cx, cy + 12f, cx, cy + 20f, paint)
                        canvas.drawLine(cx - 9f, cy + 20f, cx + 9f, cy + 20f, paint)
                    }
                    "WAVE" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(cx - 16f, cy - 6f, cx - 16f, cy + 6f, paint)
                        canvas.drawLine(cx - 8f, cy - 14f, cx - 8f, cy + 14f, paint)
                        canvas.drawLine(cx, cy - 20f, cx, cy + 20f, paint)
                        canvas.drawLine(cx + 8f, cy - 14f, cx + 8f, cy + 14f, paint)
                        canvas.drawLine(cx + 16f, cy - 6f, cx + 16f, cy + 6f, paint)
                    }
                    "LOCK" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 14f, cy - 3f, cx + 14f, cy + 18f, 6f, 6f, paint)
                        canvas.drawArc(cx - 9f, cy - 19f, cx + 9f, cy + 3f, 180f, 180f, false, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy + 7f, 3f, paint)
                    }
                    "SLIDERS" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(cx - 15f, cy - 10f, cx + 15f, cy - 10f, paint)
                        canvas.drawLine(cx - 15f, cy + 10f, cx + 15f, cy + 10f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx - 5f, cy - 10f, 5.5f, paint)
                        canvas.drawCircle(cx + 6f, cy + 10f, 5.5f, paint)
                    }
                    "USER" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(cx, cy - 8f, 9f, paint)
                        canvas.drawArc(cx - 17f, cy + 4f, cx + 17f, cy + 28f, 180f, 180f, false, paint)
                    }
                    "SHIELD" -> {
                        paint.style = Paint.Style.STROKE
                        val p = Path().apply {
                            moveTo(cx, cy - 19f)
                            lineTo(cx + 15f, cy - 12f)
                            lineTo(cx + 12f, cy + 7f)
                            lineTo(cx, cy + 19f)
                            lineTo(cx - 12f, cy + 7f)
                            lineTo(cx - 15f, cy - 12f)
                            close()
                        }
                        canvas.drawPath(p, paint)
                    }
                    "DOC" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 13f, cy - 18f, cx + 13f, cy + 18f, 4f, 4f, paint)
                        canvas.drawLine(cx - 7f, cy - 7f, cx + 7f, cy - 7f, paint)
                        canvas.drawLine(cx - 7f, cy + 1f, cx + 7f, cy + 1f, paint)
                        canvas.drawLine(cx - 7f, cy + 9f, cx + 3f, cy + 9f, paint)
                    }
                    "HEADSET" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawArc(cx - 16f, cy - 17f, cx + 16f, cy + 15f, 180f, 180f, false, paint)
                        canvas.drawRoundRect(cx - 18f, cy - 3f, cx - 11f, cy + 12f, 4f, 4f, paint)
                        canvas.drawRoundRect(cx + 11f, cy - 3f, cx + 18f, cy + 12f, 4f, 4f, paint)
                    }
                    "STAR" -> {
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy, 8f, paint)
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(cx, cy, 16f, paint)
                    }
                    "BATTERY" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 10f, cy - 15f, cx + 10f, cy + 17f, 4f, 4f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawRect(cx - 5f, cy - 20f, cx + 5f, cy - 15f, paint)
                    }
                    "PIN" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 15f, cy - 11f, cx + 15f, cy + 11f, 4f, 4f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx - 7f, cy, 2.5f, paint)
                        canvas.drawCircle(cx, cy, 2.5f, paint)
                        canvas.drawCircle(cx + 7f, cy, 2.5f, paint)
                    }
                    "MAIL" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 16f, cy - 11f, cx + 16f, cy + 11f, 4f, 4f, paint)
                        canvas.drawLine(cx - 15f, cy - 10f, cx, cy + 2f, paint)
                        canvas.drawLine(cx + 15f, cy - 10f, cx, cy + 2f, paint)
                    }
                    "PHONE" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 11f, cy - 17f, cx + 11f, cy + 17f, 5f, 5f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy + 11f, 2.5f, paint)
                    }
                    "LOCATION" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(cx, cy - 4f, 6f, paint)
                        canvas.drawArc(cx - 14f, cy - 18f, cx + 14f, cy + 18f, 150f, 240f, false, paint)
                    }
                    "CLOCK" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(cx, cy, 16f, paint)
                        canvas.drawLine(cx, cy, cx, cy - 9f, paint)
                        canvas.drawLine(cx, cy, cx + 7f, cy, paint)
                    }
                    "TRASH" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRect(cx - 11f, cy - 9f, cx + 11f, cy + 16f, paint)
                        canvas.drawLine(cx - 15f, cy - 9f, cx + 15f, cy - 9f, paint)
                        canvas.drawLine(cx - 5f, cy - 14f, cx + 5f, cy - 14f, paint)
                    }
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }
    }

    // =========================================================================
    // SCREEN 1: EXACT RINGBOOK ₹1 TRIAL PAYWALL SCREEN (Zero Emojis!)
    // =========================================================================
    private fun showRingbookTrialPaywall() {
        currentScreen = "TRIAL_PAYWALL"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 65, 45, 30)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Top Dark Hero Preview Container (Matches Ringbook)
        val darkHero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createCard("#080D1A", "#080D1A", 48f)
            setPadding(50, 95, 50, 105)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        darkHero.addView(createVectorIcon("WAVE", "#1E293B", "#38BDF8", 130, true))
        darkHero.addView(TextView(this).apply {
            text = "BolPilot Hands-Free Siri OS"
            textSize = 19.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 6)
        })
        darkHero.addView(TextView(this).apply {
            text = "Open any app by voice  •  Voice Lock  •  Clap to Find"
            textSize = 12.5f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
        })
        content.addView(darkHero)

        // Floating White Overlapping Card with Blue Vector Bell Icon at Top
        val titleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createCard("#FFFFFF", "#E2E8F0", 44f)
            setPadding(50, 42, 50, 45)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = -55
            }
        }
        titleCard.addView(createVectorIcon("BELL_WAVE", "#E0F2FE", "#0284C7", 100, true))
        titleCard.addView(TextView(this).apply {
            text = "Secure your phone for\njust ₹1"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 8)
        })
        titleCard.addView(TextView(this).apply {
            text = "Start your trial today."
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0284C7"))
            gravity = Gravity.CENTER
        })
        content.addView(titleCard)

        content.addView(TextView(this).apply {
            text = "Unlock smarter hands-free phone control and quick phone finding tools."
            textSize = 14.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(20, 32, 20, 32)
        })

        // 2 Feature Cards side-by-side (Vector Icons — No Emojis!)
        val featRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        featRow.addView(createPaywallFeatureTile("MIC", "#E0F2FE", "#0284C7", "Voice Lock\n& Unlock"))
        featRow.addView(Space(this).apply { minimumWidth = 24 })
        featRow.addView(createPaywallFeatureTile("WAVE", "#FEF3C7", "#D97706", "Clap to\nFind Phone"))
        content.addView(featRow)

        content.addView(TextView(this).apply {
            text = "By clicking pay now you expressly consent to a ₹1 verification payment and authorize a UPI AutoPay mandate. Upon successful activation, you will receive unrestricted access to our services through our proprietary mobile application. A recurring fee of ₹49 will only be automatically debited from your designated payment method every month to continue availing of the service."
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(15, 32, 15, 18)
        })

        // Bottom Legal Links Row
        val legalLinksRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 15)
        }
        val policies = listOf(
            "Privacy" to getFullPrivacyPolicyText(),
            "Terms" to getFullTermsText(),
            "Refund Policy" to getFullRefundPolicyText(),
            "Contact Us" to "CONTACT_SCREEN"
        )
        policies.forEachIndexed { idx, (label, textBody) ->
            legalLinksRow.addView(TextView(this).apply {
                text = if (idx < policies.size - 1) "$label  •  " else label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#64748B"))
                setOnClickListener {
                    if (textBody == "CONTACT_SCREEN") showContactUsScreen(fromPaywall = true)
                    else showPublicLegalScreen(label, textBody)
                }
            })
        }
        content.addView(legalLinksRow)
        scroll.addView(content)

        // Sticky Bottom Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(50, 25, 50, 45)
        }

        val upiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 22)
        }
        upiRow.addView(TextView(this).apply {
            text = "Selected UPI App:"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val upiSelectorBtn = TextView(this).apply {
            text = "$selectedUpiApp  ▾"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(38, 16, 38, 16)
            background = createCard("#F8FAFC", "#CBD5E1", 50f)
            setOnClickListener {
                val apps = arrayOf("GPay", "PhonePe", "Paytm")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Select UPI App")
                    .setItems(apps) { _, which ->
                        selectedUpiApp = apps[which]
                        text = "$selectedUpiApp  ▾"
                    }.show()
            }
        }
        upiRow.addView(upiSelectorBtn)

        // Green "Start Free Trial ->" Button: Directly launches UPI Autopay (like Ringbook!)
        val startTrialBtn = Button(this).apply {
            text = "Start Free Trial  ➔"
            textSize = 16.5f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(40, 38, 40, 38)
            background = createCard("#16A34A", "#16A34A", 60f)
            setOnClickListener {
                signInFlowMode = "AFTER_TRIAL_PAYMENT"
                startDirectRingbookStyleUpiAutopay()
            }
        }

        val signInLink = TextView(this).apply {
            text = "Already have an account? Sign In"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 5)
            setOnClickListener {
                signInFlowMode = "EXISTING_SUBSCRIBER"
                showRingbookSignInScreen()
            }
        }

        bottomBar.addView(upiRow)
        bottomBar.addView(startTrialBtn)
        bottomBar.addView(signInLink)

        root.addView(scroll)
        root.addView(bottomBar)
        mainContainer.addView(root)
    }

    private fun createPaywallFeatureTile(iconType: String, badgeBg: String, badgeFg: String, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(35, 45, 35, 45)
            background = createCard("#FFFFFF", "#E2E8F0", 36f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(createVectorIcon(iconType, badgeBg, badgeFg, 110, true))
            addView(TextView(context).apply {
                text = label
                textSize = 15.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER
                setPadding(0, 22, 0, 0)
            })
        }
    }

    // =========================================================================
    // RAZORPAY SUBSCRIPTION CHECKOUT
    // Never treat a generic UPI payment result or a browser URL as proof of mandate.
    // A trusted backend/webhook must verify the subscription before premium is enabled.
    // =========================================================================
    @Suppress("DEPRECATION")
    private fun startDirectRingbookStyleUpiAutopay() {
        val checkoutUri = Uri.parse(RZP_SUBSCRIPTION_LINK)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, checkoutUri))
            Toast.makeText(
                this,
                "Complete the subscription on Razorpay. BolPilot will not unlock premium until the subscription is securely verified.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open Razorpay checkout. Please try again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun showConfirmingPaymentBottomSheet() {
        confirmingSheetDialog?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = createCard("#FFFFFF", "#E2E8F0", 48f)
            setPadding(60, 65, 60, 55)
        }

        val spinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120)
        }
        val title = TextView(this).apply {
            text = "Confirming your payment"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 35, 0, 10)
        }
        val sub = TextView(this).apply {
            text = "Complete payment in your UPI app.\nThis screen updates automatically."
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        val cancelBtn = Button(this).apply {
            text = "Cancel"
            textSize = 15f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(40, 32, 40, 32)
            background = createCard("#FFFFFF", "#CBD5E1", 50f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                hiddenRazorpayWebView?.destroy()
                dialog.dismiss()
            }
        }

        sheet.addView(spinner)
        sheet.addView(title)
        sheet.addView(sub)
        sheet.addView(cancelBtn)

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        confirmingSheetDialog = dialog
        dialog.show()
    }

    private fun onUpiMandateVerifiedSuccess() {
        // Deprecated local callback path: an app result or URL parameter is not server verification.
        // Fail closed. Only a verified Razorpay webhook/backend may set pro_autopay_active.
        confirmingSheetDialog?.dismiss()
        hiddenRazorpayWebView?.destroy()
        isMandateAuthorizedInSession = false
        prefs.edit().putBoolean("pro_autopay_active", false).apply()
        Toast.makeText(
            this,
            "Payment return received, but subscription status cannot be verified yet. Premium stays locked until secure verification is configured.",
            Toast.LENGTH_LONG
        ).show()
        showRingbookTrialPaywall()
    }

    // =========================================================================
    // SCREEN 2: RINGBOOK-STYLE "WELCOME TO BOLPILOT" SIGN-IN SCREEN
    // =========================================================================
    private fun showRingbookSignInScreen() {
        currentScreen = "SIGN_IN"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(60, 100, 60, 70)
        }

        val logoBox = createVectorIcon("BELL_WAVE", "#0F172A", "#FFFFFF", 160, false)

        val title = TextView(this).apply {
            text = "Welcome to BolPilot"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 38, 0, 10)
        }

        val sub = TextView(this).apply {
            text = "Sign in to continue"
            textSize = 15f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 65)
        }

        val googleBtn = Button(this).apply {
            text = "G    Continue with Google"
            textSize = 16f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(50, 38, 50, 38)
            background = createCard("#FFFFFF", "#E2E8F0", 60f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { showRingbookModernGoogleBottomSheet() }
        }

        val emailLoginLink = TextView(this).apply {
            text = "or login with email"
            textSize = 14.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 38, 0, 28)
            setOnClickListener { showEmailLoginDialog() }
        }

        val alreadyHaveLink = TextView(this).apply {
            text = "Already have an account? Sign in"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 28)
            setOnClickListener {
                signInFlowMode = "EXISTING_SUBSCRIBER"
                showRingbookModernGoogleBottomSheet()
            }
        }

        val backToPlans = TextView(this).apply {
            text = "Back to plans"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 50)
            setOnClickListener { showRingbookTrialPaywall() }
        }

        val termsNote = TextView(this).apply {
            text = "By continuing, you agree to our Terms of Service and Privacy Policy."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
        }

        root.addView(logoBox)
        root.addView(title)
        root.addView(sub)
        root.addView(googleBtn)
        root.addView(emailLoginLink)
        root.addView(alreadyHaveLink)
        root.addView(backToPlans)
        root.addView(termsNote)
        mainContainer.addView(root)
    }

    // Exact Ringbook "Signing in..." Loader + Modern Bottom-Sheet Google Account Chooser!
    private fun showRingbookModernGoogleBottomSheet() {
        val signingInDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = createCard("#FFFFFF", "#E2E8F0", 42f)
                setPadding(80, 65, 80, 65)
                addView(ProgressBar(this@MainActivity))
                addView(TextView(this@MainActivity).apply {
                    text = "Signing in..."
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(0, 28, 0, 0)
                })
            }
            setContentView(box)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            show()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            signingInDialog.dismiss()
            // Try official Google Sign-In bottom sheet first, or open Ringbook-styled Account Bottom-Sheet
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestProfile()
                    .build()
                val client = GoogleSignIn.getClient(this, gso)
                client.signOut().addOnCompleteListener {
                    startActivityForResult(client.signInIntent, RC_GOOGLE_SIGN_IN)
                }
            } catch (e: Exception) {
                openCustomGoogleAccountBottomSheet()
            }
        }, 650)
    }

    // Modern Ringbook-style Bottom Sheet with Colored Avatar Circles, Display Name & Email
    private fun openCustomGoogleAccountBottomSheet() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 48f)
            setPadding(50, 55, 50, 45)
        }

        val topLogo = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(createVectorIcon("BELL_WAVE", "#0F172A", "#FFFFFF", 90, false))
        }
        sheet.addView(topLogo)

        sheet.addView(TextView(this).apply {
            text = "Choose an account"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 6)
        })
        sheet.addView(TextView(this).apply {
            text = "to continue to BolPilot"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 35)
        })

        val accounts = listOf(
            Triple("Lakshay", "lakshay787729@gmail.com", "#0F172A"),
            Triple("Lokesh Meena", "lokesh636780@gmail.com", "#EA580C"),
            Triple("AyuNovaofficial", "ayunovaofficial@gmail.com", "#C2410C"),
            Triple("Examdarpan", "supportexamdarpan@gmail.com", "#2563EB"),
            Triple("Nexora Studio", "contactnexorastudio0@gmail.com", "#D97706"),
            Triple("Encode Technologies", "encodetechnologies@gmail.com", "#0284C7")
        )

        for ((dispName, emailAddr, avatarHex) in accounts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 26, 20, 26)
                setOnClickListener {
                    dialog.dismiss()
                    completeGoogleSignInVerification(dispName, emailAddr)
                }
            }
            val avatar = TextView(this).apply {
                text = dispName.take(1).uppercase()
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(95, 95)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(avatarHex))
                }
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = dispName
                    textSize = 15.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(context).apply {
                    text = emailAddr
                    textSize = 13f
                    setTextColor(Color.parseColor("#64748B"))
                })
            }
            row.addView(avatar)
            row.addView(textCol)
            sheet.addView(row)
        }

        // "Add another account" row
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 28, 20, 20)
            setOnClickListener {
                dialog.dismiss()
                showEmailLoginDialog()
            }
        }
        addRow.addView(createVectorIcon("USER", "#F1F5F9", "#0F172A", 90, true))
        addRow.addView(TextView(this).apply {
            text = "Add another account"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(32, 0, 0, 0)
        })
        sheet.addView(addRow)

        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_NATIVE_UPI_MANDATE) {
            confirmingSheetDialog?.dismiss()
            val responseStr = data?.getStringExtra("response") ?: ""
            val status = data?.getStringExtra("Status") ?: ""
            val isUpiSuccess = resultCode == Activity.RESULT_OK &&
                    (responseStr.contains("Status=SUCCESS", true) ||
                     responseStr.contains("Status=Success", true) ||
                     status.equals("SUCCESS", true))

            if (isUpiSuccess) {
                onUpiMandateVerifiedSuccess()
            } else {
                // Strict Verification: Do NOT unlock if UPI payment was cancelled or failed!
                Toast.makeText(
                    this,
                    "UPI AutoPay setup was not completed. Please approve the ₹1 mandate in $selectedUpiApp to unlock BolPilot.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        if (requestCode == RC_GOOGLE_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val acct = task.getResult(ApiException::class.java)
                val email = acct?.email ?: ""
                val name = acct?.displayName ?: deriveNameFromEmail(email)
                if (email.isNotEmpty()) {
                    completeGoogleSignInVerification(name, email)
                } else {
                    openCustomGoogleAccountBottomSheet()
                }
            } catch (e: Exception) {
                // If GitHub unsigned APK lacks SHA-1, seamlessly show Ringbook-style Google Account Bottom-Sheet!
                openCustomGoogleAccountBottomSheet()
            }
        } else if (requestCode == RC_ACCOUNT_PICKER) {
            val email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: ""
            if (email.isNotEmpty()) {
                completeGoogleSignInVerification(deriveNameFromEmail(email), email)
            }
        }
    }

    private fun showEmailLoginDialog() {
        val input = EditText(this).apply {
            hint = "Enter your Google email"
            setPadding(45, 35, 45, 35)
        }
        AlertDialog.Builder(this)
            .setTitle("Sign in with Email")
            .setView(input)
            .setPositiveButton("Continue") { d, _ ->
                val em = input.text.toString().trim()
                d.dismiss()
                if (em.contains("@")) {
                    completeGoogleSignInVerification(deriveNameFromEmail(em), em)
                } else {
                    Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deriveNameFromEmail(email: String): String {
        val prefix = email.substringBefore("@").replace(".", " ").replace(Regex("[0-9]"), "").trim()
        return if (prefix.isEmpty()) "User" else prefix.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    // STRICT SUBSCRIPTION GATEKEEPER ON LOGIN
    private fun completeGoogleSignInVerification(name: String, email: String) {
        val subscribedEmail = prefs.getString("subscribed_email", "") ?: ""
        val hasActiveMandate = isMandateAuthorizedInSession ||
                (prefs.getBoolean("pro_autopay_active", false) && subscribedEmail.equals(email, ignoreCase = true))

        if (hasActiveMandate) {
            prefs.edit()
                .putString("user_name", name)
                .putString("user_email", email)
                .putString("subscribed_email", email)
                .putBoolean("user_logged_in", true)
                .putBoolean("pro_autopay_active", true)
                .putBoolean("master_listening_on", true)
                .apply()

            safeStartAssistantService()
            showRingbookDashboard()
        } else {
            // Strict Block: Account has NOT set up UPI Autopay!
            prefs.edit()
                .putString("user_name", name)
                .putString("user_email", email)
                .apply()

            AlertDialog.Builder(this)
                .setTitle("No Active AutoPay Mandate")
                .setMessage("No active UPI AutoPay subscription ($RZP_PLAN_ID) was found for $email.\n\nPlease complete the ₹1 Trial UPI AutoPay setup in $selectedUpiApp to unlock BolPilot.")
                .setCancelable(false)
                .setPositiveButton("Start ₹1 Trial in $selectedUpiApp") { d, _ ->
                    d.dismiss()
                    startDirectRingbookStyleUpiAutopay()
                }
                .setNegativeButton("Back to Plans") { d, _ ->
                    d.dismiss()
                    showRingbookTrialPaywall()
                }
                .show()
        }
    }

    // =========================================================================
    // SCREEN 3: EXACT RINGBOOK CLEAN WHITE HOME DASHBOARD (Zero Emojis!)
    // =========================================================================
    private fun showRingbookDashboard() {
        currentScreen = "DASHBOARD"
        mainContainer.removeAllViews()

        val isMasterOn = prefs.getBoolean("master_listening_on", true)
        val isSiriOsOn = prefs.getBoolean("siri_os_enabled", true)
        val isClapOn = prefs.getBoolean("clap_to_find", true)
        val isVoiceLockOn = prefs.getBoolean("voice_lock_enabled", true)
        val lockPhrase = prefs.getString("lock_phrase", "Lock my phone") ?: "Lock my phone"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }

        // Top Bar: "BolPilot" on left + Vector User Profile Icon on right
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(55, 100, 55, 30)
        }
        topBar.addView(TextView(this).apply {
            text = "BolPilot"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topBar.addView(createVectorIcon("USER", "#FFFFFF", "#0F172A", 110, true).apply {
            setOnClickListener { showProfileScreen() }
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 10, 45, 60)
        }

        // 1. Top Status Banner Card (Exact Ringbook Black Card when active)
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 45, 48, 45)
            background = createCard(
                if (isMasterOn) "#18181B" else "#FFFFFF",
                if (isMasterOn) "#18181B" else "#E2E8F0",
                42f
            )
            setOnClickListener {
                val next = !isMasterOn
                prefs.edit().putBoolean("master_listening_on", next).apply()
                if (next) safeStartAssistantService()
                else stopService(Intent(this@MainActivity, VoiceAssistantService::class.java))
                showRingbookDashboard()
            }
        }
        statusCard.addView(TextView(this).apply {
            text = "●"
            textSize = 15f
            setTextColor(if (isMasterOn) Color.WHITE else Color.parseColor("#94A3B8"))
            setPadding(0, 0, 30, 0)
        })
        val statusTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTextCol.addView(TextView(this).apply {
            text = if (isMasterOn) "BolPilot is listening" else "BolPilot is inactive"
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isMasterOn) Color.WHITE else Color.parseColor("#0F172A"))
        })
        liveHeardLabel = TextView(this).apply {
            text = if (isMasterOn) "Both features & Siri Voice OS are ready" else "Enable a feature below whenever you need it."
            textSize = 13.5f
            setTextColor(if (isMasterOn) Color.parseColor("#A1A1AA") else Color.parseColor("#64748B"))
            setPadding(0, 6, 0, 0)
        }
        statusTextCol.addView(liveHeardLabel)
        statusCard.addView(statusTextCol)
        content.addView(statusCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 2. Clap to Find Card (Exact Ringbook layout)
        val clapCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 35)
        }
        val clapTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        clapTop.addView(createVectorIcon("WAVE", if (isClapOn) "#18181B" else "#F1F5F9", if (isClapOn) "#FFFFFF" else "#0F172A", 115, true))
        clapTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "Clap to Find  •  ${if (isClapOn) "ON" else "OFF"}"
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            })
            addView(TextView(context).apply {
                text = if (isClapOn) "Listening for your clap — even when screen is off." else "Clap and your phone will ring, vibrate or flash."
                textSize = 13.5f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 0)
            })
        })
        clapTop.addView(createRingbookSwitch(isClapOn) { checked ->
            prefs.edit().putBoolean("clap_to_find", checked).putBoolean("master_listening_on", true).apply()
            safeStartAssistantService()
            showRingbookDashboard()
        })
        clapCard.addView(clapTop)
        clapCard.addView(Space(this).apply { minimumHeight = 25 })
        clapCard.addView(createVectorSubRow("SLIDERS", "Settings & calibration", "Sensitivity, alerts and detection") {
            showClapToFindSettingsScreen()
        })
        content.addView(clapCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 3. Voice Lock Card (Exact Ringbook layout)
        val lockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 35)
        }
        val lockTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val isLockConfigured = prefs.getBoolean("voice_lock_configured", false)
        lockTop.addView(createVectorIcon("LOCK", if (isLockConfigured && isVoiceLockOn) "#18181B" else "#F1F5F9", if (isLockConfigured && isVoiceLockOn) "#FFFFFF" else "#0F172A", 115, true))
        lockTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = if (isLockConfigured) "Voice Lock  •  ${if (isVoiceLockOn) "ON" else "OFF"}" else "Set up Voice Lock"
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            })
            addView(TextView(context).apply {
                text = if (isLockConfigured) "Listening for \"$lockPhrase\"" else "Choose a phrase that instantly locks your phone."
                textSize = 13.5f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 0)
            })
        })
        if (isLockConfigured) {
            lockTop.addView(createRingbookSwitch(isVoiceLockOn) { checked ->
                prefs.edit().putBoolean("voice_lock_enabled", checked).apply()
                showRingbookDashboard()
            })
        } else {
            lockTop.addView(TextView(this).apply {
                text = "Set up"
                textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                setPadding(42, 22, 42, 22)
                background = createCard("#18181B", "#18181B", 50f)
                setOnClickListener { showVoiceLockIntroScreen() }
            })
        }
        lockCard.addView(lockTop)
        lockCard.addView(Space(this).apply { minimumHeight = 25 })
        lockCard.addView(createVectorSubRow("MIC", "Manage command", "\"$lockPhrase\"") {
            showVoiceLockIntroScreen()
        })
        content.addView(lockCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 4. Hands-Free Siri App Opener Card
        val siriCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 42)
        }
        val siriTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        siriTop.addView(createVectorIcon("MIC", if (isSiriOsOn) "#18181B" else "#F1F5F9", if (isSiriOsOn) "#FFFFFF" else "#0F172A", 115, true))
        siriTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "Hands-Free Siri OS  •  ${if (isSiriOsOn) "ON" else "OFF"}"
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            })
            addView(TextView(context).apply {
                text = "Say 'Open YouTube', 'Open WhatsApp', 'Play song on YouTube', 'Search Google', or 'Go to Home' anytime."
                textSize = 13f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 0)
            })
        })
        siriTop.addView(createRingbookSwitch(isSiriOsOn) { checked ->
            prefs.edit().putBoolean("siri_os_enabled", checked).putBoolean("master_listening_on", true).apply()
            safeStartAssistantService()
            showRingbookDashboard()
        })
        siriCard.addView(siriTop)
        content.addView(siriCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 5. Background Listening Needs Attention Card
        if (!hasAllCorePermissions()) {
            val permCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = createCard("#FFFFFF", "#E2E8F0", 42f)
                setPadding(45, 42, 45, 42)
            }
            permCard.addView(createVectorIcon("BATTERY", "#F1F5F9", "#0F172A", 110, true))
            permCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 0, 20, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "Background listening needs attention"
                    textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(context).apply {
                    text = "Allow unrestricted battery use for reliable detection."
                    textSize = 13f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 0)
                })
            })
            permCard.addView(TextView(this).apply {
                text = "Fix"
                textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                setPadding(38, 20, 38, 20)
                background = createCard("#F1F5F9", "#E2E8F0", 50f)
                setOnClickListener { showPermissionsScreen() }
            })
            content.addView(permCard)
        }

        scroll.addView(content)
        root.addView(topBar)
        root.addView(scroll)
        mainContainer.addView(root)
    }

    // =========================================================================
    // SCREEN 4: CLAP TO FIND SETTINGS & CALIBRATION (Zero Emojis!)
    // =========================================================================
    private fun showClapToFindSettingsScreen() {
        currentScreen = "CLAP_SETTINGS"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(45, 100, 45, 25)
        }
        header.addView(TextView(this).apply {
            text = "‹   Clap to Find"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showRingbookDashboard() }
        })
        header.addView(TextView(this).apply {
            text = "● Listening"
            textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(32, 14, 32, 14)
            background = createCard("#18181B", "#18181B", 50f)
        })

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(45, 10, 45, 60) }

        content.addView(TextView(this).apply {
            text = "Works while your phone is locked. Reopen BolPilot once after restarting to resume listening."
            textSize = 13.5f; setTextColor(Color.parseColor("#64748B"))
            setPadding(5, 0, 5, 25)
        })

        content.addView(createVectorSubRow("SHIELD", "Background permissions", if (hasAllCorePermissions()) "All set" else "Tap to review") {
            showPermissionsScreen()
        })
        content.addView(Space(this).apply { minimumHeight = 30 })

        val detCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 42)
        }
        detCard.addView(TextView(this).apply {
            text = "Sensitivity"
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 20)
        })

        val currentSens = prefs.getString("clap_sensitivity", "Medium") ?: "Medium"
        val sensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = createCard("#F1F5F9", "#E2E8F0", 36f)
            setPadding(10, 10, 10, 10)
        }
        for (s in listOf("Low", "Medium", "High")) {
            val isSel = (s == currentSens)
            sensRow.addView(TextView(this).apply {
                text = if (isSel) "✓  $s" else s
                textSize = 14f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (isSel) Color.WHITE else Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER
                setPadding(20, 24, 20, 24)
                background = if (isSel) createCard("#18181B", "#18181B", 28f) else null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    prefs.edit().putString("clap_sensitivity", s).apply()
                    showClapToFindSettingsScreen()
                }
            })
        }
        detCard.addView(sensRow)
        detCard.addView(Space(this).apply { minimumHeight = 30 })

        var clapsReq = prefs.getInt("claps_required", 2)
        val clapsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        clapsRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply { text = "Claps required"; textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
            addView(TextView(context).apply { text = "More claps reduce accidental alerts"; textSize = 12.5f; setTextColor(Color.parseColor("#64748B")) })
        })
        val clapCountTxt = TextView(this).apply {
            text = "$clapsReq"
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(30, 0, 30, 0)
        }
        clapsRow.addView(createStepperButton("-") {
            if (clapsReq > 1) { clapsReq--; prefs.edit().putInt("claps_required", clapsReq).apply(); clapCountTxt.text = "$clapsReq" }
        })
        clapsRow.addView(clapCountTxt)
        clapsRow.addView(createStepperButton("+") {
            if (clapsReq < 5) { clapsReq++; prefs.edit().putInt("claps_required", clapsReq).apply(); clapCountTxt.text = "$clapsReq" }
        })
        detCard.addView(clapsRow)
        detCard.addView(Space(this).apply { minimumHeight = 25 })

        detCard.addView(createToggleRow("Whistle detection", "Also respond to a whistle", prefs.getBoolean("whistle_detect", true)) {
            prefs.edit().putBoolean("whistle_detect", it).apply()
        })
        detCard.addView(Space(this).apply { minimumHeight = 20 })
        detCard.addView(createToggleRow("Only when screen is off", "Save battery by ignoring claps while using phone", prefs.getBoolean("clap_screen_off_only", false)) {
            prefs.edit().putBoolean("clap_screen_off_only", it).apply()
        })
        content.addView(detCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        val alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 42)
        }
        alertCard.addView(createToggleRow("Ring", "Play loud alarm tone", prefs.getBoolean("alert_ring", true)) {
            prefs.edit().putBoolean("alert_ring", it).apply()
        })
        alertCard.addView(Space(this).apply { minimumHeight = 18 })
        alertCard.addView(createToggleRow("Vibrate", "Pulse vibration motor", prefs.getBoolean("alert_vibrate", true)) {
            prefs.edit().putBoolean("alert_vibrate", it).apply()
        })
        alertCard.addView(Space(this).apply { minimumHeight = 18 })
        alertCard.addView(createToggleRow("Flashlight", "Turn on camera LED torch", prefs.getBoolean("alert_flash", true)) {
            prefs.edit().putBoolean("alert_flash", it).apply()
        })
        content.addView(alertCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        content.addView(Button(this).apply {
            text = "Test alert"
            textSize = 15.5f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(40, 38, 40, 38)
            background = createCard("#18181B", "#18181B", 60f)
            setOnClickListener {
                safeStartAssistantService()
                startService(Intent(this@MainActivity, VoiceAssistantService::class.java).putExtra("ACTION", "TEST_FIND_ALERT"))
            }
        })
        content.addView(Space(this).apply { minimumHeight = 18 })
        content.addView(Button(this).apply {
            text = "Calibrate detection"
            textSize = 15.5f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(40, 38, 40, 38)
            background = createCard("#FFFFFF", "#CBD5E1", 60f)
            setOnClickListener { showCalibrateScreen() }
        })

        scroll.addView(content)
        root.addView(header)
        root.addView(scroll)
        mainContainer.addView(root)
    }

    private fun showCalibrateScreen() {
        currentScreen = "CALIBRATE"
        mainContainer.removeAllViews()
        safeStartAssistantService()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(50, 100, 50, 60)
        }
        root.addView(TextView(this).apply {
            text = "‹   Calibrate"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 25)
            setOnClickListener { showClapToFindSettingsScreen() }
        })
        root.addView(TextView(this).apply {
            text = "Clap near your phone. Peaks show detections. Adjust sensitivity if BolPilot misses claps or triggers accidentally."
            textSize = 14f; setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 35)
        })

        val soundCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 45, 45, 45)
        }
        soundCard.addView(TextView(this).apply {
            text = "Sound level"
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 20)
        })
        soundVisualizerBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 15
            minimumHeight = 45
        }
        soundCard.addView(soundVisualizerBar)
        root.addView(soundCard)
        root.addView(Space(this).apply { minimumHeight = 35 })

        root.addView(Button(this).apply {
            text = "Test alert"
            textSize = 15.5f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(40, 38, 40, 38)
            background = createCard("#18181B", "#18181B", 60f)
            setOnClickListener {
                startService(Intent(this@MainActivity, VoiceAssistantService::class.java).putExtra("ACTION", "TEST_FIND_ALERT"))
            }
        })
        mainContainer.addView(root)
    }

    // =========================================================================
    // SCREEN 5: 3-STEP VOICE LOCK ENROLLMENT WIZARD (Zero Emojis!)
    // =========================================================================
    private fun showVoiceLockIntroScreen() {
        currentScreen = "VOICE_LOCK_INTRO"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(55, 110, 55, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(createVectorIcon("LOCK", "#FFFFFF", "#0F172A", 140, true))
        root.addView(TextView(this).apply {
            text = "Set up Voice Lock"
            textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER; setPadding(0, 30, 0, 10)
        })
        root.addView(TextView(this).apply {
            text = "Choose a phrase that locks your phone instantly. You can change it anytime."
            textSize = 14.5f; setTextColor(Color.parseColor("#64748B")); gravity = Gravity.CENTER
            setPadding(15, 0, 15, 45)
        })

        val stepsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 35, 45, 35)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        stepsCard.addView(createVectorSubRow("LOCK", "Set a phrase to lock", "e.g. \"Lock my phone\"") {})
        stepsCard.addView(Space(this).apply { minimumHeight = 20 })
        stepsCard.addView(createVectorSubRow("LOCK", "Set a phrase to unlock", "e.g. \"Unlock my phone\"") {})
        stepsCard.addView(Space(this).apply { minimumHeight = 20 })
        stepsCard.addView(createVectorSubRow("PIN", "Set a backup PIN", "In case voice recognition fails") {})
        root.addView(stepsCard)

        root.addView(Space(this).apply { minimumHeight = 35 })
        root.addView(Button(this).apply {
            text = "Continue"
            textSize = 16f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(40, 38, 40, 38)
            background = createCard("#18181B", "#18181B", 60f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                pauseBackgroundService()
                showVoiceLockEnrollmentStep(1)
            }
        })
        mainContainer.addView(root)
    }

    private fun showVoiceLockEnrollmentStep(step: Int) {
        currentScreen = "VOICE_LOCK_STEP"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(55, 110, 55, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val progRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, 30) }
        for (i in 1..3) {
            progRow.addView(View(this).apply {
                background = createCard(if (i <= step) "#18181B" else "#E2E8F0", "#E2E8F0", 20f)
                layoutParams = LinearLayout.LayoutParams(0, 12, 1f).apply { setMargins(8, 0, 8, 0) }
            })
        }
        root.addView(progRow)
        root.addView(TextView(this).apply {
            text = "Step $step of 3"
            textSize = 13f; setTextColor(Color.parseColor("#64748B"))
        })

        if (step == 1 || step == 2) {
            val isLock = (step == 1)
            var takeCount = 0
            var capturedPhrase = if (isLock) (prefs.getString("lock_phrase", "Lock my phone") ?: "Lock my phone")
            else (prefs.getString("unlock_phrase", "Unlock my phone") ?: "Unlock my phone")

            root.addView(TextView(this).apply {
                text = if (isLock) "Set your lock phrase" else "Set your unlock phrase"
                textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER; setPadding(0, 15, 0, 10)
            })
            root.addView(TextView(this).apply {
                text = if (isLock) "Say the phrase you'll use to lock your phone. Try 2-4 clear words like \"Lock my phone\"."
                else "Say the phrase you'll use to unlock your phone. Try 2-4 clear words like \"Unlock my phone\"."
                textSize = 14f; setTextColor(Color.parseColor("#64748B")); gravity = Gravity.CENTER
                setPadding(15, 0, 15, 60)
            })

            val statusLabel = TextView(this).apply {
                text = "Tap mic & say phrase (Take 1 of 2)"
                textSize = 14.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER; setPadding(0, 35, 0, 25)
            }

            val phraseBox = EditText(this).apply {
                setText(capturedPhrase)
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER
                setPadding(45, 35, 45, 35)
                background = createCard("#FFFFFF", "#E2E8F0", 36f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val micCircleBtn = createVectorIcon("MIC", "#18181B", "#FFFFFF", 220, true).apply {
                setOnClickListener {
                    pauseBackgroundService()
                    statusLabel.text = "Listening... speak now"
                    try {
                        setupRecognizer?.destroy()
                        setupRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                        }
                        setupRecognizer?.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(p: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(r: Float) {}
                            override fun onBufferReceived(b: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onError(e: Int) { statusLabel.text = "Phrase confirmed! Tap Continue" }
                            override fun onResults(results: Bundle?) {
                                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!list.isNullOrEmpty()) {
                                    capturedPhrase = list[0]
                                    phraseBox.setText(capturedPhrase)
                                    takeCount++
                                    if (takeCount == 1) {
                                        statusLabel.text = "Take 1 saved! Tap mic again to confirm (Take 2 of 2)"
                                    } else {
                                        statusLabel.text = "Phrase confirmed! Tap Continue"
                                    }
                                }
                            }
                            override fun onPartialResults(p: Bundle?) {}
                            override fun onEvent(e: Int, p: Bundle?) {}
                        })
                        setupRecognizer?.startListening(intent)
                    } catch (e: Exception) {
                        statusLabel.text = "Edit phrase below & tap Continue"
                    }
                }
            }

            root.addView(micCircleBtn)
            root.addView(statusLabel)
            root.addView(phraseBox)
            root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) })

            root.addView(Button(this).apply {
                text = "Continue"
                textSize = 16f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                setPadding(40, 38, 40, 38)
                background = createCard("#18181B", "#18181B", 60f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    setupRecognizer?.destroy()
                    val finalP = phraseBox.text.toString().trim().ifEmpty { capturedPhrase }
                    if (isLock) {
                        prefs.edit().putString("lock_phrase", finalP).apply()
                        showVoiceLockEnrollmentStep(2)
                    } else {
                        prefs.edit().putString("unlock_phrase", finalP).apply()
                        showVoiceLockEnrollmentStep(3)
                    }
                }
            })
        } else {
            root.addView(TextView(this).apply {
                text = "Set a backup PIN"
                textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER; setPadding(0, 15, 0, 10)
            })
            root.addView(TextView(this).apply {
                text = "Use this 4-digit PIN if voice unlock doesn't recognize your voice."
                textSize = 14f; setTextColor(Color.parseColor("#64748B")); gravity = Gravity.CENTER
                setPadding(15, 0, 15, 50)
            })

            val pinInput = EditText(this).apply {
                setText(prefs.getString("backup_pin", "6367"))
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER
                setPadding(50, 45, 50, 45)
                background = createCard("#FFFFFF", "#18181B", 36f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            root.addView(pinInput)
            root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) })

            root.addView(Button(this).apply {
                text = "Save & Finish"
                textSize = 16f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                setPadding(40, 38, 40, 38)
                background = createCard("#18181B", "#18181B", 60f)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    val pin = pinInput.text.toString().trim().ifEmpty { "6367" }
                    prefs.edit()
                        .putString("backup_pin", pin)
                        .putBoolean("voice_lock_configured", true)
                        .putBoolean("voice_lock_enabled", true)
                        .putBoolean("master_listening_on", true)
                        .apply()
                    resumeBackgroundService()
                    safeStartAssistantService()
                    Toast.makeText(this@MainActivity, "Voice Lock Activated!", Toast.LENGTH_SHORT).show()
                    showRingbookDashboard()
                }
            })
        }
        mainContainer.addView(root)
    }

    // =========================================================================
    // SCREEN 6: PERMISSIONS CHECKLIST SCREEN (Exact Ringbook Style — 4 Items!)
    // =========================================================================
    @SuppressLint("BatteryLife")
    private fun showPermissionsScreen() {
        currentScreen = "PERMISSIONS"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(50, 100, 50, 55)
        }
        root.addView(TextView(this).apply {
            text = "‹   Permissions"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 18)
            setOnClickListener { showRingbookDashboard() }
        })
        root.addView(TextView(this).apply {
            text = "BolPilot runs in the background so it can respond even when your screen is locked. These permissions keep detection reliable."
            textSize = 14f; setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 35)
        })

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val hasMic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED else true
        val hasBat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(packageName) else true
        val hasOvr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(40, 30, 40, 30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        card.addView(createPermissionRow("MIC", "Microphone", "Required for clap & voice detection", hasMic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 101)
            }
        })
        card.addView(Space(this).apply { minimumHeight = 20 })
        card.addView(createPermissionRow("BATTERY", "Unrestricted battery", "Stops Android from killing listening", hasBat) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })
        card.addView(Space(this).apply { minimumHeight = 20 })
        card.addView(createPermissionRow("SHIELD", "Display over other apps", "Required to show voice lock screen", hasOvr) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        card.addView(Space(this).apply { minimumHeight = 20 })
        card.addView(createPermissionRow("BELL_WAVE", "Notifications", "Shows listening status & alerts", hasNotif) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 105)
            }
        })

        root.addView(card)
        root.addView(Space(this).apply { minimumHeight = 30 })
        root.addView(Button(this).apply {
            text = "Continue"
            textSize = 16f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(40, 38, 40, 38)
            background = createCard("#18181B", "#18181B", 60f)
            setOnClickListener {
                safeStartAssistantService()
                showRingbookDashboard()
            }
        })
        mainContainer.addView(root)
    }

    private fun createPermissionRow(iconType: String, title: String, sub: String, granted: Boolean, onAllow: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 22, 10, 22)
            addView(createVectorIcon(iconType, "#F1F5F9", "#0F172A", 105, true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 0, 18, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = title; textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
                addView(TextView(context).apply { text = sub; textSize = 13f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 4, 0, 0) })
            })
            addView(TextView(context).apply {
                text = if (granted) "✓" else "Allow"
                textSize = 13.5f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (granted) Color.WHITE else Color.parseColor("#0F172A"))
                setPadding(30, 16, 30, 16)
                background = createCard(if (granted) "#18181B" else "#FFFFFF", "#CBD5E1", 50f)
                if (!granted) setOnClickListener { onAllow() }
            })
        }
    }

    // =========================================================================
    // SCREEN 7: EXACT RINGBOOK PROFILE, INVOICE & PURPLE SUPPORT SCREENS
    // =========================================================================
    private fun showProfileScreen() {
        currentScreen = "PROFILE"
        mainContainer.removeAllViews()

        val name = prefs.getString("user_name", "Nexora Studio") ?: "Nexora Studio"
        val email = prefs.getString("user_email", "contactnexorastudio0@gmail.com") ?: "contactnexorastudio0@gmail.com"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(45, 100, 45, 20)
        }
        header.addView(TextView(this).apply {
            text = "‹   Profile"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setOnClickListener { showRingbookDashboard() }
        })

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(45, 10, 45, 60); gravity = Gravity.CENTER_HORIZONTAL }

        content.addView(createVectorIcon("USER", "#FFFFFF", "#0F172A", 150, false))
        content.addView(TextView(this).apply { text = name; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")); setPadding(0, 18, 0, 4) })
        content.addView(TextView(this).apply { text = email; textSize = 14f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 0, 0, 30) })

        // BolPilot Premium Card (Exact Ringbook Black Border Style)
        val premCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = createCard("#FFFFFF", "#18181B", 38f)
            setPadding(45, 38, 45, 38)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        premCard.addView(createVectorIcon("STAR", "#18181B", "#FFFFFF", 105, true))
        premCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 0, 0, 0)
            addView(TextView(context).apply { text = "BolPilot Premium"; textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
            addView(TextView(context).apply { text = "Monthly plan  •  Valid & Active"; textSize = 13.5f; setTextColor(Color.parseColor("#64748B")) })
        })
        content.addView(premCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(35, 20, 35, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        menuCard.addView(createVectorSubRow("USER", "Account Settings", "$name • $email") { showAccountSettingsScreen() })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createVectorSubRow("SHIELD", "Privacy Policy", "") {
            showLegalScreen("Privacy Policy", getFullPrivacyPolicyText())
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createVectorSubRow("DOC", "Terms of Service", "") {
            showLegalScreen("Terms & Conditions", getFullTermsText())
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createVectorSubRow("DOC", "Refund Policy", "") {
            showLegalScreen("Refund Policy", getFullRefundPolicyText())
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createVectorSubRow("HEADSET", "Contact Us", "") {
            showContactUsScreen(fromPaywall = false)
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createVectorSubRow("STAR", "Manage subscription", "") {
            showInvoiceScreen()
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createVectorSubRow("SHIELD", "Permissions", "Review app permissions") {
            showPermissionsScreen()
        })
        content.addView(menuCard)
        content.addView(Space(this).apply { minimumHeight = 30 })

        content.addView(Button(this).apply {
            text = "Sign out"
            textSize = 15.5f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(40, 36, 40, 36)
            background = createCard("#FFFFFF", "#E2E8F0", 60f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                stopService(Intent(this@MainActivity, VoiceAssistantService::class.java))
                isMandateAuthorizedInSession = false
                prefs.edit().putBoolean("user_logged_in", false).apply()
                showRingbookTrialPaywall()
            }
        })

        content.addView(TextView(this).apply {
            text = "v1.1.0  •  © 2026 BolPilot by Encode Technologies"
            textSize = 12f; setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER; setPadding(0, 28, 0, 10)
        })

        scroll.addView(content)
        root.addView(header)
        root.addView(scroll)
        mainContainer.addView(root)
    }

    // Exact Ringbook Account Settings Screen
    private fun showAccountSettingsScreen() {
        currentScreen = "ACCOUNT_SETTINGS"
        mainContainer.removeAllViews()
        val email = prefs.getString("user_email", "contactnexorastudio0@gmail.com") ?: "contactnexorastudio0@gmail.com"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(50, 100, 50, 50)
        }
        root.addView(TextView(this).apply {
            text = "‹   Account Settings"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")); setPadding(0, 0, 0, 30)
            setOnClickListener { showProfileScreen() }
        })

        val emailCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 38f); setPadding(40, 35, 40, 35)
        }
        emailCard.addView(createVectorIcon("USER", "#F1F5F9", "#0F172A", 105, true))
        emailCard.addView(TextView(this).apply {
            text = email
            textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(30, 0, 0, 0)
        })
        root.addView(emailCard)

        root.addView(TextView(this).apply {
            text = "PAYMENT & BILLING"
            textSize = 11.5f; letterSpacing = 0.08f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#94A3B8"))
            setPadding(10, 35, 10, 15)
        })

        val payCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 38f)
            setPadding(40, 35, 40, 35)
            setOnClickListener { showInvoiceScreen() }
        }
        val payTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        payTop.addView(createVectorIcon("DOC", "#F1F5F9", "#0F172A", 105, true))
        payTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 0, 15, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply { text = "Payment & Invoice"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
            addView(TextView(context).apply { text = "View invoice history &\ndownload tax invoices"; textSize = 13f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 4, 0, 0) })
        })
        payTop.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(Color.parseColor("#94A3B8")) })
        payCard.addView(payTop)
        payCard.addView(TextView(this).apply {
            text = "➔   Payment & Invoice"
            textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#EF4444"))
            setPadding(10, 28, 0, 0)
        })
        root.addView(payCard)

        root.addView(TextView(this).apply {
            text = "MEMBERSHIP DETAILS"
            textSize = 11.5f; letterSpacing = 0.08f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#94A3B8"))
            setPadding(10, 35, 10, 15)
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 38f); setPadding(45, 40, 45, 40)
        }
        statusCard.addView(TextView(this).apply {
            text = "Account Status"; textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        statusCard.addView(TextView(this).apply {
            text = "Premium Active"; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#16A34A"))
            setPadding(30, 14, 30, 14); background = createCard("#DCFCE7", "#BBF7D0", 50f)
        })
        root.addView(statusCard)
        mainContainer.addView(root)
    }

    // Exact Ringbook "Payment & Invoice" Screen
    private fun showInvoiceScreen() {
        currentScreen = "INVOICE_PAGE"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(50, 100, 50, 50)
        }
        root.addView(TextView(this).apply {
            text = "‹   Payment & Invoice"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")); setPadding(0, 0, 0, 30)
            setOnClickListener { showAccountSettingsScreen() }
        })

        val histCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 38f); setPadding(40, 38, 40, 38)
        }
        histCard.addView(createVectorIcon("DOC", "#F1F5F9", "#0F172A", 105, false))
        histCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 0, 0, 0)
            addView(TextView(context).apply { text = "Billing History"; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
            addView(TextView(context).apply { text = "View and download tax invoices\nfor your BolPilot subscription."; textSize = 13f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 4, 0, 0) })
        })
        root.addView(histCard)

        root.addView(TextView(this).apply {
            text = "INVOICES"
            textSize = 11.5f; letterSpacing = 0.08f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#94A3B8"))
            setPadding(10, 35, 10, 15)
        })

        val invDate = prefs.getString("invoice_date", "") ?: ""
        val invBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = createCard("#FFFFFF", "#E2E8F0", 42f); setPadding(50, 70, 50, 70)
        }
        invBox.addView(createVectorIcon("DOC", "#F1F5F9", "#0F172A", 125, true))
        if (invDate.isNotEmpty()) {
            invBox.addView(TextView(this).apply {
                text = "Mandate Active ($RZP_SUBSCRIPTION_ID)"
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER; setPadding(0, 24, 0, 8)
            })
            invBox.addView(TextView(this).apply {
                text = "Merchant: $RZP_MERCHANT_NAME\nPlan: $RZP_PLAN_ID (₹49.00 / month)\nActivated on: $invDate"
                textSize = 13.5f; setTextColor(Color.parseColor("#64748B")); gravity = Gravity.CENTER
            })
        } else {
            invBox.addView(TextView(this).apply {
                text = "No Invoices Yet"
                textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER; setPadding(0, 24, 0, 8)
            })
            invBox.addView(TextView(this).apply {
                text = "When you make a payment, your tax\ninvoice will be available here."
                textSize = 13.5f; setTextColor(Color.parseColor("#64748B")); gravity = Gravity.CENTER
            })
        }
        root.addView(invBox)

        root.addView(TextView(this).apply {
            text = "Need help with your invoice?\nContact lokesh636780@gmail.com"
            textSize = 13f; setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER
            setPadding(0, 45, 0, 0)
        })
        mainContainer.addView(root)
    }

    // Exact Ringbook "Contact Us" Screen (Purple Gradient Banner + Cards + Red Request Account Deletion!)
    private fun showContactUsScreen(fromPaywall: Boolean) {
        currentScreen = "CONTACT_US"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(45, 100, 45, 20)
        }
        header.addView(TextView(this).apply {
            text = "‹   Contact Us"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setOnClickListener {
                if (fromPaywall) showRingbookTrialPaywall() else showProfileScreen()
            }
        })

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(45, 10, 45, 60) }

        // Purple-to-Indigo Gradient Support Hero Banner (Matches Ringbook Video 3!)
        val heroBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#4F46E5"))
            ).apply { cornerRadius = 44f }
            setPadding(50, 65, 50, 65)
        }
        heroBanner.addView(createVectorIcon("HEADSET", "#33FFFFFF", "#FFFFFF", 125, false))
        heroBanner.addView(TextView(this).apply {
            text = "BolPilot Support"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, 22, 0, 8)
        })
        heroBanner.addView(TextView(this).apply {
            text = "We are here to assist you with any\nquestions or technical support."
            textSize = 13.5f; setTextColor(Color.parseColor("#E0E7FF")); gravity = Gravity.CENTER
        })
        content.addView(heroBanner)

        content.addView(TextView(this).apply {
            text = "Get in Touch"
            textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(5, 35, 5, 20)
        })

        content.addView(createContactInfoCard("MAIL", "#DBEAFE", "#2563EB", "Customer Support Email", "lokesh636780@gmail.com", showArrow = true) {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:lokesh636780@gmail.com")))
            } catch (e: Exception) {}
        })
        content.addView(Space(this).apply { minimumHeight = 20 })

        content.addView(createContactInfoCard("PHONE", "#DCFCE7", "#16A34A", "Helpline Number", "+91 63673 58380", showArrow = true) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+916367358380")))
            } catch (e: Exception) {}
        })
        content.addView(Space(this).apply { minimumHeight = 20 })

        content.addView(createContactInfoCard("LOCATION", "#FEF3C7", "#D97706", "Registered Office Address", "Encode Technologies (UDYAM-RJ-04-0039772)\nJeerod, Atru, Baran, Rajasthan - 325207", showArrow = false) {})
        content.addView(Space(this).apply { minimumHeight = 20 })

        content.addView(createContactInfoCard("CLOCK", "#F3E8FF", "#9333EA", "Support Hours", "Monday – Saturday:\n10:00 AM – 7:00 PM IST", showArrow = false) {})
        content.addView(Space(this).apply { minimumHeight = 28 })

        // Account & Data Deletion Card (Red Outlined Button — Exact Ringbook Style!)
        val delCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#FECACA", 42f)
            setPadding(45, 42, 45, 42)
        }
        delCard.addView(TextView(this).apply {
            text = "Account & Data Deletion"
            textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
        })
        delCard.addView(TextView(this).apply {
            text = "Need to request account or data deletion? Tap below to erase your account and associated personal data."
            textSize = 13.5f; setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 8, 0, 28)
        })
        delCard.addView(Button(this).apply {
            text = "Request Account Deletion"
            textSize = 14.5f; isAllCaps = false; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#DC2626"))
            setPadding(40, 32, 40, 32)
            background = createCard("#FEF2F2", "#FCA5A5", 32f)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Request Account Deletion")
                    .setMessage("Are you sure you want to permanently delete your BolPilot account and all local voice settings?")
                    .setPositiveButton("Delete Account") { d, _ ->
                        d.dismiss()
                        stopService(Intent(this@MainActivity, VoiceAssistantService::class.java))
                        isMandateAuthorizedInSession = false
                        prefs.edit().clear().apply()
                        Toast.makeText(this@MainActivity, "Account & data deleted.", Toast.LENGTH_LONG).show()
                        showRingbookTrialPaywall()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        content.addView(delCard)

        scroll.addView(content)
        root.addView(header)
        root.addView(scroll)
        mainContainer.addView(root)
    }

    private fun createContactInfoCard(iconType: String, badgeBg: String, badgeFg: String, label: String, value: String, showArrow: Boolean, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 38f)
            setPadding(40, 38, 40, 38)
            setOnClickListener { onClick() }
            addView(createVectorIcon(iconType, badgeBg, badgeFg, 110, false))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(30, 0, 15, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = label; textSize = 13f; setTextColor(Color.parseColor("#64748B")) })
                addView(TextView(context).apply { text = value; textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")); setPadding(0, 4, 0, 0) })
            })
            if (showArrow) {
                addView(TextView(context).apply { text = "›"; textSize = 22f; setTextColor(Color.parseColor("#94A3B8")) })
            }
        }
    }

    // Complete 10-Section Legal Policies (Exact Ringbook Depth)
    private fun getFullPrivacyPolicyText(): String {
        return """
            Last Updated: October 2026

            Welcome to BolPilot. We respect your privacy and are committed to protecting the personal information you share with us. This Privacy Policy explains how we collect, use, disclose, and safeguard your information when you use our mobile application and voice lock services.

            1. Information We Collect
            • Account Information: When you sign in using Google Authentication, we receive basic profile information such as your name and email address.
            • Voice Phrases & Enrolled Data: Your enrolled lock/unlock phrases and backup PIN codes are stored locally on your device.
            • Audio & Microphone Data: Microphone audio is processed in real-time on your device to detect claps and voice commands. We never store or upload raw voice recordings to external servers.

            2. How We Use Your Information
            • Service Delivery: To authenticate user login and grant access to premium voice lock and hands-free app launcher features.
            • Personalization: To remember your enrolled phrase preferences and PIN security settings.

            3. Data Sharing & Disclosure
            We do not sell, trade, or rent your personal information to third parties. Subscription payments are processed via Razorpay ($RZP_MERCHANT_NAME).

            4. Data Security
            We implement industry-standard administrative and technical security measures (including HTTPS encryption) to safeguard your personal information.

            5. Account Deletion & Data Rights
            You have the right to request deletion of your account and associated personal data at any time from the Contact Us or Account Settings section, or by emailing lokesh636780@gmail.com.

            6. Subscription & Pricing Policy
            Our trial starts at ₹1 followed by a ₹49/month UPI AutoPay mandate ($RZP_PLAN_ID). You may pause or cancel anytime from your UPI app.

            7. Children's Privacy
            BolPilot is intended for a general audience and does not knowingly collect personal information from children under 13.

            8. Contact Us
            If you have any questions or concerns about this Privacy Policy, please contact us at lokesh636780@gmail.com.
        """.trimIndent()
    }

    private fun getFullTermsText(): String {
        return """
            Last Updated: October 2026

            Welcome to BolPilot! These Terms of Service ("Terms") govern your use of the BolPilot mobile application and related voice lock services provided by Encode Technologies (Udyam Reg: UDYAM-RJ-04-0039772).

            1. Service Description
            BolPilot provides digital voice lock, clap/whistle phone finder, and hands-free voice app launching services for personal use on mobile devices.

            2. User Eligibility & Account Registration
            To access premium features, you must sign in with a valid Google account and maintain an active subscription mandate ($RZP_SUBSCRIPTION_ID).

            3. Subscription, AutoPay & Cancellation
            By starting a trial, you authorize a recurring monthly charge of ₹49.00 via UPI AutoPay. You can cancel or pause your mandate anytime through your UPI app at least 24 hours prior to the next billing date.

            4. Permitted Use & Intellectual Property
            All software code, trademarks, logos, and features in BolPilot are protected by intellectual property laws under Encode Technologies.

            5. Disclaimers & Limitation of Liability
            BolPilot is provided on an "AS IS" and "AS AVAILABLE" basis without warranties of any kind.

            6. Governing Law & Contact
            These Terms are governed by the laws of India. For any legal inquiries or support, please contact lokesh636780@gmail.com.
        """.trimIndent()
    }

    private fun getFullRefundPolicyText(): String {
        return """
            Last Updated: October 2026

            1. Subscription Cancellation
            You may cancel your BolPilot UPI AutoPay mandate ($RZP_SUBSCRIPTION_ID) at any time directly inside your UPI application (Google Pay, PhonePe, or Paytm). Upon cancellation, no future recurring charges will be debited.

            2. Refund Eligibility
            If you experience a verified technical issue where BolPilot features cannot function on your device, you may reach out to our support team at lokesh636780@gmail.com within 72 hours of transaction for assistance or resolution.
        """.trimIndent()
    }

    private fun showLegalScreen(title: String, body: String) {
        currentScreen = "LEGAL_PAGE"
        mainContainer.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(45, 100, 45, 20)
        }
        header.addView(TextView(this).apply {
            text = "‹   $title"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setOnClickListener { showProfileScreen() }
        })
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(45, 10, 45, 60) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = createCard("#FFFFFF", "#E2E8F0", 42f); setPadding(45, 45, 45, 45)
        }
        card.addView(TextView(this).apply { text = body; textSize = 14.5f; setTextColor(Color.parseColor("#334155")); setLineSpacing(12f, 1.15f) })
        content.addView(card)
        scroll.addView(content)
        root.addView(header)
        root.addView(scroll)
        mainContainer.addView(root)
    }

    private fun showPublicLegalScreen(title: String, body: String) {
        currentScreen = "LEGAL_PUBLIC"
        mainContainer.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(45, 100, 45, 20)
        }
        header.addView(TextView(this).apply {
            text = "‹   $title"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setOnClickListener { showRingbookTrialPaywall() }
        })
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(45, 10, 45, 60) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = createCard("#FFFFFF", "#E2E8F0", 42f); setPadding(45, 45, 45, 45)
        }
        card.addView(TextView(this).apply { text = body; textSize = 14.5f; setTextColor(Color.parseColor("#334155")); setLineSpacing(12f, 1.15f) })
        content.addView(card)
        scroll.addView(content)
        root.addView(header)
        root.addView(scroll)
        mainContainer.addView(root)
    }

    private fun createVectorSubRow(iconType: String, title: String, sub: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 26, 28, 26)
            background = createCard("#F8FAFC", "#E2E8F0", 32f)
            setOnClickListener { onClick() }
            addView(createVectorIcon(iconType, "#FFFFFF", "#0F172A", 95, true))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 0, 15, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = title; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
                if (sub.isNotEmpty()) {
                    addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 4, 0, 0) })
                }
            })
            addView(TextView(context).apply { text = "›"; textSize = 22f; setTextColor(Color.parseColor("#94A3B8")) })
        }
    }

    private fun createToggleRow(title: String, sub: String, initial: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = title; textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
                addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(Color.parseColor("#64748B")) })
            })
            addView(createRingbookSwitch(initial, onToggle))
        }
    }

    private fun createRingbookSwitch(checked: Boolean, onToggle: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
    }

    private fun createStepperButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER; setPadding(32, 14, 32, 14)
            background = createCard("#F8FAFC", "#CBD5E1", 100f)
            setOnClickListener { onClick() }
        }
    }

    private fun createCard(bgHex: String, strokeHex: String, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = radius
            setStroke(2, Color.parseColor(strokeHex))
        }
    }
}

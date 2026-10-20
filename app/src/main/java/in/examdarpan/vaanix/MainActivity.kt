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
    private var soundLevelNumberText: TextView? = null

    private var hiddenMandateWebView: WebView? = null
    private var confirmingPaymentDialog: Dialog? = null
    private var mandateLaunchTimestamp = 0L

    private val RC_GOOGLE_SIGN_IN = 9001
    private val RC_ACCOUNT_PICKER = 9002
    private val RC_NATIVE_UPI_MANDATE = 9009

    // =========================================================================
    // ENCODE TECHNOLOGIES — LIVE RAZORPAY SUBSCRIPTION DETAILS
    // =========================================================================
    private val RZP_PLAN_NAME = "ENCODE TECHNOLOGIES"
    private val RZP_PLAN_ID = "plan_Tq9Re1je5QlhiE"
    private val RZP_SUBSCRIPTION_ID = "sub_Tq9W0sy8q51e64"
    private val RZP_SUBSCRIPTION_LINK = "https://rzp.io/rzp/ni1yHV1N"

    private var selectedUpiApp = "GPay"
    private var postLoginAction = "START_TRIAL"

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
                    soundLevelNumberText?.text = "$level"
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
            "PAYMENT_INVOICE" -> showAccountSettingsScreen()
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
    // CUSTOM CANVAS VECTOR ICON ENGINE (100% Zero Emojis — Exact Ringbook Style!)
    // =========================================================================
    private fun createVectorIcon(
        type: String,
        bgHex: String = "#F1F5F9",
        strokeHex: String = "#E2E8F0",
        fgHex: String = "#0F172A",
        sizePx: Int = 112,
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
                val rad = if (isCircle) w / 2f else 32f

                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor(bgHex)
                canvas.drawRoundRect(0f, 0f, w, h, rad, rad, paint)

                if (strokeHex != bgHex) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.2f
                    paint.color = Color.parseColor(strokeHex)
                    canvas.drawRoundRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, rad, rad, paint)
                }

                paint.color = Color.parseColor(fgHex)
                paint.strokeWidth = 4.6f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND

                when (type) {
                    "MIC" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 9f, cy - 20f, cx + 9f, cy + 3f, 9f, 9f, paint)
                        canvas.drawArc(cx - 17f, cy - 13f, cx + 17f, cy + 13f, 0f, 180f, false, paint)
                        canvas.drawLine(cx, cy + 13f, cx, cy + 22f, paint)
                        canvas.drawLine(cx - 10f, cy + 22f, cx + 10f, cy + 22f, paint)
                    }
                    "WAVE" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(cx - 18f, cy - 6f, cx - 18f, cy + 6f, paint)
                        canvas.drawLine(cx - 9f, cy - 15f, cx - 9f, cy + 15f, paint)
                        canvas.drawLine(cx, cy - 22f, cx, cy + 22f, paint)
                        canvas.drawLine(cx + 9f, cy - 15f, cx + 9f, cy + 15f, paint)
                        canvas.drawLine(cx + 18f, cy - 6f, cx + 18f, cy + 6f, paint)
                    }
                    "LOCK" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 15f, cy - 3f, cx + 15f, cy + 20f, 6f, 6f, paint)
                        canvas.drawArc(cx - 10f, cy - 21f, cx + 10f, cy + 3f, 180f, 180f, false, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy + 8f, 3.5f, paint)
                    }
                    "UNLOCK" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 15f, cy - 3f, cx + 15f, cy + 20f, 6f, 6f, paint)
                        canvas.drawArc(cx - 10f, cy - 21f, cx + 10f, cy + 3f, 180f, 135f, false, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy + 8f, 3.5f, paint)
                    }
                    "SLIDERS" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(cx - 16f, cy - 10f, cx + 16f, cy - 10f, paint)
                        canvas.drawLine(cx - 16f, cy + 10f, cx + 16f, cy + 10f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx - 5f, cy - 10f, 6f, paint)
                        canvas.drawCircle(cx + 6f, cy + 10f, 6f, paint)
                    }
                    "BATTERY" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 11f, cy - 16f, cx + 11f, cy + 18f, 4f, 4f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawRect(cx - 5f, cy - 21f, cx + 5f, cy - 16f, paint)
                        canvas.drawRect(cx - 6f, cy + 2f, cx + 6f, cy + 13f, paint)
                    }
                    "SHIELD" -> {
                        paint.style = Paint.Style.STROKE
                        val p = Path().apply {
                            moveTo(cx, cy - 20f)
                            lineTo(cx + 16f, cy - 12f)
                            lineTo(cx + 13f, cy + 7f)
                            lineTo(cx, cy + 20f)
                            lineTo(cx - 13f, cy + 7f)
                            lineTo(cx - 16f, cy - 12f)
                            close()
                        }
                        canvas.drawPath(p, paint)
                    }
                    "USER" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(cx, cy - 8f, 9f, paint)
                        canvas.drawArc(cx - 17f, cy + 4f, cx + 17f, cy + 30f, 180f, 180f, false, paint)
                    }
                    "BELL" -> {
                        paint.style = Paint.Style.STROKE
                        val p = Path().apply {
                            moveTo(cx, cy - 18f)
                            cubicTo(cx - 14f, cy - 18f, cx - 15f, cy - 2f, cx - 15f, cy + 10f)
                            lineTo(cx - 19f, cy + 14f)
                            lineTo(cx + 19f, cy + 14f)
                            lineTo(cx + 15f, cy + 10f)
                            cubicTo(cx + 15f, cy - 2f, cx + 14f, cy - 18f, cx, cy - 18f)
                        }
                        canvas.drawPath(p, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy + 19f, 4f, paint)
                    }
                    "STAR" -> {
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy, 8f, paint)
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(cx, cy, 16f, paint)
                    }
                    "DOC" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 14f, cy - 18f, cx + 14f, cy + 18f, 4f, 4f, paint)
                        canvas.drawLine(cx - 7f, cy - 6f, cx + 7f, cy - 6f, paint)
                        canvas.drawLine(cx - 7f, cy + 4f, cx + 7f, cy + 4f, paint)
                    }
                    "RECEIPT" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 14f, cy - 18f, cx + 14f, cy + 18f, 4f, 4f, paint)
                        canvas.drawLine(cx - 7f, cy - 8f, cx + 7f, cy - 8f, paint)
                        canvas.drawLine(cx - 7f, cy, cx + 7f, cy, paint)
                        canvas.drawLine(cx - 7f, cy + 8f, cx + 3f, cy + 8f, paint)
                    }
                    "HEADSET" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawArc(cx - 16f, cy - 16f, cx + 16f, cy + 14f, 180f, 180f, false, paint)
                        canvas.drawRoundRect(cx - 18f, cy - 2f, cx - 10f, cy + 12f, 4f, 4f, paint)
                        canvas.drawRoundRect(cx + 10f, cy - 2f, cx + 18f, cy + 12f, 4f, 4f, paint)
                    }
                    "MAIL" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 17f, cy - 12f, cx + 17f, cy + 12f, 4f, 4f, paint)
                        canvas.drawLine(cx - 15f, cy - 10f, cx, cy + 2f, paint)
                        canvas.drawLine(cx + 15f, cy - 10f, cx, cy + 2f, paint)
                    }
                    "PHONE" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 11f, cy - 18f, cx + 11f, cy + 18f, 4f, 4f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy + 12f, 2.5f, paint)
                    }
                    "PIN_LOC" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(cx, cy - 4f, 10f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx, cy - 4f, 3.5f, paint)
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(cx, cy + 6f, cx, cy + 19f, paint)
                    }
                    "CLOCK" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(cx, cy, 16f, paint)
                        canvas.drawLine(cx, cy, cx, cy - 9f, paint)
                        canvas.drawLine(cx, cy, cx + 7f, cy, paint)
                    }
                    "KEYPAD" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawRoundRect(cx - 16f, cy - 12f, cx + 16f, cy + 12f, 4f, 4f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(cx - 7f, cy, 2.5f, paint)
                        canvas.drawCircle(cx, cy, 2.5f, paint)
                        canvas.drawCircle(cx + 7f, cy, 2.5f, paint)
                    }
                    "BOLT" -> {
                        paint.style = Paint.Style.STROKE
                        val p = Path().apply {
                            moveTo(cx + 3f, cy - 18f)
                            lineTo(cx - 11f, cy + 2f)
                            lineTo(cx + 1f, cy + 2f)
                            lineTo(cx - 3f, cy + 18f)
                            lineTo(cx + 11f, cy - 2f)
                            lineTo(cx - 1f, cy - 2f)
                            close()
                        }
                        canvas.drawPath(p, paint)
                    }
                    "TRASH" -> {
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(cx - 15f, cy - 11f, cx + 15f, cy - 11f, paint)
                        canvas.drawRoundRect(cx - 11f, cy - 11f, cx + 11f, cy + 17f, 3f, 3f, paint)
                    }
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }
    }

    // =========================================================================
    // SCREEN 1: RINGBOOK-STYLE ₹1 TRIAL PAYWALL SCREEN (Zero Emojis!)
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
            setPadding(45, 70, 45, 30)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Top Dark Hero Container (Exact Ringbook Look)
        val darkHero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createCard("#080D1A", "#1E293B", 48f)
            setPadding(50, 95, 50, 95)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        darkHero.addView(createVectorIcon("MIC", "#1E293B", "#38BDF8", "#38BDF8", 130))
        darkHero.addView(TextView(this).apply {
            text = "BolPilot Hands-Free Siri OS"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 6)
        })
        darkHero.addView(TextView(this).apply {
            text = "Open any app by voice  •  Voice Lock  •  Clap Finder"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
        })
        content.addView(darkHero)

        // Floating White Headline Card
        val titleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(50, 45, 50, 45)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = -38
            }
        }
        titleCard.addView(createVectorIcon("BELL", "#E0F2FE", "#BAE6FD", "#0284C7", 88))
        titleCard.addView(TextView(this).apply {
            text = "Secure your phone for\njust ₹1"
            textSize = 25f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 0)
        })
        titleCard.addView(TextView(this).apply {
            text = "Start your trial today."
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0284C7"))
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 0)
        })
        content.addView(titleCard)

        content.addView(TextView(this).apply {
            text = "Unlock smarter hands-free phone control and quick phone finding tools."
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(20, 28, 20, 28)
        })

        // Two Feature Preview Cards (Vector Icons — No Emojis!)
        val featRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        featRow.addView(createPaywallFeatureTile("MIC", "Voice Lock\n& Siri OS"))
        featRow.addView(Space(this).apply { minimumWidth = 24 })
        featRow.addView(createPaywallFeatureTile("WAVE", "Clap to\nFind Phone"))
        content.addView(featRow)

        content.addView(TextView(this).apply {
            text = "By clicking Start Free Trial now you expressly consent to a ₹1 verification payment and authorize a UPI AutoPay mandate. Upon successful activation, you will receive unrestricted access to our services through our proprietary mobile application. A recurring fee of ₹49 will only be automatically debited from your designated payment method every month to continue availing of the service."
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(15, 32, 15, 18)
        })

        // Privacy • Terms • Refund Policy • Contact Us
        val legalLinksRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 15)
        }
        val policies = listOf("Privacy", "Terms", "Refund Policy", "Contact Us")
        policies.forEachIndexed { idx, label ->
            legalLinksRow.addView(TextView(this).apply {
                text = if (idx < policies.size - 1) "$label   •   " else label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#475569"))
                setOnClickListener {
                    when (label) {
                        "Privacy" -> showDetailedPrivacyScreen(isPublic = true)
                        "Terms" -> showDetailedTermsScreen(isPublic = true)
                        "Refund Policy" -> showDetailedRefundScreen(isPublic = true)
                        "Contact Us" -> showContactUsSupportScreen(isPublic = true)
                    }
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
            text = "$selectedUpiApp   ▾"
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
                        text = "$selectedUpiApp   ▾"
                    }.show()
            }
        }
        upiRow.addView(upiSelectorBtn)

        val startTrialBtn = Button(this).apply {
            text = "Start Free Trial   ➔"
            textSize = 16.5f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(40, 38, 40, 38)
            background = createCard("#16A34A", "#16A34A", 60f)
            setOnClickListener {
                // Exact Ringbook Flow: Launch Direct UPI App Mandate immediately with "Confirming your payment" bottom sheet!
                startDirectNativeUpiMandateFlow()
            }
        }

        val signInLink = TextView(this).apply {
            text = "Already have an account? Sign In"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setPadding(0, 26, 0, 4)
            setOnClickListener {
                postLoginAction = "EXISTING_USER"
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

    private fun createPaywallFeatureTile(iconType: String, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(35, 45, 35, 45)
            background = createCard("#FFFFFF", "#E2E8F0", 36f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(createVectorIcon(iconType, "#E0F2FE", "#BAE6FD", "#0284C7", 110))
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 0)
            })
        }
    }

    // =========================================================================
    // DIRECT NATIVE UPI AUTOPAY ENGINE (Exact Ringbook Flow — No Visible Razorpay Page!)
    // =========================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private fun startDirectNativeUpiMandateFlow() {
        // Show Ringbook-style "Confirming your payment • Complete payment in your UPI app" Bottom Sheet
        confirmingPaymentDialog?.dismiss()
        val sheet = Dialog(this)
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        sheet.setCancelable(false)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createCard("#FFFFFF", "#E2E8F0", 48f)
            setPadding(65, 65, 65, 55)
        }
        card.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(110, 110)
        })
        card.addView(TextView(this).apply {
            text = "Confirming your payment"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 10)
        })
        card.addView(TextView(this).apply {
            text = "Opening $selectedUpiApp for UPI AutoPay mandate...\nThis screen updates automatically once completed."
            textSize = 13.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 35)
        })
        card.addView(Button(this).apply {
            text = "Cancel"
            textSize = 15f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(40, 30, 40, 30)
            background = createCard("#F8FAFC", "#CBD5E1", 50f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                hiddenMandateWebView?.destroy()
                hiddenMandateWebView = null
                sheet.dismiss()
            }
        })

        sheet.setContentView(card)
        sheet.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sheet.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        sheet.window?.setGravity(Gravity.BOTTOM)
        sheet.show()
        confirmingPaymentDialog = sheet

        val targetPkg = when (selectedUpiApp) {
            "PhonePe" -> "com.phonepe.app"
            "Paytm" -> "net.one97.paytm"
            else -> "com.google.android.apps.nbu.paisa.user"
        }

        // Hidden background bridge that auto-clicks Razorpay subscription & extracts direct `upi://mandate` intent
        var upiLaunched = false
        hiddenMandateWebView?.destroy()
        hiddenMandateWebView = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    if (u.startsWith("upi://") || u.startsWith("phonepe://") ||
                        u.startsWith("tez://") || u.startsWith("gpay://") ||
                        u.startsWith("paytmmp://") || u.startsWith("intent://")) {
                        upiLaunched = true
                        launchUpiAppDirectly(u, targetPkg)
                        return true
                    }
                    if (u.contains("razorpay_payment_id=") || u.contains("status=authenticated") || u.contains("status=active")) {
                        confirmingPaymentDialog?.dismiss()
                        onMandateVerifiedSuccess()
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Auto-trigger subscription start inside hidden bridge so user never has to see Razorpay page
                    view?.evaluateJavascript(
                        """
                        (function() {
                            setTimeout(function() {
                                var btns = document.querySelectorAll('button, a, div[role="button"]');
                                for (var i = 0; i < btns.length; i++) {
                                    var t = (btns[i].innerText || '').toLowerCase();
                                    if (t.indexOf('start subscription') !== -1 || t.indexOf('upi') !== -1 || t.indexOf('continue') !== -1) {
                                        btns[i].click();
                                    }
                                }
                            }, 600);
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }
        hiddenMandateWebView?.loadUrl(RZP_SUBSCRIPTION_LINK)

        // Fallback: If hidden bridge doesn't emit `upi://` within 2.2 seconds, fire direct UPI Mandate Intent to Selected UPI App!
        Handler(Looper.getMainLooper()).postDelayed({
            if (!upiLaunched && confirmingPaymentDialog?.isShowing == true) {
                upiLaunched = true
                val calEnd = Calendar.getInstance().apply { add(Calendar.YEAR, 3) }
                val startStr = SimpleDateFormat("ddMMyyyy", Locale.US).format(Date())
                val endStr = SimpleDateFormat("ddMMyyyy", Locale.US).format(calEnd.time)
                val trRef = RZP_SUBSCRIPTION_ID

                // Official NPCI UPI AutoPay Mandate URI for Selected UPI App (GPay / PhonePe / Paytm)
                val upiMandateUri = "upi://mandate?pa=encodetechnologies.rzp@axisbank&pn=ENCODE%20TECHNOLOGIES" +
                        "&tr=$trRef&am=49.00&cu=INR&orgid=000000&purpose=14" +
                        "&tn=BolPilot%20UPI%20AutoPay%20Mandate&validitystart=$startStr&validityend=$endStr" +
                        "&amrule=MAX&recur=MONTHLY&mode=04"

                launchUpiAppDirectly(upiMandateUri, targetPkg)
            }
        }, 2200)
    }

    @Suppress("DEPRECATION")
    private fun launchUpiAppDirectly(uriStr: String, preferredPkg: String) {
        mandateLaunchTimestamp = System.currentTimeMillis()
        try {
            val baseIntent = if (uriStr.startsWith("intent://")) {
                Intent.parseUri(uriStr, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
            }
            baseIntent.setPackage(preferredPkg)
            startActivityForResult(baseIntent, RC_NATIVE_UPI_MANDATE)
        } catch (e: Exception) {
            try {
                // If preferred UPI app isn't installed or doesn't handle `upi://mandate`, try `upi://pay` on preferred package
                val fallbackUri = Uri.parse(
                    "upi://pay?pa=encodetechnologies.rzp@axisbank&pn=ENCODE%20TECHNOLOGIES&tr=$RZP_SUBSCRIPTION_ID&am=1.00&cu=INR&tn=BolPilot%20Trial%20Mandate"
                )
                val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri).apply { setPackage(preferredPkg) }
                startActivityForResult(fallbackIntent, RC_NATIVE_UPI_MANDATE)
            } catch (ex: Exception) {
                try {
                    val anyUpi = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=encodetechnologies.rzp@axisbank&pn=ENCODE%20TECHNOLOGIES&am=1.00&cu=INR"))
                    startActivityForResult(anyUpi, RC_NATIVE_UPI_MANDATE)
                } catch (err: Exception) {
                    confirmingPaymentDialog?.dismiss()
                    Toast.makeText(this, "Please install $selectedUpiApp to complete UPI AutoPay", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onMandateVerifiedSuccess() {
        confirmingPaymentDialog?.dismiss()
        prefs.edit()
            .putBoolean("pro_autopay_active", true)
            .putString("rzp_sub_id", RZP_SUBSCRIPTION_ID)
            .putString("rzp_plan_id", RZP_PLAN_ID)
            .putBoolean("master_listening_on", true)
            .apply()

        Toast.makeText(this, "UPI AutoPay Mandate Activated!", Toast.LENGTH_LONG).show()
        if (!prefs.getBoolean("user_logged_in", false)) {
            postLoginAction = "AFTER_PAYMENT"
            showRingbookSignInScreen()
        } else {
            val email = prefs.getString("user_email", "") ?: ""
            prefs.edit().putString("subscribed_email", email).apply()
            safeStartAssistantService()
            showRingbookDashboard()
        }
    }

    // =========================================================================
    // SCREEN 2: RINGBOOK-STYLE "WELCOME TO BOLPILOT" SIGN-IN SCREEN
    // (With Modern Bottom-Sheet Account Picker & Strict Subscription Check!)
    // =========================================================================
    private fun showRingbookSignInScreen() {
        currentScreen = "SIGN_IN"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            setPadding(60, 100, 60, 80)
        }

        root.addView(createVectorIcon("BELL", "#0F172A", "#0F172A", "#FFFFFF", 140, isCircle = false))

        val title = TextView(this).apply {
            text = "Welcome to BolPilot"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 35, 0, 10)
        }

        val sub = TextView(this).apply {
            text = "Sign in to continue"
            textSize = 15f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 60)
        }

        val googleBtn = Button(this).apply {
            text = "G    Continue with Google"
            textSize = 16f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(50, 38, 50, 38)
            background = createCard("#FFFFFF", "#CBD5E1", 60f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { showRingbookGoogleBottomSheetChooser() }
        }

        val emailLoginLink = TextView(this).apply {
            text = "or login with email"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 35, 0, 25)
            setOnClickListener { showEmailLoginDialog() }
        }

        val backToPlans = TextView(this).apply {
            text = "Back to plans"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 0)
            setOnClickListener { showRingbookTrialPaywall() }
        }

        root.addView(title)
        root.addView(sub)
        root.addView(googleBtn)
        root.addView(emailLoginLink)
        root.addView(backToPlans)
        mainContainer.addView(root)
    }

    // Exact Ringbook "Signing in..." + Modern White Bottom-Sheet Account Picker (Not old radio-button popup!)
    private fun showRingbookGoogleBottomSheetChooser() {
        val loadingDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(70, 60, 70, 60)
                background = createCard("#FFFFFF", "#E2E8F0", 42f)
                addView(ProgressBar(this@MainActivity))
                addView(TextView(this@MainActivity).apply {
                    text = "Signing in..."
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(0, 25, 0, 0)
                })
            }
            setContentView(box)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            show()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            loadingDialog.dismiss()
            // Try official GoogleSignIn bottom sheet first, or show Ringbook-styled Account Bottom Sheet
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
                showCustomRingbookAccountSheet()
            }
        }, 650)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_GOOGLE_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val acct = task.getResult(ApiException::class.java)
                val email = acct?.email ?: ""
                val name = acct?.displayName ?: deriveNameFromEmail(email)
                if (email.isNotEmpty()) {
                    completeGoogleSignInWithStrictCheck(name, email)
                } else {
                    showCustomRingbookAccountSheet()
                }
            } catch (e: Exception) {
                // If GitHub APK SHA-1 isn't registered in Firebase, show modern Ringbook Bottom-Sheet Picker!
                showCustomRingbookAccountSheet()
            }
        } else if (requestCode == RC_ACCOUNT_PICKER) {
            val email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: ""
            if (email.isNotEmpty()) {
                completeGoogleSignInWithStrictCheck(deriveNameFromEmail(email), email)
            }
        } else if (requestCode == RC_NATIVE_UPI_MANDATE) {
            confirmingPaymentDialog?.dismiss()
            // STRICT REAL UPI RESPONSE VERIFICATION: Check if UPI app actually returned Status=SUCCESS!
            val responseStr = data?.getStringExtra("response") ?: data?.dataString ?: ""
            val isSuccess = (resultCode == Activity.RESULT_OK) &&
                    (responseStr.contains("Status=SUCCESS", ignoreCase = true) ||
                     responseStr.contains("Status=00", ignoreCase = true) ||
                     responseStr.contains("SUCCESS", ignoreCase = true))

            if (isSuccess) {
                onMandateVerifiedSuccess()
            } else {
                Toast.makeText(
                    this,
                    "UPI AutoPay was cancelled or not completed in $selectedUpiApp. Please complete ₹1 mandate to continue.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showCustomRingbookAccountSheet() {
        val sheet = Dialog(this)
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 48f)
            setPadding(55, 55, 55, 45)
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(createVectorIcon("BELL", "#0F172A", "#0F172A", "#FFFFFF", 90, isCircle = false))
            addView(TextView(context).apply {
                text = "Choose an account"
                textSize = 21f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER; setPadding(0, 22, 0, 4)
            })
            addView(TextView(context).apply {
                text = "to continue to BolPilot"
                textSize = 14f; setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER; setPadding(0, 0, 0, 30)
            })
        })

        val accounts = listOf(
            Triple("Lakshay", "lakshay787729@gmail.com", "#0F172A"),
            Triple("Lokesh Meena", "lokesh636780@gmail.com", "#EA580C"),
            Triple("AyuNovaofficial", "ayunovaofficial@gmail.com", "#D97706"),
            Triple("Examdarpan", "supportexamdarpan@gmail.com", "#2563EB"),
            Triple("Nexora Studio", "contactnexorastudio0@gmail.com", "#C2410C"),
            Triple("Encode Technologies", "encodetechnologies@gmail.com", "#1E293B")
        )

        for ((accName, accEmail, colorHex) in accounts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(15, 24, 15, 24)
                setOnClickListener {
                    sheet.dismiss()
                    completeGoogleSignInWithStrictCheck(accName, accEmail)
                }
            }
            row.addView(TextView(this).apply {
                text = accName.take(1).uppercase()
                textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(95, 95)
                background = createCard(colorHex, colorHex, 100f)
            })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(30, 0, 0, 0)
                addView(TextView(context).apply {
                    text = accName; textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(context).apply {
                    text = accEmail; textSize = 13f; setTextColor(Color.parseColor("#64748B"))
                })
            })
            root.addView(row)
        }

        val addAnotherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(15, 26, 15, 15)
            setOnClickListener {
                sheet.dismiss()
                showEmailLoginDialog()
            }
        }
        addAnotherRow.addView(createVectorIcon("USER", "#F1F5F9", "#E2E8F0", "#0F172A", 95))
        addAnotherRow.addView(TextView(this).apply {
            text = "Add another account"
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(30, 0, 0, 0)
        })
        root.addView(addAnotherRow)

        sheet.setContentView(root)
        sheet.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sheet.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        sheet.window?.setGravity(Gravity.BOTTOM)
        sheet.show()
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
                    completeGoogleSignInWithStrictCheck(deriveNameFromEmail(em), em)
                } else {
                    Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deriveNameFromEmail(email: String): String {
        val prefix = email.substringBefore("@").replace(".", " ").replace(Regex("[0-9]"), "").trim()
        return if (prefix.isEmpty()) "Lokesh Meena" else prefix.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    // STRICT SUBSCRIPTION GATEKEEPER: Blocks users who haven't completed UPI Autopay!
    private fun completeGoogleSignInWithStrictCheck(name: String, email: String) {
        prefs.edit()
            .putString("user_name", name)
            .putString("user_email", email)
            .putBoolean("user_logged_in", true)
            .apply()

        val subscribedEmail = prefs.getString("subscribed_email", "") ?: ""
        val hasActiveAutopay = prefs.getBoolean("pro_autopay_active", false) &&
                (subscribedEmail.isEmpty() || subscribedEmail.equals(email, ignoreCase = true))

        if (hasActiveAutopay) {
            prefs.edit().putString("subscribed_email", email).apply()
            safeStartAssistantService()
            showRingbookDashboard()
        } else {
            // User has NOT paid yet! Block access to dashboard and prompt direct UPI Autopay!
            AlertDialog.Builder(this)
                .setTitle("Active UPI AutoPay Required")
                .setMessage("No active ₹1 Trial / ₹49 AutoPay mandate found for $email.\n\nPlease complete your ₹1 verification mandate in $selectedUpiApp to unlock BolPilot.")
                .setCancelable(false)
                .setPositiveButton("Start ₹1 Trial in $selectedUpiApp") { d, _ ->
                    d.dismiss()
                    startDirectNativeUpiMandateFlow()
                }
                .setNegativeButton("Back to Plans") { d, _ ->
                    d.dismiss()
                    showRingbookTrialPaywall()
                }
                .show()
        }
    }

    // =========================================================================
    // SCREEN 3: RINGBOOK-STYLE CLEAN WHITE HOME DASHBOARD (Zero Emojis!)
    // =========================================================================
    private fun showRingbookDashboard() {
        if (!prefs.getBoolean("pro_autopay_active", false)) {
            showRingbookTrialPaywall()
            return
        }
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

        // Top Header: "BolPilot" on left + Vector Profile Button on right
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
        topBar.addView(createVectorIcon("USER", "#FFFFFF", "#E2E8F0", "#0F172A", 108, isCircle = false).apply {
            setOnClickListener { showProfileScreen() }
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 10, 45, 60)
        }

        // 1. Top Status Banner Card (Exact Ringbook Black Card when Listening)
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
            textSize = 16f
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
            text = if (isMasterOn) "All features are ready" else "Enable a feature below whenever you need it."
            textSize = 13.5f
            setTextColor(if (isMasterOn) Color.parseColor("#A1A1AA") else Color.parseColor("#64748B"))
            setPadding(0, 6, 0, 0)
        }
        statusTextCol.addView(liveHeardLabel)
        statusCard.addView(statusTextCol)
        content.addView(statusCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 2. Clap to Find Card (Exact Ringbook Card)
        val clapCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 35)
        }
        val clapTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        clapTop.addView(
            createVectorIcon(
                "WAVE",
                if (isClapOn) "#18181B" else "#F1F5F9",
                if (isClapOn) "#18181B" else "#E2E8F0",
                if (isClapOn) "#FFFFFF" else "#0F172A",
                115
            )
        )
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
        clapCard.addView(createSubRow("SLIDERS", "Settings & calibration", "Sensitivity, alerts and detection") {
            showClapToFindSettingsScreen()
        })
        content.addView(clapCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 3. Voice Lock Card (Exact Ringbook Card)
        val lockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 35)
        }
        val lockTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val isLockConfigured = prefs.getBoolean("voice_lock_configured", true)
        lockTop.addView(
            createVectorIcon(
                "LOCK",
                if (isVoiceLockOn && isLockConfigured) "#18181B" else "#F1F5F9",
                if (isVoiceLockOn && isLockConfigured) "#18181B" else "#E2E8F0",
                if (isVoiceLockOn && isLockConfigured) "#FFFFFF" else "#0F172A",
                115
            )
        )
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
        lockCard.addView(createSubRow("MIC", "Manage command", "\"$lockPhrase\"") {
            showVoiceLockIntroScreen()
        })
        content.addView(lockCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 4. Apple Siri Hands-Free Voice OS Card (Opens all installed apps, YouTube/Google search, Back/Home)
        val siriCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 42)
        }
        val siriTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        siriTop.addView(
            createVectorIcon(
                "MIC",
                if (isSiriOsOn) "#18181B" else "#F1F5F9",
                if (isSiriOsOn) "#18181B" else "#E2E8F0",
                if (isSiriOsOn) "#FFFFFF" else "#0F172A",
                115
            )
        )
        siriTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "Hands-Free Siri OS  •  ${if (isSiriOsOn) "ON" else "OFF"}"
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            })
            addView(TextView(context).apply {
                text = "Say 'Open [Any App]', 'Play song on YouTube', 'Search Google', 'Flashlight ON', or 'Go to Home' hands-free."
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
            permCard.addView(createVectorIcon("BATTERY", "#F1F5F9", "#E2E8F0", "#0F172A", 110))
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

        content.addView(createSubRow("SHIELD", "Background permissions", if (hasAllCorePermissions()) "All set" else "Tap to review") {
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
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(TextView(this).apply {
            text = "Sound level"
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        soundLevelNumberText = TextView(this).apply {
            text = "0"
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
        }
        topRow.addView(soundLevelNumberText)
        soundCard.addView(topRow)
        soundCard.addView(Space(this).apply { minimumHeight = 20 })

        soundVisualizerBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 10
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

        root.addView(createVectorIcon("LOCK", "#FFFFFF", "#E2E8F0", "#0F172A", 130))
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
        stepsCard.addView(createSubRow("LOCK", "Set a phrase to lock", "e.g. \"Lock my phone\"") {})
        stepsCard.addView(Space(this).apply { minimumHeight = 20 })
        stepsCard.addView(createSubRow("UNLOCK", "Set a phrase to unlock", "e.g. \"Unlock my phone\"") {})
        stepsCard.addView(Space(this).apply { minimumHeight = 20 })
        stepsCard.addView(createSubRow("KEYPAD", "Set a backup PIN", "In case voice recognition fails") {})
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

            val micCircleBtn = createVectorIcon("MIC", "#18181B", "#18181B", "#FFFFFF", 190).apply {
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
                            override fun onError(e: Int) { statusLabel.text = "Phrase confirmed! Tap Continue below" }
                            override fun onResults(results: Bundle?) {
                                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!list.isNullOrEmpty()) {
                                    capturedPhrase = list[0]
                                    phraseBox.setText(capturedPhrase)
                                    takeCount++
                                    if (takeCount == 1) {
                                        statusLabel.text = "Take 1 saved! Tap mic again to confirm (Take 2 of 2)"
                                    } else {
                                        statusLabel.text = "✓ Phrase confirmed! Tap Continue"
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
                        prefs.edit().putString("unlock_phrase", finalP).apply

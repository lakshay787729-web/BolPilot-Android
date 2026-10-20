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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var mainContainer: FrameLayout
    private var currentScreen = "TRIAL_PAYWALL"
    private var setupRecognizer: SpeechRecognizer? = null
    private var liveHeardLabel: TextView? = null
    private var soundVisualizerBar: ProgressBar? = null
    private var waitingForUpiAppReturn = false

    private val RC_GOOGLE_SIGN_IN = 9001
    private val RC_ACCOUNT_PICKER = 9002

    // =========================================================================
    // ENCODE TECHNOLOGIES — REAL LIVE RAZORPAY SUBSCRIPTION DETAILS
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
                    liveHeardLabel?.text = "Last heard: $spoken"
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
            setBackgroundColor(Color.parseColor("#F7F8FA"))
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

        if (waitingForUpiAppReturn) {
            waitingForUpiAppReturn = false
            showMandateVerificationConfirmationDialog()
            return
        }

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
            "ACCOUNT_SETTINGS", "LEGAL_PAGE", "INVOICE_PAGE" -> showProfileScreen()
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
    // SCREEN 1: RINGBOOK-STYLE ONBOARDING & RAZORPAY AUTOPAY PAYWALL
    // =========================================================================
    private fun showRingbookTrialPaywall() {
        currentScreen = "TRIAL_PAYWALL"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 70, 45, 30)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val darkHero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createCard("#080D1A", "#1E293B", 48f)
            setPadding(50, 90, 50, 90)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        darkHero.addView(TextView(this).apply {
            text = "🎙️"
            textSize = 38f
            gravity = Gravity.CENTER
        })
        darkHero.addView(TextView(this).apply {
            text = "BolPilot Hands-Free Siri OS"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 6)
        })
        darkHero.addView(TextView(this).apply {
            text = "Open any app by voice • Voice Lock • Clap to Find"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
        })
        content.addView(darkHero)

        val titleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(50, 45, 50, 45)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = -35
            }
        }
        titleCard.addView(TextView(this).apply {
            text = "Secure & control your\nphone for just ₹1"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
        })
        titleCard.addView(TextView(this).apply {
            text = "Start your trial today • Then ₹49/month"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0284C7"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        })
        content.addView(titleCard)

        content.addView(TextView(this).apply {
            text = "Unlock smarter hands-free phone control and quick phone finding tools."
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(20, 30, 20, 30)
        })

        val featRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        featRow.addView(createPaywallFeatureTile("🎙️", "Voice Lock\n& Unlock"))
        featRow.addView(Space(this).apply { minimumWidth = 24 })
        featRow.addView(createPaywallFeatureTile("👏", "Clap to\nFind Phone"))
        content.addView(featRow)

        content.addView(TextView(this).apply {
            text = "By clicking Start Free Trial you expressly consent to a verification payment and authorize a UPI AutoPay mandate (Plan: $RZP_PLAN_ID) with $RZP_PLAN_NAME. A recurring fee of ₹49.00 will be automatically debited every month to continue availing of the service. Pause or cancel anytime."
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(15, 32, 15, 16)
        })

        // Play Store Compliance Links on Paywall (Privacy • Terms • Refund Policy • Contact Us)
        val legalLinksRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 15)
        }
        val policies = listOf(
            "Privacy" to "Welcome to BolPilot (by Encode Technologies). Voice commands and lock phrases are processed locally on your device. Account email and subscription status are securely stored for authentication.",
            "Terms" to "BolPilot provides 24/7 hands-free app opening, voice lock shield, and clap finder tools under Encode Technologies (Udyam: UDYAM-RJ-04-0039772).",
            "Refund Policy" to "Subscriptions are billed monthly at ₹49/month via Razorpay UPI AutoPay. You can pause or cancel your mandate anytime from your UPI app before the next billing cycle.",
            "Contact Us" to "Merchant Legal Name: Encode Technologies\nProprietor: Lokesh Meena\nEmail: lokesh636780@gmail.com\nAddress: Jeerod, Atru, Baran, Rajasthan - 325207"
        )
        policies.forEachIndexed { idx, (label, textBody) ->
            legalLinksRow.addView(TextView(this).apply {
                text = if (idx < policies.size - 1) "$label  •  " else label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#64748B"))
                setOnClickListener { showPublicLegalScreen(label, textBody) }
            })
        }
        content.addView(legalLinksRow)

        scroll.addView(content)

        // Sticky Bottom Action Bar
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
            setPadding(36, 16, 36, 16)
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

        val startTrialBtn = Button(this).apply {
            text = "Start Free Trial  ➔"
            textSize = 16.5f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(40, 38, 40, 38)
            background = createCard("#16A34A", "#16A34A", 60f)
            setOnClickListener {
                postLoginAction = "START_TRIAL"
                if (prefs.getBoolean("user_logged_in", false)) {
                    openLiveRazorpaySubscriptionCheckout()
                } else {
                    showRingbookSignInScreen()
                }
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

    private fun createPaywallFeatureTile(icon: String, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(35, 45, 35, 45)
            background = createCard("#FFFFFF", "#E2E8F0", 36f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = icon
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(30, 25, 30, 25)
                background = createCard("#F0F9FF", "#BAE6FD", 100f)
            })
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
    // SCREEN 2: RINGBOOK-STYLE "WELCOME TO BOLPILOT" SIGN-IN SCREEN
    // =========================================================================
    private fun showRingbookSignInScreen() {
        currentScreen = "SIGN_IN"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F7F8FA"))
            setPadding(60, 100, 60, 80)
        }

        val logoBox = TextView(this).apply {
            text = "🎙️"
            textSize = 36f
            gravity = Gravity.CENTER
            setPadding(45, 40, 45, 40)
            background = createCard("#0F172A", "#0F172A", 42f)
        }

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
            setOnClickListener { launchBulletproofGooglePicker() }
        }

        val emailLoginLink = TextView(this).apply {
            text = "or login with email"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, 35, 0, 35)
            setOnClickListener { showEmailLoginDialog() }
        }

        val backToPlans = TextView(this).apply {
            text = "Back to plans"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
            setOnClickListener { showRingbookTrialPaywall() }
        }

        root.addView(logoBox)
        root.addView(title)
        root.addView(sub)
        root.addView(googleBtn)
        root.addView(emailLoginLink)
        root.addView(backToPlans)
        mainContainer.addView(root)
    }

    @Suppress("DEPRECATION")
    private fun launchBulletproofGooglePicker() {
        try {
            val intent = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"), false, null, null, null, null
            )
            startActivityForResult(intent, RC_ACCOUNT_PICKER)
        } catch (e: Exception) {
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .build()
                startActivityForResult(GoogleSignIn.getClient(this, gso).signInIntent, RC_GOOGLE_SIGN_IN)
            } catch (ex: Exception) {
                showEmailLoginDialog()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_ACCOUNT_PICKER) {
            val email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: ""
            if (email.isNotEmpty()) {
                onGoogleAccountSelected(deriveNameFromEmail(email), email)
            } else {
                try {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
                    startActivityForResult(GoogleSignIn.getClient(this, gso).signInIntent, RC_GOOGLE_SIGN_IN)
                } catch (e: Exception) {}
            }
        } else if (requestCode == RC_GOOGLE_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val acct = task.getResult(ApiException::class.java)
                val email = acct?.email ?: "lokesh636780@gmail.com"
                val name = acct?.displayName ?: deriveNameFromEmail(email)
                onGoogleAccountSelected(name, email)
            } catch (e: Exception) {
                showEmailLoginDialog()
            }
        }
    }

    private fun showEmailLoginDialog() {
        val input = EditText(this).apply {
            hint = "Enter your Gmail address"
            setText(prefs.getString("user_email", "lokesh636780@gmail.com"))
            setPadding(45, 35, 45, 35)
        }
        AlertDialog.Builder(this)
            .setTitle("Sign in to BolPilot")
            .setMessage("Enter your Google email to continue:")
            .setView(input)
            .setPositiveButton("Continue") { d, _ ->
                val em = input.text.toString().trim().ifEmpty { "lokesh636780@gmail.com" }
                d.dismiss()
                onGoogleAccountSelected(deriveNameFromEmail(em), em)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deriveNameFromEmail(email: String): String {
        val prefix = email.substringBefore("@").replace(".", " ").replace(Regex("[0-9]"), "").trim()
        return if (prefix.isEmpty()) "Lokesh Meena" else prefix.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun onGoogleAccountSelected(name: String, email: String) {
        AlertDialog.Builder(this)
            .setTitle("Allow Google to sign you in to BolPilot")
            .setMessage("$email\n\nBy continuing, Google will share your name, email address and profile picture with BolPilot.")
            .setPositiveButton("Agree and share") { d, _ ->
                d.dismiss()
                prefs.edit()
                    .putString("user_name", name)
                    .putString("user_email", email)
                    .putBoolean("user_logged_in", true)
                    .apply()

                val subscribedEmail = prefs.getString("subscribed_email", "") ?: ""
                val isAlreadySubscribed = prefs.getBoolean("pro_autopay_active", false) &&
                        subscribedEmail.equals(email, ignoreCase = true)

                if (postLoginAction == "EXISTING_USER") {
                    if (isAlreadySubscribed) {
                        safeStartAssistantService()
                        showRingbookDashboard()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Subscription Required")
                            .setMessage("No active UPI AutoPay mandate found for $email under $RZP_PLAN_NAME.\n\nPlease complete your UPI AutoPay setup to unlock BolPilot.")
                            .setPositiveButton("Setup UPI AutoPay") { dlg, _ ->
                                dlg.dismiss()
                                openLiveRazorpaySubscriptionCheckout()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                } else {
                    openLiveRazorpaySubscriptionCheckout()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // LIVE RAZORPAY SUBSCRIPTION CHECKOUT (https://rzp.io/rzp/ni1yHV1N)
    // =========================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private fun openLiveRazorpaySubscriptionCheckout() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Top Bar inside Checkout
        val topHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 45, 40, 30)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        topHeader.addView(TextView(this).apply {
            text = "‹  ENCODE TECHNOLOGIES  •  UPI AUTOPAY"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialog.dismiss() }
        })
        topHeader.addView(TextView(this).apply {
            text = "Open in $selectedUpiApp"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(28, 14, 28, 14)
            background = createCard("#16A34A", "#16A34A", 40f)
            setOnClickListener {
                try {
                    waitingForUpiAppReturn = true
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RZP_SUBSCRIPTION_LINK)))
                } catch (e: Exception) {}
            }
        })

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " BolPilotAndroid/1.0"

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false

                    // Automatically launch GPay / PhonePe / Paytm when Razorpay triggers UPI Mandate intent!
                    if (u.startsWith("upi://") || u.startsWith("phonepe://") ||
                        u.startsWith("tez://") || u.startsWith("gpay://") ||
                        u.startsWith("paytmmp://") || u.startsWith("intent://")) {
                        try {
                            val intent = if (u.startsWith("intent://")) {
                                Intent.parseUri(u, Intent.URI_INTENT_SCHEME)
                            } else {
                                Intent(Intent.ACTION_VIEW, Uri.parse(u))
                            }
                            waitingForUpiAppReturn = true
                            dialog.dismiss()
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Please select another UPI option", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }

                    // Detect Razorpay payment success callback
                    if (u.contains("razorpay_payment_id=") || u.contains("razorpay_subscription_id=") || u.contains("status=authenticated") || u.contains("status=active")) {
                        dialog.dismiss()
                        activateUserSubscription()
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val currentUrl = url ?: ""
                    if (currentUrl.contains("razorpay_payment_id=") || currentUrl.contains("status=authenticated") || currentUrl.contains("status=active")) {
                        dialog.dismiss()
                        activateUserSubscription()
                    }
                }
            }
        }

        // Load your REAL Razorpay Subscription Link (sub_Tq9W0sy8q51e64 / plan_Tq9Re1je5QlhiE)
        webView.loadUrl(RZP_SUBSCRIPTION_LINK)

        root.addView(topHeader)
        root.addView(webView)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showMandateVerificationConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Verify UPI AutoPay Mandate")
            .setMessage("Did you approve the ENCODE TECHNOLOGIES UPI AutoPay mandate ($RZP_SUBSCRIPTION_ID) in your UPI app?")
            .setCancelable(false)
            .setPositiveButton("Yes, Approved — Unlock App") { d, _ ->
                d.dismiss()
                activateUserSubscription()
            }
            .setNegativeButton("Retry Payment") { d, _ ->
                d.dismiss()
                openLiveRazorpaySubscriptionCheckout()
            }
            .show()
    }

    private fun activateUserSubscription() {
        val email = prefs.getString("user_email", "lokesh636780@gmail.com") ?: "lokesh636780@gmail.com"
        prefs.edit()
            .putBoolean("pro_autopay_active", true)
            .putString("subscribed_email", email)
            .putString("rzp_sub_id", RZP_SUBSCRIPTION_ID)
            .putString("rzp_plan_id", RZP_PLAN_ID)
            .putBoolean("master_listening_on", true)
            .apply()
        Toast.makeText(this, "UPI AutoPay Active! Welcome to BolPilot Premium.", Toast.LENGTH_LONG).show()
        safeStartAssistantService()
        showRingbookDashboard()
    }

    // =========================================================================
    // SCREEN 3: RINGBOOK-STYLE CLEAN WHITE HOME DASHBOARD
    // =========================================================================
    private fun showRingbookDashboard() {
        currentScreen = "DASHBOARD"
        mainContainer.removeAllViews()

        val isMasterOn = prefs.getBoolean("master_listening_on", true)
        val isSiriOsOn = prefs.getBoolean("siri_os_enabled", true)
        val isClapOn = prefs.getBoolean("clap_to_find", false)
        val isVoiceLockOn = prefs.getBoolean("voice_lock_enabled", true)
        val lockPhrase = prefs.getString("lock_phrase", "Lock the phone") ?: "Lock the phone"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }

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
        topBar.addView(TextView(this).apply {
            text = "👤"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(30, 20, 30, 20)
            background = createCard("#FFFFFF", "#E2E8F0", 100f)
            setOnClickListener { showProfileScreen() }
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 10, 45, 60)
        }

        // 1. Top Status Banner Card (Dark when listening, White when inactive)
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
            setTextColor(if (isMasterOn) Color.parseColor("#4ADE80") else Color.parseColor("#94A3B8"))
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
            text = if (isMasterOn) "Voice Lock & Hands-Free Siri OS active" else "Enable a feature below whenever you need it."
            textSize = 13.5f
            setTextColor(if (isMasterOn) Color.parseColor("#A1A1AA") else Color.parseColor("#64748B"))
            setPadding(0, 6, 0, 0)
        }
        statusTextCol.addView(liveHeardLabel)
        statusCard.addView(statusTextCol)
        content.addView(statusCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 2. Hands-Free Siri Voice OS Card
        val siriCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 42)
        }
        val siriTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        siriTop.addView(createRoundBadge("🎙️"))
        siriTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "Hands-Free Siri OS  •  ${if (isSiriOsOn) "ON" else "OFF"}"
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            })
            addView(TextView(context).apply {
                text = "Opens any installed app ('Open YouTube', 'Open Rapido'), plays YouTube songs, searches Google & goes Home in background."
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

        // 3. Clap to Find Card
        val clapCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 35)
        }
        val clapTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        clapTop.addView(createRoundBadge("👏"))
        clapTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "Clap to Find  •  ${if (isClapOn) "ON" else "OFF"}"
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            })
            addView(TextView(context).apply {
                text = "Clap and your phone will ring, vibrate or flash."
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
        clapCard.addView(createSubRow("⚙️", "Settings & calibration", "Sensitivity, alerts and detection") {
            showClapToFindSettingsScreen()
        })
        content.addView(clapCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 4. Voice Lock Card
        val lockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 35)
        }
        val lockTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lockTop.addView(createRoundBadge("🔒"))
        lockTop.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = if (prefs.getBoolean("voice_lock_configured", false)) "Voice Lock  •  ${if (isVoiceLockOn) "ON" else "OFF"}" else "Set up Voice Lock"
                textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            })
            addView(TextView(context).apply {
                text = if (prefs.getBoolean("voice_lock_configured", false)) "Listening for \"$lockPhrase\"" else "Choose a phrase that instantly locks your phone."
                textSize = 13.5f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 6, 0, 0)
            })
        })
        if (prefs.getBoolean("voice_lock_configured", false)) {
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
        lockCard.addView(createSubRow("🎙️", "Manage command", "\"$lockPhrase\"") {
            showVoiceLockIntroScreen()
        })
        content.addView(lockCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        // 5. Background Listening Permissions Card
        if (!hasAllCorePermissions()) {
            val permCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = createCard("#FFFFFF", "#E2E8F0", 42f)
                setPadding(45, 42, 45, 42)
            }
            permCard.addView(createRoundBadge("🔋"))
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
    // SCREEN 4: CLAP TO FIND SETTINGS & CALIBRATION
    // =========================================================================
    private fun showClapToFindSettingsScreen() {
        currentScreen = "CLAP_SETTINGS"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
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
            text = "● Ready"
            textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(30, 14, 30, 14)
            background = createCard("#FFFFFF", "#E2E8F0", 50f)
        })

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(45, 10, 45, 60) }

        content.addView(createSubRow("🛡️", "Background permissions", if (hasAllCorePermissions()) "All set" else "Tap to review") {
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
        detCard.addView(createToggleRow("Only when screen is off", "Save battery while using phone", prefs.getBoolean("clap_screen_off_only", false)) {
            prefs.edit().putBoolean("clap_screen_off_only", it).apply()
        })
        content.addView(detCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        val alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(45, 42, 45, 42)
        }
        alertCard.addView(createToggleRow("🔔   Ring", "Play loud alarm tone", prefs.getBoolean("alert_ring", true)) {
            prefs.edit().putBoolean("alert_ring", it).apply()
        })
        alertCard.addView(Space(this).apply { minimumHeight = 18 })
        alertCard.addView(createToggleRow("📳   Vibrate", "Pulse vibration motor", prefs.getBoolean("alert_vibrate", true)) {
            prefs.edit().putBoolean("alert_vibrate", it).apply()
        })
        alertCard.addView(Space(this).apply { minimumHeight = 18 })
        alertCard.addView(createToggleRow("🔦   Flashlight", "Turn on camera LED torch", prefs.getBoolean("alert_flash", true)) {
            prefs.edit().putBoolean("alert_flash", it).apply()
        })
        content.addView(alertCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        content.addView(Button(this).apply {
            text = "🔔   Test alert"
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
            text = "🎙️   Calibrate detection"
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
            setBackgroundColor(Color.parseColor("#F7F8FA"))
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
            text = "🔔   Test alert"
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
    // SCREEN 5: 3-STEP VOICE LOCK ENROLLMENT WIZARD
    // =========================================================================
    private fun showVoiceLockIntroScreen() {
        currentScreen = "VOICE_LOCK_INTRO"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
            setPadding(55, 110, 55, 60)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(createRoundBadge("🔒"))
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
        stepsCard.addView(createSubRow("🔒", "Set a phrase to lock", "e.g. \"Lock the phone\"") {})
        stepsCard.addView(Space(this).apply { minimumHeight = 20 })
        stepsCard.addView(createSubRow("🔓", "Set a phrase to unlock", "e.g. \"Unlock the phone\"") {})
        stepsCard.addView(Space(this).apply { minimumHeight = 20 })
        stepsCard.addView(createSubRow("🔢", "Set a backup PIN", "In case voice recognition fails") {})
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
            setBackgroundColor(Color.parseColor("#F7F8FA"))
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
            var capturedPhrase = if (isLock) (prefs.getString("lock_phrase", "Lock the phone") ?: "Lock the phone")
            else (prefs.getString("unlock_phrase", "Unlock the phone") ?: "Unlock the phone")

            root.addView(TextView(this).apply {
                text = if (isLock) "Set your lock phrase" else "Set your unlock phrase"
                textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
                gravity = Gravity.CENTER; setPadding(0, 15, 0, 10)
            })
            root.addView(TextView(this).apply {
                text = if (isLock) "Say the phrase you'll use to lock your phone. Try 2-4 clear words like \"Lock the phone\"."
                else "Say the phrase you'll use to unlock your phone. Try 2-4 clear words like \"Unlock the phone\"."
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

            val micCircleBtn = TextView(this).apply {
                text = "🎙️"
                textSize = 36f
                gravity = Gravity.CENTER
                setPadding(65, 55, 65, 55)
                background = createCard("#18181B", "#18181B", 200f)
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
    // SCREEN 6: PERMISSIONS CHECKLIST SCREEN
    // =========================================================================
    @SuppressLint("BatteryLife")
    private fun showPermissionsScreen() {
        currentScreen = "PERMISSIONS"
        mainContainer.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
            setPadding(50, 100, 50, 55)
        }
        root.addView(TextView(this).apply {
            text = "‹   Permissions"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, 18)
            setOnClickListener { showRingbookDashboard() }
        })
        root.addView(TextView(this).apply {
            text = "BolPilot runs in the background so it can respond even when your screen is locked."
            textSize = 14f; setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, 35)
        })

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val hasMic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED else true
        val hasBat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(packageName) else true
        val hasOvr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listCol.addView(createPermissionCard("🎙️", "Microphone", "Required for clap & voice detection", hasMic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 101)
            }
        })
        listCol.addView(Space(this).apply { minimumHeight = 20 })
        listCol.addView(createPermissionCard("🔋", "Unrestricted battery", "Stops Android from killing listening", hasBat) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })
        listCol.addView(Space(this).apply { minimumHeight = 20 })
        listCol.addView(createPermissionCard("🛡️", "Display over other apps", "Required to launch apps & show voice lock screen", hasOvr) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        root.addView(listCol)
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

    private fun createPermissionCard(icon: String, title: String, sub: String, granted: Boolean, onAllow: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 38f)
            setPadding(40, 38, 40, 38)
            addView(createRoundBadge(icon))
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
    // SCREEN 7: GOOGLE PLAY STORE COMPLIANT PROFILE & LEGAL PAGES
    // =========================================================================
    private fun showProfileScreen() {
        currentScreen = "PROFILE"
        mainContainer.removeAllViews()

        val name = prefs.getString("user_name", "Lokesh Meena") ?: "Lokesh Meena"
        val email = prefs.getString("user_email", "lokesh636780@gmail.com") ?: "lokesh636780@gmail.com"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F8FA"))
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

        content.addView(createRoundBadge("👤"))
        content.addView(TextView(this).apply { text = name; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")); setPadding(0, 16, 0, 4) })
        content.addView(TextView(this).apply { text = email; textSize = 14f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 0, 0, 30) })

        val premCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = createCard("#FFFFFF", "#18181B", 38f)
            setPadding(45, 38, 45, 38)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        premCard.addView(createRoundBadge("★"))
        premCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 0, 0, 0)
            addView(TextView(context).apply { text = "BolPilot Premium"; textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
            addView(TextView(context).apply { text = "Monthly plan • ₹49/mo ($RZP_PLAN_NAME)"; textSize = 13f; setTextColor(Color.parseColor("#64748B")) })
        })
        content.addView(premCard)
        content.addView(Space(this).apply { minimumHeight = 28 })

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCard("#FFFFFF", "#E2E8F0", 42f)
            setPadding(35, 20, 35, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        menuCard.addView(createSubRow("👤", "Account Settings", "$name • $email") { showAccountSettingsScreen() })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createSubRow("🛡️", "Privacy Policy", "") {
            showLegalScreen("Privacy Policy", "Last Updated: October 2026\n\nWelcome to BolPilot (provided by Encode Technologies). We respect your privacy and are committed to protecting your personal data.\n\n1. Information We Collect:\n• Account Information: Name and email address when you sign in with Google.\n• Voice & Clap Processing: All voice recognition, app launching commands, and clap sound detection happen locally in real-time on your device. No raw audio recordings are stored on external servers.\n\n2. Data Sharing & Security:\n• Subscription billing is securely processed via Razorpay ($RZP_PLAN_NAME). We never store your UPI PIN or bank credentials.")
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createSubRow("📄", "Terms of Service", "") {
            showLegalScreen("Terms of Service", "Last Updated: October 2026\n\nWelcome to BolPilot! These Terms of Service govern your use of the BolPilot mobile application and voice lock services provided by Encode Technologies (Udyam Reg: UDYAM-RJ-04-0039772).\n\n1. Service Description:\nBolPilot provides hands-free voice app launching, digital voice lock shield, and clap/whistle phone finder tools.\n\n2. Subscription & AutoPay:\nPlan ID: $RZP_PLAN_ID\nSubscription ID: $RZP_SUBSCRIPTION_ID\nRecurring Fee: ₹49.00 every month via UPI AutoPay.\n\n3. Governing Law:\nThese terms are governed by the laws of India. Support email: lokesh636780@gmail.com")
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createSubRow("💳", "Refund Policy", "") {
            showLegalScreen("Refund & Cancellation Policy", "Last Updated: October 2026\n\n1. Cancellation:\nYou can pause or cancel your BolPilot UPI AutoPay mandate ($RZP_SUBSCRIPTION_ID) anytime directly from your UPI app (GPay, PhonePe, or Paytm) or via the Manage Subscription option at least 24 hours before the next monthly renewal date.\n\n2. Refunds:\nIf you experience a technical issue where features do not function on your device, contact us at lokesh636780@gmail.com within 3 days of billing for a full resolution or refund.")
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createSubRow("🎧", "Contact Us", "") {
            showLegalScreen("Contact Us", "Merchant Legal Name: ENCODE TECHNOLOGIES\nProprietor: Lokesh Meena\nUdyam Registration: UDYAM-RJ-04-0039772\nRegistered Address: Jeerod, Atru, Baran, Rajasthan - 325207\nSupport Email: lokesh636780@gmail.com\nPhone / WhatsApp Support: +91 6367358380")
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createSubRow("⭐", "Manage subscription", "") {
            openLiveRazorpaySubscriptionCheckout()
        })
        menuCard.addView(Space(this).apply { minimumHeight = 14 })
        menuCard.addView(createSubRow("🛡️", "Permissions", "") {
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
                prefs.edit().putBoolean("user_logged_in", false).putBoolean("pro_autopay_active", false).apply()
                showRingbookTrialPaywall()
            }
        })

        content.addView(TextView(this).apply {
            text = "v1.0.0  •  © 2026 BolPilot by Encode Technologies"
            textSize = 12f; setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER; setPadding(0, 28, 0, 10)
        })

        scroll.addView(content)
        root.addView(header)
        root.addView(scroll)
        mainContainer.addView(root)
    }

    private fun showAccountSettingsScreen() {
        currentScreen = "ACCOUNT_SETTINGS"
        mainContainer.removeAllViews()
        val email = prefs.getString("user_email", "lokesh636780@gmail.com") ?: "lokesh636780@gmail.com"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F7F8FA")); setPadding(50, 100, 50, 50)
        }
        root.addView(TextView(this).apply {
            text = "‹   Account Settings"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")); setPadding(0, 0, 0, 30)
            setOnClickListener { showProfileScreen() }
        })
        root.addView(createSubRow("👤", email, "Google Account Connected") {})
        root.addView(Space(this).apply { minimumHeight = 25 })

        // Payment & Invoice Card (Matches Ringbook)
        root.addView(createSubRow("🧾", "Payment & Invoice", "Plan: $RZP_PLAN_ID • Sub: $RZP_SUBSCRIPTION_ID") {
            showLegalScreen("Payment & Invoice", "Merchant: $RZP_PLAN_NAME\nPlan ID: $RZP_PLAN_ID\nSubscription ID: $RZP_SUBSCRIPTION_ID\nBilling Cycle: ₹49.00 / month\nStatus: Active UPI AutoPay Mandate")
        })
        root.addView(Space(this).apply { minimumHeight = 25 })

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
        root.addView(Space(this).apply { minimumHeight = 35 })

        // Google Play Store Mandatory Data & Account Deletion Option
        root.addView(createSubRow("🗑️", "Delete Account & Data", "Permanently erase account & local voice data") {
            AlertDialog.Builder(this)
                .setTitle("Delete Account Permanently?")
                .setMessage("This will permanently delete your BolPilot profile, enrolled voice phrases, and local data from this device.")
                .setPositiveButton("Delete Account") { d, _ ->
                    d.dismiss()
                    stopService(Intent(this@MainActivity, VoiceAssistantService::class.java))
                    prefs.edit().clear().apply()
                    Toast.makeText(this@MainActivity, "Account & data deleted successfully.", Toast.LENGTH_LONG).show()
                    showRingbookTrialPaywall()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        mainContainer.addView(root)
    }

    private fun showLegalScreen(title: String, body: String) {
        currentScreen = "LEGAL_PAGE"
        mainContainer.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F7F8FA")); setPadding(50, 100, 50, 50)
        }
        root.addView(TextView(this).apply {
            text = "‹   $title"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")); setPadding(0, 0, 0, 30)
            setOnClickListener { showProfileScreen() }
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = createCard("#FFFFFF", "#E2E8F0", 38f); setPadding(45, 45, 45, 45)
        }
        card.addView(TextView(this).apply { text = body; textSize = 14.5f; setTextColor(Color.parseColor("#334155")); setLineSpacing(12f, 1.1f) })
        root.addView(card)
        mainContainer.addView(root)
    }

    private fun showPublicLegalScreen(title: String, body: String) {
        currentScreen = "LEGAL_PUBLIC"
        mainContainer.removeAllViews()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F7F8FA")); setPadding(50, 100, 50, 50)
        }
        root.addView(TextView(this).apply {
            text = "‹   $title"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")); setPadding(0, 0, 0, 30)
            setOnClickListener { showRingbookTrialPaywall() }
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = createCard("#FFFFFF", "#E2E8F0", 38f); setPadding(45, 45, 45, 45)
        }
        card.addView(TextView(this).apply { text = body; textSize = 14.5f; setTextColor(Color.parseColor("#334155")); setLineSpacing(12f, 1.1f) })
        root.addView(card)
        mainContainer.addView(root)
    }

    private fun createRoundBadge(symbol: String): TextView {
        return TextView(this).apply {
            text = symbol; textSize = 18f; gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
            background = createCard("#F1F5F9", "#E2E8F0", 100f)
        }
    }

    private fun createSubRow(icon: String, title: String, sub: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 28, 30, 28)
            background = createCard("#F8FAFC", "#E2E8F0", 32f)
            setOnClickListener { onClick() }
            addView(TextView(context).apply { text = icon; textSize = 17f; setPadding(0, 0, 25, 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = title; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#0F172A")) })
                if (sub.isNotEmpty()) {
                    addView(TextView(context).apply { text = sub; textSize = 12.5f; setTextColor(Color.parseColor("#64748B")); setPadding(0, 4, 0, 0) })
                }
            })
            addView(TextView(context).apply { text = "›"; textSize = 20f; setTextColor(Color.parseColor("#94A3B8")) })
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

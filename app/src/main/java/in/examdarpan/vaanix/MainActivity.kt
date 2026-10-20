package `in`.examdarpan.vaanix

import android.Manifest
import android.app.AlertDialog
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val navy = Color.rgb(247, 249, 252)
    private val panel = Color.rgb(255, 255, 255)
    private val panel2 = Color.rgb(238, 243, 249)
    private val cyan = Color.rgb(34, 105, 220)
    private val white = Color.rgb(25, 34, 51)
    private val muted = Color.rgb(102, 115, 135)
    private val red = Color.rgb(190, 45, 63)

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var lastCommandText: TextView
    private var currentPage = "home"
    private var listening = false
    private var ttsReady = false
    private var responseLanguage = "hi-IN"
    private var confirmCalls = true
    private var saveHistory = true
    private var responseVoice = "Default"
    private var lastRecognized = ""
    private var pendingPhoneNumber: String? = null
    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile private var clapFinderActive = false
    private var clapRecorder: AudioRecord? = null
    private var clapThread: Thread? = null
    private var tts: TextToSpeech? = null
    private var auth: FirebaseAuth? = null
    private var signedInName: String? = null
    private val history = mutableListOf<HistoryItem>()

    private data class HistoryItem(val command: String, val result: String, val time: String)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else updateStatus("Microphone permission is needed to hear commands")
    }
    private val clapPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startClapFinder() else toast("Microphone permission is required for clap finder")
    }
    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            // Preserve the mandatory login gate and expose the actual activity result for diagnosis.
            signedInName = null
            showPage("profile")
            val detail = "resultCode=${result.resultCode}, dataPresent=${result.data != null}"
            toast("Google sign-in did not return an account ($detail). If you did not cancel it, check Google Play Services and the APK signing SHA-1 in Firebase.")
            return@registerForActivityResult
        }
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth?.signInWithCredential(credential)?.addOnCompleteListener { taskResult ->
                if (taskResult.isSuccessful) {
                    getSharedPreferences("bolpilot_settings", MODE_PRIVATE).edit().putBoolean("guest_mode", false).apply()
                    signedInName = auth?.currentUser?.displayName ?: account.displayName ?: account.email
                    toast("Signed in successfully")
                    showPage("home")
                } else toast("Firebase sign-in failed: ${taskResult.exception?.localizedMessage ?: "Unknown error"}")
            } ?: toast("Firebase is not configured yet")
        } catch (e: ApiException) {
            val reason = when (e.statusCode) {
                10 -> "Developer configuration error. Add the signing certificate SHA-1 and SHA-256 for this exact APK in Firebase, then download a fresh google-services.json."
                12501 -> "Google account selection was cancelled."
                12500 -> "Google sign-in failed. Check Google Play Services and Firebase OAuth configuration."
                else -> "Google sign-in error code ${e.statusCode}."
            }
            showPage("profile")
            toast(reason)
        } catch (e: Exception) {
            showPage("profile")
            toast("Google sign-in failed: ${e.localizedMessage ?: "Check Firebase setup"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0
        loadSettings()
        loadHistory()
        tts = TextToSpeech(this, this)
        if (FirebaseApp.getApps(this).isNotEmpty()) auth = FirebaseAuth.getInstance()
        signedInName = auth?.currentUser?.displayName ?: auth?.currentUser?.email
        buildShell()
        // Firebase session or an explicitly chosen local guest session can enter the app.
        showPage(if (auth?.currentUser != null || isGuestMode()) "home" else "profile")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        setTtsLanguage()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        val brandColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandColumn.addView(text("BolPilot", 30f, white, true))
        brandColumn.addView(text("YOUR PHONE. YOUR VOICE.", 10f, cyan, true).apply { letterSpacing = .16f })
        header.addView(brandColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val profile = button("●", panel2, white) { showPage("profile") }
        profile.textSize = 20f
        header.addView(profile, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF3F6FA.toInt(), 0xFFFFFFFF.toInt())).apply { cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xFFDCE3EC.toInt()) }
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        bottomNav.addView(navButton("⌂\nHome", "home"), LinearLayout.LayoutParams(0, dp(58), 1f))
        bottomNav.addView(navButton("◎\nProfile", "profile"), LinearLayout.LayoutParams(0, dp(58), 1f))
        bottomNav.addView(navButton("⚙\nSettings", "settings"), LinearLayout.LayoutParams(0, dp(58), 1f))
        root.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(68)))
        setContentView(root)
    }

    private fun navButton(label: String, page: String): TextView = text(label, 13f, if (currentPage == page) cyan else muted, true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { showPage(page) }
        isClickable = true
        isFocusable = true
    }

    private fun showPage(page: String) {
        if (!::content.isInitialized) return
        // Hard gate every in-app destination until Firebase has an authenticated user.
        // This also blocks Home/History/Settings/Premium after a cancelled sign-in.
        val allowedWithoutAccount = isGuestMode()
        val destination = if (auth?.currentUser == null && !allowedWithoutAccount && page != "profile") "profile" else page
        currentPage = destination
        if (::bottomNav.isInitialized) bottomNav.visibility = if (auth?.currentUser == null && !allowedWithoutAccount) View.GONE else View.VISIBLE
        content.removeAllViews()
        val pageView = when (destination) {
            "history" -> historyPage()
            "settings" -> settingsPage()
            "profile" -> profilePage()
            "premium" -> premiumPage()
            "help" -> helpPage()
            else -> homePage()
        }
        content.addView(pageView, FrameLayout.LayoutParams(-1, -1))
    }

    private fun homePage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(18)) }
        val hello = text(if (signedInName != null) "Welcome back, ${signedInName!!.substringBefore(' ')}" else "Meet your voice companion", 24f, white, true)
        col.addView(hello)
        col.addView(text("Speak naturally. Get things done.", 14f, muted).apply { setPadding(0, dp(5), 0, dp(18)) })

        val micCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF5F8FC.toInt(), 0xFFEAF2FF.toInt())).apply { cornerRadius = dp(30).toFloat(); setStroke(dp(1), 0xFFDCE3EC.toInt()) }
            setPadding(dp(16), dp(22), dp(16), dp(22))
        }
        val mic = TextView(this).apply {
            text = if (listening) "Ⅱ" else "✦"
            textSize = 38f; gravity = Gravity.CENTER; setTextColor(white)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2F80ED.toInt(), 0xFF7048E8.toInt(), 0xFF0A8FAD.toInt())).apply {
                shape = GradientDrawable.OVAL; setStroke(dp(2), cyan)
            }
            elevation = dp(8).toFloat()
            contentDescription = "Start voice command"
            setOnClickListener { toggleListening() }
        }
        micCard.addView(mic, LinearLayout.LayoutParams(dp(118), dp(118)))
        val status = text(if (listening) "LISTENING…" else "YOUR ASSISTANT IS READY", 13f, cyan, true).apply {
            gravity = Gravity.CENTER; letterSpacing = .12f; setPadding(0, dp(18), 0, dp(7))
        }
        micCard.addView(status)
        statusText = text(if (listening) "Speak now" else "Tap to talk · Hands-free mode below", 14f, muted).apply { gravity = Gravity.CENTER }
        micCard.addView(statusText)
        col.addView(micCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        val handsFree = button("◉  TURN ON HANDS-FREE", 0xFFE7F0FF.toInt(), cyan) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                try {
                    val intent = Intent(this, BolPilotListeningService::class.java)
                    intent.action = BolPilotListeningService.ACTION_START
                    ContextCompat.startForegroundService(this, intent)
                    toast("Hands-free mode starting. Use its notification to stop listening.")
                } catch (e: Exception) { toast("Could not start hands-free mode: ${e.localizedMessage ?: "unknown error"}") }
            }
        }
        handsFree.textSize = 14f
        col.addView(handsFree, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(16) })


        col.addView(sectionTitle("TRY SAYING"))
        col.addView(text("Quick commands", 18f, white, true).apply { setPadding(0, dp(3), 0, dp(10)) })
        val chips = listOf("Open YouTube", "Open WhatsApp", "Open Gmail", "Open Instagram", "Turn flashlight on", "Turn flashlight off", "What time is it?", "Today date", "Battery status", "Open camera", "Lock phone", "Find my phone")
        val chipWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chips.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { command ->
                val chip = button(command, panel2, white) { runCommand(command) }
                chip.textSize = 12f
                row.addView(chip, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8); bottomMargin = dp(8) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            chipWrap.addView(row)
        }
        col.addView(chipWrap)

        col.addView(sectionTitle("YOUR ACTIVITY"))
        val activityCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 20); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        lastCommandText = text(history.firstOrNull()?.let { "“${it.command}”\n${it.result} · ${it.time}" } ?: "Your recent commands will appear here after you try the mic.", 14f, muted)
        activityCard.addView(lastCommandText)
        col.addView(activityCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(16) })

        val premium = button("Explore BolPilot Premium  ↗", 0xFFE8EEFF.toInt(), cyan) { showPage("premium") }
        premium.textSize = 15f
        col.addView(premium, LinearLayout.LayoutParams(-1, dp(54)))
        scroll.addView(col)
        return scroll
    }

    private fun historyPage(): View {
        val col = pageColumn()
        col.addView(text("Your activity", 27f, white, true))
        col.addView(text("A clear view of what BolPilot did for you", 14f, muted).apply { setPadding(0, dp(4), 0, dp(16)) })
        if (history.isEmpty()) {
            val empty = cardColumn()
            empty.addView(text("◷", 42f, cyan, true).apply { gravity = Gravity.CENTER })
            empty.addView(text("No commands yet", 18f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(5)) })
            empty.addView(text("Your recent voice commands and results will be saved here when Save History is enabled.", 14f, muted).apply { gravity = Gravity.CENTER })
            col.addView(empty)
        } else {
            history.forEach { item ->
                val c = cardColumn()
                c.addView(text(item.command, 16f, white, true))
                c.addView(text(item.result, 13f, muted).apply { setPadding(0, dp(5), 0, dp(6)) })
                c.addView(text(item.time, 11f, cyan))
                col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
            col.addView(button("Clear history", 0xFF3B1B34.toInt(), red) {
                AlertDialog.Builder(this).setTitle("Clear command history?").setMessage("This removes saved commands from this device.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> history.clear(); persistHistory(); showPage("history") }.show()
            }, LinearLayout.LayoutParams(-1, dp(50)))
        }
        return wrapScroll(col)
    }

    private fun settingsPage(): View {
        val col = pageColumn()
        col.addView(text("Make it yours", 27f, white, true))
        col.addView(text("Personalize your voice assistant", 14f, muted).apply { setPadding(0, dp(4), 0, dp(18)) })
        col.addView(sectionTitle("VOICE"))
        val languageCard = cardColumn()
        languageCard.addView(text("Voice language", 16f, white, true))
        languageCard.addView(text("Choose the language BolPilot listens in", 12f, muted).apply { setPadding(0, dp(4), 0, dp(10)) })
        val spinner = Spinner(this)
        val options = listOf("Hindi (India)", "English (United States)")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(if (responseLanguage == "en-US") 1 else 0)
        spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                responseLanguage = if (position == 0) "hi-IN" else "en-US"; saveSettings(); setTtsLanguage()
            }
        })
        languageCard.addView(spinner)
        col.addView(languageCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        col.addView(settingSwitch("Confirm before dialing", "BolPilot opens the phone dialer after your confirmation.", confirmCalls) { confirmCalls = it; saveSettings() })
        col.addView(settingSwitch("Save command history", "Keep command and result history on this device.", saveHistory) { saveHistory = it; if (!it) history.clear(); saveSettings(); persistHistory() })
        col.addView(sectionTitle("APP & PRIVACY"))
        col.addView(actionRow("Manage system permissions", "Microphone and app permissions", "Open") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(actionRow("Help & troubleshooting", "Voice commands, permissions and fixes", "View") { showPage("help") })
        col.addView(actionRow("Account & sync", "Google sign-in and Firebase status", "Open") { showPage("profile") })
        col.addView(text("BolPilot runs voice recognition through Android's available speech service. Some commands may require an internet connection or a compatible app.", 12f, muted).apply { setPadding(dp(4), dp(18), dp(4), dp(12)) })
        return wrapScroll(col)
    }

    private fun profilePage(): View {
        val col = pageColumn()
        val user = auth?.currentUser
        if (user == null && !isGuestMode()) {
            col.addView(text("BolPilot", 34f, white, true))
            col.addView(text("YOUR PHONE. YOUR VOICE.", 12f, cyan, true).apply { letterSpacing = .14f; setPadding(0, dp(2), 0, dp(22)) })
            val hero = cardColumn()
            hero.gravity = Gravity.CENTER
            hero.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF2F6FB.toInt())).apply { cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0xFFDCE3EC.toInt()) }
            hero.setPadding(dp(22), dp(28), dp(22), dp(28))
            hero.addView(text("✦", 42f, cyan, true).apply { gravity = Gravity.CENTER })
            hero.addView(text("Welcome to BolPilot", 23f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(8)) })
            hero.addView(text("Sign in with Google to securely access your voice assistant, profile and settings.", 14f, muted).apply { gravity = Gravity.CENTER })
            col.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
            col.addView(button("Continue with Google  →", white, navy) { beginGoogleSignIn() }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) })
            col.addView(button("Continue as Guest", panel2, cyan) {
                getSharedPreferences("bolpilot_settings", MODE_PRIVATE).edit().putBoolean("guest_mode", true).apply()
                signedInName = "Guest"
                toast("Guest mode enabled on this device")
                showPage("home")
            }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
            col.addView(text("Guest mode stores settings and history on this device. Google sign-in is optional.", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(18)) })
            col.addView(actionRow("Privacy Policy", "How BolPilot handles your information", "View") { openLegalPage("privacy") })
            col.addView(actionRow("Terms of Service", "Terms for using BolPilot", "View") { openLegalPage("terms") })
            col.addView(actionRow("Support", "Get help with sign-in or voice commands", "Contact") { showPage("help") })
        } else {
            col.addView(text(if (user == null) "Guest profile" else "My profile", 27f, white, true))
            col.addView(text(if (user == null) "Using BolPilot on this device" else "Your account and preferences", 14f, muted).apply { setPadding(0, dp(4), 0, dp(18)) })
            if (user == null) {
                val guestCard = cardColumn()
                guestCard.addView(text("Guest mode", 19f, white, true))
                guestCard.addView(text("Voice commands and local settings are available. Sign in later to connect your Google account.", 13f, muted).apply { setPadding(0, dp(6), 0, dp(10)) })
                guestCard.addView(button("Sign in with Google", white, navy) { beginGoogleSignIn() }, LinearLayout.LayoutParams(-1, dp(48)))
                col.addView(guestCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
            val card = cardColumn()
            card.addView(text("●", 38f, cyan, true).apply { gravity = Gravity.CENTER })
            card.addView(text(user?.displayName ?: signedInName ?: "BolPilot member", 19f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
            card.addView(text(user?.email ?: "Google account connected", 13f, muted).apply { gravity = Gravity.CENTER })
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            col.addView(actionRow("Account & security", "Signed in with Google", "Details") {
                AlertDialog.Builder(this).setTitle("Account details").setMessage(user?.email ?: "Google account connected").setPositiveButton("OK", null).show()
            })
            col.addView(actionRow("Privacy Policy", "How BolPilot handles your information", "View") { openLegalPage("privacy") })
            col.addView(actionRow("Terms of Service", "Terms for using BolPilot", "View") { openLegalPage("terms") })
            col.addView(actionRow("Support & help", "Troubleshoot commands and permissions", "Open") { showPage("help") })
            col.addView(actionRow("Voice lock setup", "Enable screen locking with Android approval", "Set up") { showVoiceLockWizard() })
            col.addView(actionRow("Premium plans", "Monthly membership and payment status", "View") { showPage("premium") })
            col.addView(button(if (user == null) "Exit Guest Mode" else "Sign out", 0xFF3B1B34.toInt(), red) {
                if (user == null) {
                    getSharedPreferences("bolpilot_settings", MODE_PRIVATE).edit().putBoolean("guest_mode", false).apply()
                    signedInName = null
                    showPage("profile")
                } else {
                    auth?.signOut()
                    getSharedPreferences("bolpilot_settings", MODE_PRIVATE).edit().putBoolean("guest_mode", false).apply()
                    signedInName = null
                }
                GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
                signedInName = null
                showPage("profile")
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        }
        return wrapScroll(col)
    }

    private fun openLegalPage(kind: String) {
        val title = if (kind == "privacy") "Privacy Policy" else "Terms of Service"
        val body = if (kind == "privacy") {
            "BolPilot uses Google sign-in to identify your account. Voice commands are processed by Android's speech recognition service, which may send audio to its provider. Command history and preferences are stored locally on this device. This demo does not process payments. Before public release, publish a complete policy describing data retention, deletion, analytics and support contact details."
        } else {
            "Use BolPilot only on devices and accounts you are authorized to control. Voice commands can open apps and may lock the screen after you grant Android device-administrator permission. BolPilot does not bypass your PIN, password or biometrics. Payment and subscription features are currently demo-only and do not create a paid plan."
        }
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("Close", null).show()
    }

    private fun premiumPage(): View {
        val col = pageColumn()
        col.addView(text("BolPilot Premium", 27f, white, true))
        col.addView(text("Monthly membership · secure checkout coming next", 14f, muted).apply { setPadding(0, dp(4), 0, dp(18)) })
        val c = cardColumn()
        c.addView(text("✦", 38f, cyan, true).apply { gravity = Gravity.CENTER })
        c.addView(text("Your voice, upgraded", 19f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(6)) })
        c.addView(text("Premium plans must be configured in Google Play Console before real prices, trials, and purchases can be shown. No fake purchase or trial is enabled in this build.", 14f, muted).apply { gravity = Gravity.CENTER })
        col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        col.addView(button("Try demo checkout (no payment)", 0xFFE7F0FF.toInt(), cyan) {
            AlertDialog.Builder(this)
                .setTitle("Payment integration pending")
                .setMessage("No payment is taken here. Before enabling the ₹1 trial, configure Cashfree credentials as server-side secrets, create subscriptions from a trusted backend, verify signed webhooks, and check subscription status from the backend.")
                .setPositiveButton("Got it", null)
                .show()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        listOf("More voice workflows", "Preference sync across devices", "Premium command packs").forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(panel, 14); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            row.addView(text("✓", 18f, cyan, true), LinearLayout.LayoutParams(dp(28), -2))
            row.addView(text(item, 14f, white))
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        col.addView(button("Back to home", panel2, cyan) { showPage("home") }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        return wrapScroll(col)
    }

    private fun helpPage(): View {
        val col = pageColumn()
        col.addView(text("Help & troubleshooting", 25f, white, true))
        val entries = listOf(
            "Voice recognition doesn't start" to "Allow Microphone permission in Android Settings. Make sure the device has a speech recognition service installed.",
            "The app doesn't open the requested app" to "Check that the app is installed and that its package is available on this Android device.",
            "Google sign-in isn't configured" to "Enable Google in Firebase Authentication. Register SHA-1 and SHA-256 for the exact signing key used to build the installed APK, then download a fresh google-services.json. GitHub Actions debug APKs can use a different debug signing key than your laptop build.",
            "Background listening" to "Enable hands-free mode from Home while BolPilot is visible and grant microphone permission. Android keeps a foreground notification while listening. Availability depends on the device speech service, battery settings, and Android restrictions. Say app names or supported commands; this is not a fully offline wake-word engine.",
            "Lock phone by voice" to "Say lock phone. Android asks you to enable BolPilot as a device administrator before it can lock the screen. This does not bypass the PIN, password, or biometric unlock."
        )
        entries.forEach { (title, body) ->
            val c = cardColumn(); c.addView(text(title, 16f, white, true)); c.addView(text(body, 13f, muted).apply { setPadding(0, dp(7), 0, 0) })
            col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        return wrapScroll(col)
    }

    private fun toggleListening() {
        if (listening) { speechRecognizer?.cancel(); listening = false; updateStatus("Listening stopped"); showPage("home"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("Speech recognition isn't available on this device")
            speak("Speech recognition is not available on this device")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO); return
        }
        startListening()
    }

    private fun startListening() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { listening = true; updateStatus("Listening… speak now") }
                    override fun onBeginningOfSpeech() { updateStatus("I hear you…") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false; updateStatus("Processing your command…") }
                    override fun onError(error: Int) {
                        listening = false
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio error. Check microphone access."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network issue. Try again."
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the mic and try again."
                            else -> "Couldn't recognize that. Please try again."
                        }
                        updateStatus(message)
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (words.isNullOrBlank()) updateStatus("Didn't catch that. Try again.") else runCommand(words)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, responseLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, responseLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PROMPT, if (responseLanguage == "hi-IN") "BolPilot ko command dein" else "Speak to BolPilot")
            }
            speechRecognizer?.startListening(intent)
            updateStatus("Starting microphone…")
        } catch (e: Exception) {
            listening = false; updateStatus("Unable to start speech recognition: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun runCommand(raw: String) {
        val command = raw.trim()
        if (command.isBlank()) return
        lastRecognized = command
        val q = command.lowercase(Locale.ROOT)
        val result: String
        when {
            q.contains("lock phone") || q.contains("lock screen") || q.contains("screen lock") || q.contains("फोन लॉक") || q.contains("मोबाइल लॉक") || q.contains("स्क्रीन लॉक") -> {
                result = lockPhoneScreen()
                speak(if (responseLanguage == "hi-IN") "Screen lock ke liye device administrator permission zaroori hai. Prompt ko approve karein." else result)
            }
            q.contains("time") || q.contains("समय") || q.contains("टाइम") || q.contains("kitne baje") -> {
                val time = SimpleDateFormat("hh:mm a", if (responseLanguage == "hi-IN") Locale("hi", "IN") else Locale.US).format(Date())
                result = if (responseLanguage == "hi-IN") "Abhi samay $time hai" else "The time is $time"
                speak(result)
            }
            q.contains("date") || q.contains("तारीख") || q.contains("दिनांक") || q.contains("aaj ki date") -> {
                val date = SimpleDateFormat("EEEE, d MMMM yyyy", if (responseLanguage == "hi-IN") Locale("hi", "IN") else Locale.US).format(Date())
                result = if (responseLanguage == "hi-IN") "Aaj $date hai" else "Today is $date"
                speak(result)
            }
            q.contains("battery") || q.contains("बैटरी") -> {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                result = if (level >= 0) "Battery is at $level percent" else "Battery status isn't available"
                speak(if (responseLanguage == "hi-IN") "Battery $level percent hai" else result)
            }
            q.contains("call ") || q.startsWith("call") || q.contains("फोन लगाओ") || q.contains("कॉल करो") -> {
                val digits = command.filter { it.isDigit() || it == '+' }
                if (digits.length >= 5) {
                    result = if (confirmCalls) "Call request ready. Confirm in the dialog to open the dialer." else "Opening phone dialer"
                    if (confirmCalls) AlertDialog.Builder(this).setTitle("Confirm phone call")
                        .setMessage("Open the dialer with $digits? You can review and place the call yourself.")
                        .setNegativeButton("Cancel") { _, _ -> addHistory(command, "Call cancelled"); updateStatus("Call cancelled") }
                        .setPositiveButton("Open dialer") { _, _ -> openDialer(digits); addHistory(command, "Dialer opened"); updateStatus("Dialer opened") }.show()
                    else openDialer(digits)
                } else {
                    result = "Say a phone number, for example: call 9876543210"
                    speak(if (responseLanguage == "hi-IN") "Kripya phone number boliye" else result)
                }
            }
            q.startsWith("search google for ") || q.startsWith("google par search karo ") || q.startsWith("search for ") || q.startsWith("खोजो ") || q.startsWith("सर्च करो ") -> {
                val query = when {
                    q.startsWith("search google for ") -> command.substringAfter("search google for ", "")
                    q.startsWith("google par search karo ") -> command.substringAfter("google par search karo ", "")
                    q.startsWith("search for ") -> command.substringAfter("search for ", "")
                    q.startsWith("खोजो ") -> command.substringAfter("खोजो ", "")
                    else -> command.substringAfter("सर्च करो ", "")
                }.trim()
                if (query.isNotBlank()) {
                    result = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))); "Searching Google for $query" }
                    catch (_: Exception) { "Couldn't open Google Search" }
                } else result = "Say: search Google for weather"
                speak(if (responseLanguage == "hi-IN") "Google par search kar raha hoon" else result)
            }
            q.contains("gmail") || q.contains("जीमेल") -> { result = openPackage("com.google.android.gm", "Gmail") }
            q.contains("instagram") || q.contains("इंस्टाग्राम") -> { result = openPackage("com.instagram.android", "Instagram") }
            q.contains("telegram") || q.contains("टेलीग्राम") -> { result = openPackage("org.telegram.messenger", "Telegram") }
            q.contains("facebook") || q.contains("फेसबुक") -> { result = openPackage("com.facebook.katana", "Facebook") }
            q.contains("youtube") || q.contains("यूट्यूब") -> { result = openPackage("com.google.android.youtube", "YouTube") }
            q.contains("whatsapp") || q.contains("व्हाट्सएप") -> { result = openPackage("com.whatsapp", "WhatsApp") }
            q.contains("chrome") || q.contains("browser") || q.contains("ब्राउज़र") -> { result = openPackage("com.android.chrome", "Chrome") }
            q.contains("maps") || q.contains("map") || q.contains("मैप") -> { result = openPackage("com.google.android.apps.maps", "Maps") }
            q.contains("camera") || q.contains("कैमरा") -> { result = openCamera() }
            q.contains("wifi") || q.contains("wi-fi") || q.contains("वाईफाई") || q.contains("settings") || q.contains("सेटिंग") -> {
                result = try { startActivity(Intent(Settings.ACTION_SETTINGS)); "Opened Android Settings" } catch (_: Exception) { "Couldn't open Settings" }
            }
            q.contains("help") || q.contains("मदद") || q.contains("क्या कर सकते") -> {
                result = "Try: open YouTube, open WhatsApp, time, date, battery status, open settings, or call followed by a number."
                showPage("help"); speak(if (responseLanguage == "hi-IN") "Aap YouTube, WhatsApp, samay, tareekh, battery ya settings kholne ko keh sakte hain" else result)
            }
            else -> {
                result = "I couldn't match that command yet. Try one of the examples on Home."
                speak(if (responseLanguage == "hi-IN") "Maaf kijiye, ye command abhi samajh nahi aayi. Home par diye gaye examples try karein." else result)
            }
        }
        addHistory(command, result)
        updateStatus(result)
        if (currentPage == "home") showPage("home")
    }

    private fun startClapFinder() {
        if (clapFinderActive) {
            toast("Clap finder is already active")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            clapPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            toast("Clap finder is not supported by this device")
            return
        }
        try {
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, sampleRate / 2))
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                toast("Could not start microphone for clap finder")
                return
            }
            clapRecorder = recorder
            clapFinderActive = true
            recorder.startRecording()
            toast("Clap finder active for 30 seconds. Keep the app open; clap to trigger an alert.")
            clapThread = Thread {
                val buffer = ShortArray(maxOf(256, minBuffer / 2))
                val startedAt = System.currentTimeMillis()
                var lastAlertAt = 0L
                try {
                    while (clapFinderActive && System.currentTimeMillis() - startedAt < 30_000L) {
                        val count = recorder.read(buffer, 0, buffer.size)
                        if (count > 0) {
                            var sum = 0.0
                            for (i in 0 until count) {
                                val sample = buffer[i].toDouble()
                                sum += sample * sample
                            }
                            val rms = kotlin.math.sqrt(sum / count)
                            val now = System.currentTimeMillis()
                            if (rms > 8500 && now - lastAlertAt > 1800L) {
                                lastAlertAt = now
                                runOnUiThread {
                                    if (!isFinishing && clapFinderActive) {
                                        speak(if (responseLanguage == "hi-IN") "Phone mil gaya!" else "I heard a clap!")
                                        try {
                                            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                                                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                                            } else {
                                                @Suppress("DEPRECATION")
                                                getSystemService(VIBRATOR_SERVICE) as Vibrator
                                            }
                                            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 260), -1))
                                            else @Suppress("DEPRECATION") vibrator.vibrate(440)
                                        } catch (_: Exception) { }
                                        toast("Clap detected — BolPilot heard a loud sound")
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread { if (!isFinishing) toast("Clap finder stopped because microphone access ended") }
                } finally {
                    try { recorder.stop() } catch (_: Exception) { }
                    recorder.release()
                    if (clapRecorder === recorder) clapRecorder = null
                    clapFinderActive = false
                    runOnUiThread { if (!isFinishing) updateStatus("Clap finder finished. Tap “Find my phone” to try again.") }
                }
            }.apply { name = "BolPilot-ClapFinder"; start() }
        } catch (e: Exception) {
            clapFinderActive = false
            toast("Unable to start clap finder: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun stopClapFinder() {
        clapFinderActive = false
        try { clapRecorder?.stop() } catch (_: Exception) { }
        clapRecorder = null
    }

    private fun showVoiceLockWizard() {
        AlertDialog.Builder(this)
            .setTitle("Set up Voice Lock")
            .setMessage("Step 1 of 2: Android requires your explicit approval for BolPilot to lock this device. This feature cannot bypass your PIN, password or biometric.\n\nStep 2: Tap Continue to open Android's device-administrator approval screen. After approving it, return and say “lock phone”.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val policy = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, BolPilotDeviceAdminReceiver::class.java)
                if (policy.isAdminActive(admin)) {
                    AlertDialog.Builder(this).setTitle("Voice Lock is ready")
                        .setMessage("BolPilot already has Android's screen-lock permission. Say “lock phone” to use it.")
                        .setPositiveButton("OK", null).show()
                } else {
                    try {
                        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allow BolPilot to lock the screen only when you explicitly ask.")
                        })
                    } catch (e: Exception) {
                        toast("Could not open Android permission screen: ${e.localizedMessage ?: "unknown error"}")
                    }
                }
            }.show()
    }

    private fun lockPhoneScreen(): String {
        val policy = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, BolPilotDeviceAdminReceiver::class.java)
        if (!policy.isAdminActive(admin)) {
            return try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "BolPilot needs permission only to lock your screen when you explicitly ask. It cannot bypass your secure unlock method.")
                }
                startActivity(intent)
                "Approve BolPilot's device administrator permission, then say 'lock phone' again."
            } catch (e: Exception) { "Unable to open device administrator settings: " + (e.localizedMessage ?: "unknown error") }
        }
        return try { policy.lockNow(); "Locking the screen now" }
        catch (e: SecurityException) { "Screen lock permission isn't active. Enable BolPilot under device administrator settings." }
    }

    private fun openPackage(packageName: String, displayName: String): String {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        return if (launch != null) {
            try { startActivity(launch); val msg = "Opened $displayName"; speak(if (responseLanguage == "hi-IN") "$displayName khol raha hoon" else msg); msg }
            catch (_: Exception) { "Couldn't open $displayName" }
        } else {
            val msg = "$displayName doesn't appear to be installed on this device"
            speak(if (responseLanguage == "hi-IN") "$displayName install nahi hai" else msg); msg
        }
    }

    private fun openCamera(): String = try {
        startActivity(Intent("android.media.action.IMAGE_CAPTURE")); "Opened Camera"
    } catch (_: ActivityNotFoundException) { "No camera app found" }

    private fun openDialer(number: String) {
        try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))) }
        catch (_: Exception) { toast("No phone dialer available") }
    }

    private fun isGuestMode(): Boolean =
        getSharedPreferences("bolpilot_settings", MODE_PRIVATE).getBoolean("guest_mode", false)

    private fun beginGoogleSignIn() {
        if (FirebaseApp.getApps(this).isEmpty() || auth == null) {
            toast("Firebase isn't configured. Add your google-services.json first.")
            return
        }
        val clientIdRes = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (clientIdRes == 0) {
            toast("Web Client ID missing. Check Firebase Google sign-in setup.")
            return
        }
        try {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail().requestIdToken(getString(clientIdRes)).build()
            googleLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
        } catch (e: Exception) { toast("Unable to start Google sign-in: ${e.localizedMessage ?: "unknown error"}") }
    }

    private fun speak(message: String) {
        if (!ttsReady) return
        setTtsLanguage()
        @Suppress("DEPRECATION")
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "bolpilot-${System.currentTimeMillis()}")
    }

    private fun setTtsLanguage() {
        val locale = if (responseLanguage == "hi-IN") Locale("hi", "IN") else Locale.US
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }
    }

    private fun addHistory(command: String, result: String) {
        if (saveHistory) {
            history.add(0, HistoryItem(command, result, SimpleDateFormat("d MMM · hh:mm a", Locale.getDefault()).format(Date())))
            while (history.size > 50) history.removeAt(history.lastIndex)
            persistHistory()
        }
    }

    private fun loadSettings() {
        val p = getSharedPreferences("bolpilot_settings", MODE_PRIVATE)
        responseLanguage = p.getString("language", "hi-IN") ?: "hi-IN"
        confirmCalls = p.getBoolean("confirm_calls", true)
        saveHistory = p.getBoolean("save_history", true)
    }
    private fun saveSettings() {
        getSharedPreferences("bolpilot_settings", MODE_PRIVATE).edit()
            .putString("language", responseLanguage).putBoolean("confirm_calls", confirmCalls)
            .putBoolean("save_history", saveHistory).apply()
    }
    private fun loadHistory() {
        if (!saveHistory) return
        val raw = getSharedPreferences("bolpilot_history", MODE_PRIVATE).getString("items", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(HistoryItem(o.optString("command"), o.optString("result"), o.optString("time")))
            }
        } catch (_: Exception) { history.clear() }
    }
    private fun persistHistory() {
        val arr = JSONArray()
        history.take(50).forEach { arr.put(JSONObject().put("command", it.command).put("result", it.result).put("time", it.time)) }
        getSharedPreferences("bolpilot_history", MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
        if (::lastCommandText.isInitialized) lastCommandText.text = history.firstOrNull()?.let { "“${it.command}”\n${it.result} · ${it.time}" } ?: "Your recent commands will appear here."
    }

    private fun updateStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
        if (::lastCommandText.isInitialized && history.isEmpty()) lastCommandText.text = message
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun pageColumn() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(18)) }
    private fun wrapScroll(view: View): View = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(view) }
    private fun cardColumn() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF7F9FC.toInt())).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFFDCE3EC.toInt()) }; setPadding(dp(16), dp(16), dp(16), dp(16)) }
    private fun sectionTitle(s: String) = text(s, 11f, cyan, true).apply { letterSpacing = .14f; setPadding(dp(2), dp(8), 0, dp(10)) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = true
    }
    private fun button(label: String, bg: Int, fg: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(bg, panel2)).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0xFFD5DFEA.toInt()) }; setPadding(dp(10), dp(8), dp(10), dp(8)); isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }
    private fun actionRow(title: String, subtitle: String, actionLabel: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(panel, 16); setPadding(dp(14), dp(13), dp(12), dp(13)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(title, 15f, white, true)); left.addView(text(subtitle, 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(button(actionLabel, panel2, cyan, action), LinearLayout.LayoutParams(dp(66), dp(42)))
        return row.apply { val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(9); layoutParams = lp }
    }
    private fun settingSwitch(title: String, subtitle: String, checked: Boolean, changed: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(panel, 16); setPadding(dp(14), dp(12), dp(12), dp(12)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(title, 15f, white, true)); left.addView(text(subtitle, 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply { isChecked = checked; buttonTintList = android.content.res.ColorStateList.valueOf(cyan); setOnCheckedChangeListener { _, value -> changed(value) } }
        row.addView(sw)
        return row.apply { val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(9); layoutParams = lp }
    }
    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onDestroy() {
        speechRecognizer?.destroy(); speechRecognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }
}

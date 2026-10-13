package in.examdarpan.vaanix

import android.Manifest
import android.app.AlertDialog
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
    private val navy = Color.rgb(3, 13, 32)
    private val panel = Color.rgb(8, 27, 59)
    private val panel2 = Color.rgb(11, 39, 79)
    private val cyan = Color.rgb(8, 217, 245)
    private val white = Color.rgb(246, 249, 255)
    private val muted = Color.rgb(171, 187, 215)
    private val red = Color.rgb(255, 105, 122)

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
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
    private var tts: TextToSpeech? = null
    private var auth: FirebaseAuth? = null
    private var signedInName: String? = null
    private val history = mutableListOf<HistoryItem>()

    private data class HistoryItem(val command: String, val result: String, val time: String)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else updateStatus("Microphone permission is needed to hear commands")
    }
    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            toast("Google sign-in cancelled")
            return@registerForActivityResult
        }
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth?.signInWithCredential(credential)?.addOnCompleteListener { taskResult ->
                if (taskResult.isSuccessful) {
                    signedInName = auth?.currentUser?.displayName ?: account.displayName ?: account.email
                    toast("Signed in successfully")
                    showPage("profile")
                } else toast("Firebase sign-in failed: ${taskResult.exception?.localizedMessage ?: "Unknown error"}")
            } ?: toast("Firebase is not configured yet")
        } catch (e: Exception) {
            toast("Google sign-in failed: ${e.localizedMessage ?: "Please check Firebase setup"}")
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
        showPage("home")
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
        brandColumn.addView(text("BolPilot", 29f, white, true))
        brandColumn.addView(text("YOUR VOICE, SMARTER PHONE", 10f, cyan, true).apply { letterSpacing = .12f })
        header.addView(brandColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val profile = button("●", panel2, white) { showPage("profile") }
        profile.textSize = 20f
        header.addView(profile, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(panel2, 32)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        nav.addView(navButton("⌂\nHome", "home"), LinearLayout.LayoutParams(0, dp(58), 1f))
        nav.addView(navButton("◷\nHistory", "history"), LinearLayout.LayoutParams(0, dp(58), 1f))
        nav.addView(navButton("⚙\nSettings", "settings"), LinearLayout.LayoutParams(0, dp(58), 1f))
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(68)))
        setContentView(root)
    }

    private fun navButton(label: String, page: String): TextView = text(label, 13f, if (currentPage == page) cyan else muted, true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { showPage(page) }
        isClickable = true
        isFocusable = true
    }

    private fun showPage(page: String) {
        currentPage = page
        if (!::content.isInitialized) return
        content.removeAllViews()
        val pageView = when (page) {
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
        val hello = text(if (signedInName != null) "Welcome back, ${signedInName!!.substringBefore(' ')}" else "Your phone, hands-free", 22f, white, true)
        col.addView(hello)
        col.addView(text("Tap the mic and tell BolPilot what to do.", 14f, muted).apply { setPadding(0, dp(5), 0, dp(14)) })

        val micCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = rounded(panel, 28)
            setPadding(dp(16), dp(22), dp(16), dp(22))
        }
        val mic = TextView(this).apply {
            text = if (listening) "Ⅱ" else "🎙"
            textSize = 38f; gravity = Gravity.CENTER; setTextColor(white)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0757A8.toInt(), 0xFF06234B.toInt())).apply {
                shape = GradientDrawable.OVAL; setStroke(dp(2), cyan)
            }
            elevation = dp(8)
            contentDescription = "Start voice command"
            setOnClickListener { toggleListening() }
        }
        micCard.addView(mic, LinearLayout.LayoutParams(dp(118), dp(118)))
        val status = text(if (listening) "LISTENING…" else "READY WHEN YOU ARE", 13f, cyan, true).apply {
            gravity = Gravity.CENTER; letterSpacing = .12f; setPadding(0, dp(18), 0, dp(7))
        }
        micCard.addView(status)
        statusText = text(if (listening) "Speak now" else "Press the microphone to start", 14f, muted).apply { gravity = Gravity.CENTER }
        micCard.addView(statusText)
        col.addView(micCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        col.addView(sectionTitle("TRY SAYING"))
        val chips = listOf("Open YouTube", "What time is it?", "Open WhatsApp", "Today's date", "Open settings", "Battery status")
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

        col.addView(sectionTitle("LATEST ACTIVITY"))
        val activityCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 20); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        lastCommandText = text(history.firstOrNull()?.let { "“${it.command}”\n${it.result} · ${it.time}" } ?: "Your recent commands will appear here after you try the mic.", 14f, muted)
        activityCard.addView(lastCommandText)
        col.addView(activityCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(16) })

        val premium = button("Explore BolPilot Premium  →", 0xFF063A6C.toInt(), cyan) { showPage("premium") }
        premium.textSize = 15f
        col.addView(premium, LinearLayout.LayoutParams(-1, dp(54)))
        scroll.addView(col)
        return scroll
    }

    private fun historyPage(): View {
        val col = pageColumn()
        col.addView(text("Command history", 25f, white, true))
        col.addView(text("Commands processed on this device", 14f, muted).apply { setPadding(0, dp(4), 0, dp(16)) })
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
        col.addView(text("Settings", 27f, white, true))
        col.addView(text("Make BolPilot work your way", 14f, muted).apply { setPadding(0, dp(4), 0, dp(18)) })
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
        col.addView(text("Your account", 27f, white, true))
        col.addView(text("Keep your experience connected", 14f, muted).apply { setPadding(0, dp(4), 0, dp(18)) })
        val card = cardColumn()
        card.addView(text("◉", 42f, cyan, true).apply { gravity = Gravity.CENTER })
        card.addView(text(signedInName ?: "You're using BolPilot as a guest", 17f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(5)) })
        card.addView(text(if (auth?.currentUser != null) "Firebase account connected" else "Local mode · sign-in not connected", 13f, muted).apply { gravity = Gravity.CENTER })
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        if (auth?.currentUser == null) {
            col.addView(button("Continue with Google", white, navy) { beginGoogleSignIn() }, LinearLayout.LayoutParams(-1, dp(54)))
            col.addView(text("To enable Google sign-in, add your own Firebase google-services.json and configure the Web Client ID. This project does not contain your private Firebase configuration.", 12f, muted).apply { setPadding(dp(4), dp(12), dp(4), dp(12)) })
        } else {
            col.addView(button("Sign out", 0xFF3B1B34.toInt(), red) {
                auth?.signOut(); GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
                signedInName = null; showPage("profile")
            }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        col.addView(button("Premium plans", panel2, cyan) { showPage("premium") }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        return wrapScroll(col)
    }

    private fun premiumPage(): View {
        val col = pageColumn()
        col.addView(text("BolPilot Premium", 27f, white, true))
        col.addView(text("A simple place for future premium features", 14f, muted).apply { setPadding(0, dp(4), 0, dp(18)) })
        val c = cardColumn()
        c.addView(text("✦", 38f, cyan, true).apply { gravity = Gravity.CENTER })
        c.addView(text("Your voice, upgraded", 19f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(6)) })
        c.addView(text("Premium plans must be configured in Google Play Console before real prices, trials, and purchases can be shown. No fake purchase or trial is enabled in this build.", 14f, muted).apply { gravity = Gravity.CENTER })
        col.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
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
            "Google sign-in isn't configured" to "Add the google-services.json for application ID in.examdarpan.vaanix, enable Google in Firebase Authentication, and ensure the Web OAuth client is generated.",
            "Background listening" to "This starter intentionally uses tap-to-talk. Always-on hotword detection is not enabled; Android background microphone restrictions and battery use need a separate implementation."
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
    private fun cardColumn() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 20); setPadding(dp(16), dp(16), dp(16), dp(16)) }
    private fun sectionTitle(s: String) = text(s, 11f, cyan, true).apply { letterSpacing = .14f; setPadding(dp(2), dp(8), 0, dp(10)) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = true
    }
    private fun button(label: String, bg: Int, fg: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
        background = rounded(bg, 18); setPadding(dp(10), dp(8), dp(10), dp(8)); isClickable = true; isFocusable = true
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

package `in`.examdarpan.vaanix

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class MainActivity : AppCompatActivity() {
    private val ink = Color.rgb(25, 32, 45)
    private val muted = Color.rgb(105, 114, 130)
    private val purple = Color.rgb(91, 76, 235)
    private val canvas = Color.rgb(247, 248, 252)
    private lateinit var root: LinearLayout
    private var page = "welcome"
    private var signedIn = false
    private val auth by lazy { FirebaseAuth.getInstance() }

    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "Google sign-in cancelled. Check Google/Firebase OAuth setup if it keeps failing.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                Toast.makeText(this, "Google ID token missing. Add the Web client ID in Firebase/Google Services.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .addOnSuccessListener {
                    signedIn = true
                    page = "home"
                    render()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Firebase sign-in failed: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
                }
        } catch (e: ApiException) {
            Toast.makeText(this, "Google sign-in error code: ${e.statusCode}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = canvas
        window.navigationBarColor = Color.WHITE
        if (Build.VERSION.SDK_INT >= 23) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        signedIn = auth.currentUser != null
        page = if (signedIn) "home" else "welcome"
        render()
    }

    private fun render() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        when (page) {
            "welcome" -> welcomePage()
            "signin" -> signInPage()
            "home" -> homePage()
            "clap" -> clapPage()
            "lock" -> lockPage()
            "permissions" -> permissionsPage()
        }
    }

    private fun welcomePage() {
        header("BolPilot", "Your voice. Your phone. Simplified.")
        val hero = card()
        hero.addView(label("Speak naturally. Get things done.", 26, ink, true))
        hero.addView(space(10))
        hero.addView(label("Open apps, search the web and use voice features with clear controls.", 15, muted))
        hero.addView(space(22))
        hero.addView(pill("3-day free trial", Color.rgb(235, 232, 255), purple))
        hero.addView(space(18))
        hero.addView(action("Continue with Google", purple) { page = "signin"; render() })
        hero.addView(space(10))
        hero.addView(action("Explore features", Color.WHITE, ink, true) { page = "home"; render() })
        root.addView(hero)
        root.addView(space(14))
        root.addView(label("Simple monthly plan", 18, ink, true))
        root.addView(space(8))
        val plan = card()
        plan.addView(label("₹1 introductory payment", 18, ink, true))
        plan.addView(label("Final price and renewal terms must be shown before payment. Payments are not activated in this demo screen.", 13, muted))
        root.addView(plan)
    }

    private fun signInPage() {
        header("Sign in", "Secure access with your Google account")
        val c = card()
        c.addView(label("Connect your account", 22, ink, true))
        c.addView(space(8))
        c.addView(label("Google sign-in uses the configured OAuth Web client ID and Firebase Authentication.", 14, muted))
        c.addView(space(20))
        c.addView(action("Continue with Google", purple) { startGoogleSignIn() })
        c.addView(space(10))
        c.addView(action("Back", Color.WHITE, ink, true) { page = "welcome"; render() })
        root.addView(c)
    }

    private fun startGoogleSignIn() {
        val id = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (id == 0) {
            Toast.makeText(this, "Firebase setup needed: add your valid google-services.json and enable Google provider in Firebase Authentication.", Toast.LENGTH_LONG).show()
            return
        }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(getString(id))
            .build()
        googleLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }

    private fun homePage() {
        header("Good to see you", if (signedIn) auth.currentUser?.email ?: "You're signed in" else "Your personal voice assistant")
        val welcome = card()
        welcome.addView(label("Make your phone work hands-free", 23, ink, true))
        welcome.addView(space(8))
        welcome.addView(label("Turn on only the features you need. Listening shows a permanent Android notification and can be stopped anytime.", 14, muted))
        welcome.addView(space(16))
        welcome.addView(action("Start voice assistant", purple) { requestMicAndStart() })
        root.addView(welcome)
        root.addView(space(12))
        featureCard("👏", "Clap to Find", "Choose how the phone responds when you clap.", "Open settings") { page = "clap"; render() }
        featureCard("🔊", "Voice assistant", "Start or stop visible voice listening.", "Manage") { page = "permissions"; render() }
        featureCard("🔒", "Voice Lock setup", "Set up an in-app voice phrase. This does not replace Android's secure lock screen.", "Set up") { page = "lock"; render() }
        root.addView(space(12))
        if (signedIn) root.addView(action("Sign out", Color.WHITE, ink, true) {
            auth.signOut()
            GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()
            signedIn = false; page = "welcome"; render()
        }) else root.addView(action("Sign in to sync settings", Color.WHITE, ink, true) { page = "signin"; render() })
    }

    private fun clapPage() {
        header("Clap to Find", "Configure the feature")
        val c = card()
        c.addView(label("Clap detection", 20, ink, true))
        c.addView(label("Clap-to-find needs microphone access and may use extra battery. This starter UI does not run hidden background detection.", 14, muted))
        c.addView(space(18))
        c.addView(action("Allow microphone", purple) { requestMicAndStart() })
        c.addView(space(10))
        c.addView(action("Back to home", Color.WHITE, ink, true) { page = "home"; render() })
        root.addView(c)
    }

    private fun lockPage() {
        header("Voice Lock", "Set up an in-app phrase")
        val c = card()
        c.addView(label("Use a phrase to open BolPilot", 20, ink, true))
        c.addView(space(8))
        c.addView(label("For your safety, voice matching is not a secure identity check. This feature cannot unlock Android's system lock screen or replace your PIN, password or biometrics.", 14, muted))
        c.addView(space(16))
        c.addView(action("Continue to permissions", purple) { page = "permissions"; render() })
        c.addView(space(10))
        c.addView(action("Back to home", Color.WHITE, ink, true) { page = "home"; render() })
        root.addView(c)
    }

    private fun permissionsPage() {
        header("Permissions", "You stay in control")
        val c = card()
        listOf("Microphone — required for voice commands", "Notifications — shows listening status", "Battery settings — optional; Android may still stop background work").forEach {
            c.addView(label("✓  $it", 15, ink))
            c.addView(space(12))
        }
        c.addView(action("Grant microphone & start", purple) { requestMicAndStart() })
        c.addView(space(10))
        c.addView(action("Stop voice assistant", Color.WHITE, ink, true) {
            stopService(Intent(this, VoiceAssistantService::class.java))
            Toast.makeText(this, "Voice assistant stopped", Toast.LENGTH_SHORT).show()
        })
        c.addView(space(10))
        c.addView(action("Back to home", Color.WHITE, ink, true) { page = "home"; render() })
        root.addView(c)
    }

    private fun requestMicAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 400)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 401)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_START))
        Toast.makeText(this, "Voice assistant started. Use its notification to stop.", Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestMicAndStart()
        else Toast.makeText(this, "Permission is needed for this feature.", Toast.LENGTH_SHORT).show()
    }

    private fun header(title: String, subtitle: String) {
        root.addView(label(title, 28, ink, true))
        root.addView(space(5))
        root.addView(label(subtitle, 14, muted))
        root.addView(space(20))
    }

    private fun featureCard(emoji: String, title: String, description: String, button: String, click: () -> Unit) {
        val c = card()
        c.orientation = LinearLayout.HORIZONTAL
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        content.addView(label("$emoji  $title", 17, ink, true))
        content.addView(space(5))
        content.addView(label(description, 13, muted))
        c.addView(content)
        val b = TextView(this).apply {
            text = button; textSize = 12f; setTextColor(purple); gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { click() }
        }
        c.addView(b)
        root.addView(c); root.addView(space(10))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(22).toFloat() }
        elevation = dp(2).toFloat()
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(2); layoutParams = lp
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun pill(text: String, bg: Int, fg: Int) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(fg); setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(30).toFloat() }
    }

    private fun action(text: String, bg: Int, fg: Int, outlined: Boolean = false, click: () -> Unit): TextView = TextView(this).apply {
        this.text = text; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(fg); setPadding(dp(16), dp(15), dp(16), dp(15))
        background = GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(15).toFloat()
            if (outlined) setStroke(dp(1), Color.rgb(225, 228, 236))
        }
        isClickable = true; isFocusable = true; setOnClickListener { click() }
    }

    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
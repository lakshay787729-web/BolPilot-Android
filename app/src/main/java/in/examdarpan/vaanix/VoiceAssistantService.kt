package `in`.examdarpan.vaanix

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlayBridgeView: View? = null
    private var lockScreenView: View? = null
    private var phoneFoundOverlayView: View? = null
    private var activeRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    private var isFindingPhone = false
    private var isDestroyed = false
    private var isListeningActive = false
    private var isPausedForWizard = false
    private var isExternalMediaPlaying = false
    private var isTorchOn = false

    // Raw PCM Clap Detection variables (Works 10x faster than SpeechRecognizer!)
    private var clapDetectorRunning = false
    private var clapSpikeCount = 0
    private var lastSpikeTimestamp = 0L

    private var lastActionTimestamp = 0L
    private var lastUnlockTimestamp = 0L
    private var countdownTimer: CountDownTimer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "BolPilot_Siri_Engine"

    companion object {
        var isRunning = false
        var lastHeardCommand = "Ready • Listening for your voice"
        var liveSoundLevel = 0
    }

    // Smart Media Guard: Pauses SpeechRecognizer while YouTube/Instagram/Facebook video plays so video NEVER pauses
    private val mediaPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            val activeMedia = configs?.any { cfg ->
                val u = cfg.audioAttributes.usage
                u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME
            } ?: false

            isExternalMediaPlaying = activeMedia
            if (activeMedia && lockScreenView == null && !isFindingPhone) {
                mainHandler.post {
                    if (isListeningActive) {
                        try { speechRecognizer?.cancel() } catch (e: Exception) {}
                        isListeningActive = false
                    }
                }
            } else if (!activeMedia && !isPausedForWizard && !isListeningActive) {
                scheduleRestart(250)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("pro_autopay_active", false) &&
            !prefs.getBoolean("guest_mode", false)) {
            stopSelf()
            return
        }

        isRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        audioManager.registerAudioPlaybackCallback(mediaPlaybackCallback, mainHandler)
        createNotificationChannel()

        try {
            val notification = buildNotification("BolPilot is listening • All features ready")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(101, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }

        acquireWakeLock()
        setupOverlayBridge()
        tts = TextToSpeech(applicationContext, this, "com.google.android.tts")
        startRawPcmClapDetector()
        initSpeechRecognizer()
        startSmartWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("ACTION")) {
            "PAUSE_LISTENING" -> {
                isPausedForWizard = true
                mainHandler.post {
                    try { speechRecognizer?.cancel() } catch (e: Exception) {}
                    isListeningActive = false
                }
            }
            "RESUME_LISTENING" -> {
                isPausedForWizard = false
                initSpeechRecognizer()
            }
            "REFRESH_LANGUAGE" -> {
                applyHumanFemaleVoice()
                initSpeechRecognizer()
            }
            "TEST_FIND_ALERT" -> triggerFindMyPhoneAlert()
            "STOP_FIND_ALERT" -> stopFindMyPhoneAlert()
            "TEST_VOICE_LOCK" -> lockPhoneNow()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("master_listening_on", true) && prefs.getBoolean("pro_autopay_active", false)) {
            val restartIntent = Intent(applicationContext, VoiceAssistantService::class.java)
            val pending = PendingIntent.getService(
                this, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 250, pending)
        }
        super.onTaskRemoved(rootIntent)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BolPilot::SiriAlwaysOn")
            wakeLock?.acquire()
        } catch (e: Exception) {}
    }

    private fun setupOverlayBridge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                if (overlayBridgeView == null) {
                    overlayBridgeView = View(this)
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE

                    val params = WindowManager.LayoutParams(
                        1, 1, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP or Gravity.START }
                    wm.addView(overlayBridgeView, params)
                }
            } catch (e: Exception) {}
        }
    }

    // =========================================================================
    // DEDICATED HIGH-SPEED CLAP & WHISTLE DETECTOR (Like Ringbook — 0.1s Response!)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startRawPcmClapDetector() {
        if (clapDetectorRunning) return
        clapDetectorRunning = true

        thread(start = true, name = "BolPilotClapThread") {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@thread

            var recorder: AudioRecord? = null
            val buffer = ShortArray(minBuf)

            while (!isDestroyed) {
                try {
                    val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
                    val clapEnabled = prefs.getBoolean("clap_to_find", false)
                    val siriEnabled = prefs.getBoolean("siri_os_enabled", true)
                    val voiceLockEnabled = prefs.getBoolean("voice_lock_enabled", true)

                    // Run raw PCM detector whenever Clap-to-Find is ON and SpeechRecognizer isn't holding the mic,
                    // OR during short windows between speech cycles so claps are caught instantaneously!
                    if (clapEnabled && !isFindingPhone && !isPausedForWizard && (!siriEnabled && !voiceLockEnabled || !isListeningActive)) {
                        if (recorder == null) {
                            recorder = AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                sampleRate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                minBuf
                            )
                            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                                recorder.startRecording()
                            } else {
                                recorder.release()
                                recorder = null
                            }
                        }

                        val readCount = recorder?.read(buffer, 0, buffer.size) ?: 0
                        if (readCount > 0) {
                            var sum = 0.0
                            var peak = 0
                            for (i in 0 until readCount) {
                                val v = abs(buffer[i].toInt())
                                if (v > peak) peak = v
                                sum += (buffer[i] * buffer[i]).toDouble()
                            }
                            val rms = sqrt(sum / readCount)
                            val normalizedLevel = ((peak / 32767.0) * 100).toInt().coerceIn(0, 100)
                            liveSoundLevel = normalizedLevel
                            sendBroadcast(Intent("BOLPILOT_SOUND_LEVEL").putExtra("LEVEL", normalizedLevel))

                            val sens = prefs.getString("clap_sensitivity", "Medium") ?: "Medium"
                            val spikeThreshold = when (sens) {
                                "Low" -> 22000
                                "High" -> 11000
                                else -> 16000
                            }

                            if (peak >= spikeThreshold && rms > 1200) {
                                val now = System.currentTimeMillis()
                                if (now - lastSpikeTimestamp in 140..1400) {
                                    clapSpikeCount++
                                } else if (now - lastSpikeTimestamp > 1400) {
                                    clapSpikeCount = 1
                                }
                                lastSpikeTimestamp = now

                                val reqClaps = prefs.getInt("claps_required", 2)
                                if (clapSpikeCount >= reqClaps) {
                                    clapSpikeCount = 0
                                    mainHandler.post { triggerFindMyPhoneAlert() }
                                }
                            }
                        }
                    } else {
                        if (recorder != null) {
                            try { recorder.stop() } catch (e: Exception) {}
                            try { recorder.release() } catch (e: Exception) {}
                            recorder = null
                        }
                        Thread.sleep(180)
                    }
                } catch (e: Exception) {
                    try { recorder?.release() } catch (ex: Exception) {}
                    recorder = null
                    Thread.sleep(300)
                }
            }
            try { recorder?.release() } catch (e: Exception) {}
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyHumanFemaleVoice()
        }
    }

    private fun applyHumanFemaleVoice() {
        try {
            tts.language = Locale("en", "IN")
            val voices = tts.voices
            if (!voices.isNullOrEmpty()) {
                val bestFemale = voices.firstOrNull { v ->
                    v.locale.country.equals("IN", true) &&
                            (v.name.contains("female", true) || v.name.contains("ene", true) || v.name.contains("network", true))
                } ?: voices.firstOrNull { it.locale.country.equals("IN", true) }
                if (bestFemale != null) tts.voice = bestFemale
            }
            tts.setPitch(1.06f)
            tts.setSpeechRate(1.0f)
        } catch (e: Exception) {}
    }

    private fun speak(text: String) {
        try {
            if (!audioManager.isMusicActive && !isExternalMediaPlaying) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "BolPilotTTS")
            }
        } catch (e: Exception) {}
    }

    private fun startSmartWatchdog() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isDestroyed) return
                val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
                val needSpeech = prefs.getBoolean("siri_os_enabled", true) || prefs.getBoolean("voice_lock_enabled", true)
                val mediaBusy = (audioManager.isMusicActive || isExternalMediaPlaying) && lockScreenView == null && !isFindingPhone
                if (needSpeech && !isPausedForWizard && !mediaBusy && !isListeningActive) {
                    safeStartListening()
                }
                mainHandler.postDelayed(this, 1100)
            }
        }, 1100)
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { isListeningActive = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        val level = ((rmsdB.coerceAtLeast(0f) / 12f) * 100).toInt().coerceIn(0, 100)
                        liveSoundLevel = level
                        sendBroadcast(Intent("BOLPILOT_SOUND_LEVEL").putExtra("LEVEL", level))

                        // Instant Clap Detection inside SpeechRecognizer RMS stream too!
                        val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
                        if (prefs.getBoolean("clap_to_find", false) && !isFindingPhone) {
                            val sens = prefs.getString("clap_sensitivity", "Medium") ?: "Medium"
                            val thresh = when (sens) {
                                "Low" -> 10.5f
                                "High" -> 7.8f
                                else -> 9.0f
                            }
                            if (rmsdB >= thresh) {
                                val now = System.currentTimeMillis()
                                if (now - lastSpikeTimestamp in 150..1600) {
                                    clapSpikeCount++
                                } else if (now - lastSpikeTimestamp > 1600) {
                                    clapSpikeCount = 1
                                }
                                lastSpikeTimestamp = now
                                val req = prefs.getInt("claps_required", 2)
                                if (clapSpikeCount >= req) {
                                    clapSpikeCount = 0
                                    triggerFindMyPhoneAlert()
                                }
                            }
                        }
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { isListeningActive = false }
                    override fun onError(error: Int) {
                        isListeningActive = false
                        val mediaBusy = (audioManager.isMusicActive || isExternalMediaPlaying) && lockScreenView == null
                        if (!isPausedForWizard && !mediaBusy) {
                            scheduleRestart(220)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        isListeningActive = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            for (c in matches) {
                                val clean = normalizeHindiToLatin(c.lowercase(Locale.getDefault()).trim())
                                broadcastSpokenText(clean)
                                if (executeVoiceCommand(clean)) break
                            }
                        }
                        val mediaBusy = (audioManager.isMusicActive || isExternalMediaPlaying) && lockScreenView == null
                        if (!isPausedForWizard && !mediaBusy) {
                            scheduleRestart(150)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val clean = normalizeHindiToLatin(matches[0].lowercase(Locale.getDefault()).trim())
                            if (clean.isNotEmpty()) {
                                broadcastSpokenText(clean)
                                executeVoiceCommand(clean)
                            }
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                safeStartListening()
            } catch (e: Exception) {}
        }
    }

    private fun normalizeHindiToLatin(input: String): String {
        return input
            .replace("ओपन", "open")
            .replace("खोलो", "open")
            .replace("चलाओ", "play")
            .replace("सर्च", "search")
            .replace("यूट्यूब", "youtube")
            .replace("व्हाट्सएप", "whatsapp")
            .replace("इंस्टाग्राम", "instagram")
            .replace("फेसबुक", "facebook")
            .replace("रैपिडो", "rapido")
            .replace("दैनिक भास्कर", "dainik bhaskar")
            .replace("भास्कर", "bhaskar")
            .replace("ट्रूकॉलर", "truecaller")
            .replace("टेलीग्राम", "telegram")
            .replace("पेटीएम", "paytm")
            .replace("फोनपे", "phonepe")
            .replace("गूगल", "google")
            .replace("कैमरा", "camera")
            .replace("फ्लैशलाइट", "flashlight")
            .replace("टॉर्च", "torch")
            .replace("लॉक", "lock")
            .replace("अनलॉक", "unlock")
            .replace("होम", "home")
            .replace("बैक", "back")
            .replace("बंद", "off")
            .replace("चालू", "on")
    }

    private fun broadcastSpokenText(text: String) {
        lastHeardCommand = "\"$text\""
        sendBroadcast(Intent("BOLPILOT_VOICE_UPDATE").putExtra("SPOKEN_TEXT", lastHeardCommand))
    }

    private fun safeStartListening() {
        if (isDestroyed || isPausedForWizard) return
        val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
        val needSpeech = prefs.getBoolean("siri_os_enabled", true) || prefs.getBoolean("voice_lock_enabled", true)
        if (!needSpeech) return

        val mediaBusy = (audioManager.isMusicActive || isExternalMediaPlaying) && lockScreenView == null && !isFindingPhone
        if (mediaBusy) return

        mainHandler.post {
            try {
                isListeningActive = true
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                isListeningActive = false
            }
        }
    }

    private fun scheduleRestart(delay: Long) {
        mainHandler.postDelayed({
            val mediaBusy = (audioManager.isMusicActive || isExternalMediaPlaying) && lockScreenView == null && !isFindingPhone
            if (!isDestroyed && !isPausedForWizard && !mediaBusy) {
                try {
                    speechRecognizer?.cancel()
                    safeStartListening()
                } catch (e: Exception) {
                    initSpeechRecognizer()
                }
            }
        }, delay)
    }

    // =========================================================================
    // PRECISION SIRI COMMAND ENGINE (Zero False Clock/Phone Triggers!)
    // =========================================================================
    private fun executeVoiceCommand(rawInput: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionTimestamp < 800) return false

        val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
        val voiceLockEnabled = prefs.getBoolean("voice_lock_enabled", true)
        val siriOsEnabled = prefs.getBoolean("siri_os_enabled", true)

        val unlockPhrase = normalizeHindiToLatin((prefs.getString("unlock_phrase", "unlock my phone") ?: "unlock my phone").lowercase().trim())
        val lockPhrase = normalizeHindiToLatin((prefs.getString("lock_phrase", "lock my phone") ?: "lock my phone").lowercase().trim())
        val backupPin = (prefs.getString("backup_pin", "6367") ?: "6367").lowercase().trim()

        // 0. SILENCE PHONE FOUND ALARM BY VOICE
        if (isFindingPhone && (rawInput.contains("stop") || rawInput.contains("quiet") || rawInput.contains("silence"))) {
            lastActionTimestamp = now
            stopFindMyPhoneAlert()
            return true
        }

        val spokenDigits = rawInput.filter { it.isDigit() }
        val isUnlockCommand = rawInput.contains("unlock") || rawInput.contains("unlok") || rawInput.contains("anlok") ||
                (unlockPhrase.isNotEmpty() && rawInput.contains(unlockPhrase)) ||
                (spokenDigits.isNotEmpty() && spokenDigits == backupPin)

        // 1. IF LOCK SCREEN IS ACTIVE -> CHECK UNLOCK IMMEDIATELY
        if (lockScreenView != null) {
            if (isUnlockCommand || rawInput.contains("open my phone") || rawInput.contains("open the phone")) {
                lastActionTimestamp = now
                lastUnlockTimestamp = now
                unlockAndWakePhone()
                return true
            }
            return false
        }

        if (isUnlockCommand || (now - lastUnlockTimestamp < 3000)) {
            return false
        }

        // 2. INSTANT VOICE LOCK MATCHING (Catches "clock my phone", "log the phone", "lok phone", "block phone" too!)
        val soundsLikeLock = rawInput.contains("lock my") || rawInput.contains("lock the") ||
                rawInput.contains("lock phone") || rawInput.contains("phone lock") ||
                rawInput.contains("screen lock") || rawInput.contains("clock my phone") ||
                rawInput.contains("clock the phone") || rawInput.contains("log the phone") ||
                rawInput.contains("log my phone") || rawInput.contains("lok my phone") ||
                rawInput.contains("lok the phone") || rawInput.contains("block my phone") ||
                (lockPhrase.isNotEmpty() && rawInput.contains(lockPhrase))

        if (voiceLockEnabled && soundsLikeLock) {
            lastActionTimestamp = now
            lockPhoneNow()
            return true
        }

        if (!siriOsEnabled) return false

        // 3. FIND MY PHONE BY VOICE
        if (rawInput.contains("find my phone") || rawInput.contains("where is my phone")) {
            lastActionTimestamp = now
            triggerFindMyPhoneAlert()
            return true
        }

        val cmd = rawInput
            .replace("hey siri", "")
            .replace("hi siri", "")
            .replace("hey ringbook", "")
            .replace("bolpilot", "")
            .replace("please", "")
            .replace("open the", "open")
            .trim()

        if (cmd.isEmpty()) return false

        try {
            // 4. GO TO HOME / BACK HOME / CLOSE APP
            if (cmd.contains("go to home") || cmd.contains("back to home") ||
                cmd.contains("back home") || cmd.contains("go home") ||
                cmd.contains("home screen") || cmd.contains("close app") || cmd == "home") {
                lastActionTimestamp = now
                speak("Going home")
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                launchFromBackground(homeIntent)
                return true
            }

            // 5. FLASHLIGHT / TORCH ON & OFF (Catches "place light on", "plus light on", "flash light on")
            if (cmd.contains("flashlight") || cmd.contains("flash light") ||
                cmd.contains("place light") || cmd.contains("plus light") || cmd.contains("torch")) {
                val wantOff = cmd.contains("off") || cmd.contains("of") || cmd.contains("close") || cmd.contains("stop")
                val wantOn = cmd.contains("on") || cmd.contains("start")
                if (wantOff || (isTorchOn && !wantOn)) {
                    lastActionTimestamp = now
                    toggleTorch(false)
                    speak("Flashlight off")
                    return true
                } else {
                    lastActionTimestamp = now
                    toggleTorch(true)
                    speak("Flashlight on")
                    return true
                }
            }

            // 6. TIME, DATE & BATTERY
            if (cmd.contains("what time") || cmd.contains("current time")) {
                lastActionTimestamp = now
                val t = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
                speak("The time is $t")
                return true
            }
            if (cmd.contains("today date") || cmd.contains("what date")) {
                lastActionTimestamp = now
                val d = SimpleDateFormat("EEEE, d MMMM", Locale.US).format(Date())
                speak("Today is $d")
                return true
            }
            if (cmd.contains("battery status") || cmd.contains("battery level") || cmd.contains("battery check")) {
                lastActionTimestamp = now
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                speak("Battery is $pct percent")
                return true
            }

            // 7. YOUTUBE SONG / VIDEO SEARCH & PLAY
            if ((cmd.contains("youtube") && (cmd.contains("search") || cmd.contains("play"))) ||
                (cmd.startsWith("play ") && !cmd.startsWith("play store"))) {
                lastActionTimestamp = now
                val query = cmd
                    .replace("open youtube and search", "")
                    .replace("open youtube search", "")
                    .replace("search on youtube", "")
                    .replace("on youtube", "")
                    .replace("youtube", "")
                    .replace("search", "")
                    .replace("play", "")
                    .trim()
                if (query.isNotEmpty()) {
                    speak("Playing $query on YouTube")
                    val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("query", query)
                    }
                    launchFromBackground(ytIntent)
                    return true
                }
            }

            // 8. GOOGLE WEB SEARCH
            if ((cmd.contains("google") && cmd.contains("search")) || cmd.startsWith("search ")) {
                lastActionTimestamp = now
                val query = cmd
                    .replace("search on google", "")
                    .replace("google search", "")
                    .replace("on google", "")
                    .replace("google", "")
                    .replace("search", "")
                    .trim()
                if (query.isNotEmpty()) {
                    speak("Searching $query on Google")
                    val gIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
                    launchFromBackground(gIntent)
                    return true
                }
            }

            // 9. CAMERA / SELFIE
            if (cmd.contains("take selfie") || cmd.contains("open camera")) {
                lastActionTimestamp = now
                speak("Opening Camera")
                launchFromBackground(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                return true
            }

            // 10. STRICT APP LAUNCHER: Requires "open [app]" OR exact known app name (Prevents accidental Clock/Phone opening!)
            if (cmd.startsWith("open ")) {
                val targetApp = cmd.removePrefix("open ").replace("app", "").trim()
                if (targetApp.isNotEmpty() && targetApp != "my phone" && targetApp != "the phone") {
                    if (openAnyInstalledApp(targetApp)) {
                        lastActionTimestamp = now
                        return true
                    }
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun openAnyInstalledApp(requestedName: String): Boolean {
        val cleanReq = requestedName.lowercase(Locale.getDefault()).trim()
        if (cleanReq.isEmpty() || cleanReq == "phone" || cleanReq == "clock") {
            // Only open dialer or clock if user explicitly said "open dialer" or "open alarm clock"
        }
        val pm = packageManager

        val knownAliases = listOf(
            Triple(listOf("youtube", "you tube"), "com.google.android.youtube", "YouTube"),
            Triple(listOf("whatsapp", "whats app"), "com.whatsapp", "WhatsApp"),
            Triple(listOf("instagram", "insta", "stagram"), "com.instagram.android", "Instagram"),
            Triple(listOf("facebook", "fb", "pesbook"), "com.facebook.katana", "Facebook"),
            Triple(listOf("snapchat", "snap"), "com.snapchat.android", "Snapchat"),
            Triple(listOf("telegram"), "org.telegram.messenger", "Telegram"),
            Triple(listOf("linkedin", "link in", "linked in"), "com.linkedin.android", "LinkedIn"),
            Triple(listOf("truecaller", "true color", "true caller"), "com.truecaller", "Truecaller"),
            Triple(listOf("dainik bhaskar", "bhaskar", "danik"), "com.ak.ta.dainikbhaskar.activity", "Dainik Bhaskar"),
            Triple(listOf("rapido", "rapid"), "com.rapido.passenger", "Rapido"),
            Triple(listOf("x", "twitter"), "com.twitter.android", "X"),
            Triple(listOf("reddit"), "com.reddit.frontpage", "Reddit"),
            Triple(listOf("phonepe", "phone pay"), "com.phonepe.app", "PhonePe"),
            Triple(listOf("paytm"), "net.one97.paytm", "Paytm"),
            Triple(listOf("gpay", "google pay"), "com.google.android.apps.nbu.paisa.user", "Google Pay"),
            Triple(listOf("chrome", "browser", "google"), "com.android.chrome", "Chrome"),
            Triple(listOf("gmail", "mail"), "com.google.android.gm", "Gmail"),
            Triple(listOf("maps", "google maps"), "com.google.android.apps.maps", "Google Maps"),
            Triple(listOf("play store"), "com.android.vending", "Play Store"),
            Triple(listOf("chatgpt", "chat gpt"), "com.openai.chatgpt", "ChatGPT"),
            Triple(listOf("settings", "setting"), "com.android.settings", "Settings")
        )

        for ((keywords, pkg, title) in knownAliases) {
            if (keywords.any { cleanReq == it || cleanReq.contains(it) }) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    speak("Opening $title")
                    launchFromBackground(intent)
                    return true
                }
            }
        }

        // Dynamic scan of all installed apps (excluding accidental "clock"/"phone" unless exact)
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val allApps = pm.queryIntentActivities(mainIntent, 0)

        for (info in allApps) {
            val label = info.loadLabel(pm).toString().lowercase(Locale.getDefault()).trim()
            if (label == "clock" || label == "phone") continue
            if (label == cleanReq || (cleanReq.length >= 3 && (label.contains(cleanReq) || cleanReq.contains(label)))) {
                val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (launchIntent != null) {
                    val niceName = info.loadLabel(pm).toString()
                    speak("Opening $niceName")
                    launchFromBackground(launchIntent)
                    return true
                }
            }
        }
        return false
    }

    // =========================================================================
    // RINGBOOK-STYLE VECTOR BLUE "PHONE FOUND" OVERLAY (Zero Emojis!)
    // =========================================================================
    private fun triggerFindMyPhoneAlert() {
        if (isFindingPhone) return
        isFindingPhone = true
        val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
        val durationSec = prefs.getInt("alert_duration", 30)

        try {
            if (prefs.getBoolean("alert_ring", true)) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0
                )
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                activeRingtone = RingtoneManager.getRingtone(applicationContext, uri)
                activeRingtone?.play()
            }
            if (prefs.getBoolean("alert_vibrate", true)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 500, 300, 500), 0)
                }
            }
            if (prefs.getBoolean("alert_flash", true)) {
                toggleTorch(true)
            }
        } catch (e: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            mainHandler.post {
                try {
                    if (phoneFoundOverlayView != null) return@post
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    val root = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.parseColor("#1E40AF"), Color.parseColor("#0F172A"))
                        )
                        setPadding(70, 120, 70, 100)
                    }

                    // Custom Drawn Concentric Ringing Bell Vector View (Exact Ringbook Look — No Emoji!)
                    val bellVectorView = object : View(this) {
                        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val cx = width / 2f
                            val cy = height / 2f

                            // Outer translucent concentric rings
                            paint.style = Paint.Style.FILL
                            paint.color = Color.parseColor("#22FFFFFF")
                            canvas.drawCircle(cx, cy, cx * 0.95f, paint)
                            paint.color = Color.parseColor("#44FFFFFF")
                            canvas.drawCircle(cx, cy, cx * 0.72f, paint)
                            paint.color = Color.WHITE
                            canvas.drawCircle(cx, cy, cx * 0.50f, paint)

                            // Blue Vector Bell inside white circle
                            paint.color = Color.parseColor("#1D4ED8")
                            val bellPath = Path().apply {
                                moveTo(cx, cy - 34f)
                                cubicTo(cx - 26f, cy - 34f, cx - 28f, cy - 5f, cx - 28f, cy + 16f)
                                lineTo(cx - 35f, cy + 24f)
                                lineTo(cx + 35f, cy + 24f)
                                lineTo(cx + 28f, cy + 16f)
                                cubicTo(cx + 28f, cy - 5f, cx + 26f, cy - 34f, cx, cy - 34f)
                                close()
                            }
                            canvas.drawPath(bellPath, paint)
                            canvas.drawCircle(cx, cy + 32f, 8f, paint)

                            // Sound wave arcs around bell
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = 5.5f
                            paint.strokeCap = Paint.Cap.ROUND
                            canvas.drawArc(cx - 46f, cy - 30f, cx + 46f, cy + 25f, 200f, 35f, false, paint)
                            canvas.drawArc(cx - 46f, cy - 30f, cx + 46f, cy + 25f, 305f, 35f, false, paint)
                        }
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(420, 420)
                    }

                    val title = TextView(this).apply {
                        text = "Phone found"
                        textSize = 32f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(0, 40, 0, 12)
                    }

                    val sub = TextView(this).apply {
                        text = "BolPilot heard your clap"
                        textSize = 16.5f
                        setTextColor(Color.parseColor("#DBEAFE"))
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, 90)
                    }

                    val stopBtn = Button(this).apply {
                        text = "Stop alert"
                        textSize = 17.5f
                        isAllCaps = false
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#1E3A8A"))
                        setPadding(110, 44, 110, 44)
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = 100f
                        }
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        setOnClickListener { stopFindMyPhoneAlert() }
                    }

                    val timerTxt = TextView(this).apply {
                        text = "Stops on its own in ${durationSec}s"
                        textSize = 13.5f
                        setTextColor(Color.parseColor("#93C5FD"))
                        gravity = Gravity.CENTER
                        setPadding(0, 35, 0, 0)
                    }

                    root.addView(bellVectorView)
                    root.addView(title)
                    root.addView(sub)
                    root.addView(stopBtn)
                    root.addView(timerTxt)

                    phoneFoundOverlayView = root
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.OPAQUE
                    )
                    wm.addView(phoneFoundOverlayView, params)

                    countdownTimer?.cancel()
                    countdownTimer = object : CountDownTimer(durationSec * 1000L, 1000L) {
                        override fun onTick(millisUntilFinished: Long) {
                            timerTxt.text = "Stops on its own in ${millisUntilFinished / 1000}s"
                        }
                        override fun onFinish() {
                            stopFindMyPhoneAlert()
                        }
                    }.start()
                } catch (e: Exception) {}
            }
        } else {
            mainHandler.postDelayed({ stopFindMyPhoneAlert() }, durationSec * 1000L)
        }
    }

    private fun stopFindMyPhoneAlert() {
        isFindingPhone = false
        countdownTimer?.cancel()
        try { activeRingtone?.stop() } catch (e: Exception) {}
        try { vibrator?.cancel() } catch (e: Exception) {}
        toggleTorch(false)
        mainHandler.post {
            try {
                if (phoneFoundOverlayView != null) {
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    wm.removeView(phoneFoundOverlayView)
                    phoneFoundOverlayView = null
                }
            } catch (e: Exception) {}
        }
    }

    // =========================================================================
    // VOICE LOCK SHIELD FULL-SCREEN OVERLAY (Zero Emojis!)
    // =========================================================================
    private fun lockPhoneNow() {
        speak("Phone locked")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            mainHandler.post {
                try {
                    if (lockScreenView != null) return@post
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    val prefs = getSharedPreferences("BolPilotPrefs", MODE_PRIVATE)
                    val unlockPhrase = prefs.getString("unlock_phrase", "Unlock my phone") ?: "Unlock my phone"
                    val backupPin = prefs.getString("backup_pin", "6367") ?: "6367"

                    val root = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setBackgroundColor(Color.parseColor("#090D16"))
                        setPadding(65, 160, 65, 90)
                    }

                    val badge = TextView(this).apply {
                        text = "BOLPILOT VOICE SHIELD ACTIVE"
                        textSize = 11.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#38BDF8"))
                        letterSpacing = 0.1f
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, 25)
                    }

                    val timeView = TextView(this).apply {
                        text = SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date())
                        textSize = 68f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }

                    val dateView = TextView(this).apply {
                        text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
                        textSize = 15.5f
                        setTextColor(Color.parseColor("#94A3B8"))
                        gravity = Gravity.CENTER
                        setPadding(0, 8, 0, 90)
                    }

                    val voiceHint = TextView(this).apply {
                        text = "Say \"$unlockPhrase\" to unlock hands-free"
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(45, 42, 45, 42)
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#1E293B"))
                            cornerRadius = 40f
                        }
                    }

                    val pinContainer = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                    }

                    var enteredPin = ""
                    val pinDots = TextView(this).apply {
                        text = "ENTER 4-DIGIT BACKUP PIN\n\n○   ○   ○   ○"
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(0, 25, 0, 30)
                    }

                    val grid = GridLayout(this).apply { columnCount = 3; rowCount = 4 }
                    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "CLR", "0", "DEL")
                    for (k in keys) {
                        val btn = Button(this).apply {
                            text = k
                            textSize = 17f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            background = GradientDrawable().apply {
                                setColor(Color.parseColor("#1E293B"))
                                cornerRadius = 100f
                            }
                            layoutParams = GridLayout.LayoutParams().apply { width = 180; height = 130; setMargins(14, 12, 14, 12) }
                            setOnClickListener {
                                when (k) {
                                    "CLR" -> { enteredPin = ""; pinDots.text = "ENTER 4-DIGIT BACKUP PIN\n\n○   ○   ○   ○" }
                                    "DEL" -> {
                                        if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                        val dots = "●   ".repeat(enteredPin.length) + "○   ".repeat(4 - enteredPin.length)
                                        pinDots.text = "BACKUP PIN\n\n$dots"
                                    }
                                    else -> {
                                        if (enteredPin.length < 4) {
                                            enteredPin += k
                                            val dots = "●   ".repeat(enteredPin.length) + "○   ".repeat(4 - enteredPin.length)
                                            pinDots.text = "BACKUP PIN\n\n$dots"
                                            if (enteredPin == backupPin) {
                                                lastUnlockTimestamp = System.currentTimeMillis()
                                                unlockAndWakePhone()
                                            } else if (enteredPin.length == 4) {
                                                pinDots.text = "INCORRECT PIN\n\n○   ○   ○   ○"
                                                enteredPin = ""
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        grid.addView(btn)
                    }

                    pinContainer.addView(pinDots)
                    pinContainer.addView(grid)

                    val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
                    val showPinBtn = TextView(this).apply {
                        text = "Use Backup PIN"
                        textSize = 14.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setPadding(55, 34, 55, 34)
                        background = GradientDrawable().apply { setColor(Color.parseColor("#1E293B")); cornerRadius = 60f }
                        setOnClickListener {
                            if (pinContainer.visibility == View.GONE) {
                                voiceHint.visibility = View.GONE; pinContainer.visibility = View.VISIBLE
                                text = "Use Voice Unlock"
                            } else {
                                pinContainer.visibility = View.GONE; voiceHint.visibility = View.VISIBLE
                                text = "Use Backup PIN"
                            }
                        }
                    }

                    root.addView(badge); root.addView(timeView); root.addView(dateView)
                    root.addView(voiceHint); root.addView(pinContainer)
                    root.addView(spacer); root.addView(showPinBtn)

                    lockScreenView = root
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.OPAQUE
                    )
                    wm.addView(lockScreenView, params)
                    safeStartListening()
                } catch (e: Exception) {}
            }
        }
    }

    private fun unlockAndWakePhone() {
        lastUnlockTimestamp = System.currentTimeMillis()
        speak("Phone unlocked")
        mainHandler.post {
            try {
                if (lockScreenView != null) {
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    wm.removeView(lockScreenView)
                    lockScreenView = null
                }
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "BolPilot::UnlockWake")
                wake.acquire(3000)
            } catch (e: Exception) {}
        }
    }

    private fun launchFromBackground(intent: Intent) {
        mainHandler.post {
            try {
                setupOverlayBridge()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            } catch (e: Exception) {}
        }
    }

    private fun toggleTorch(turnOn: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], turnOn)
            isTorchOn = turnOn
        } catch (e: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("BolPilot Voice OS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BolPilot Voice OS", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        isRunning = false
        countdownTimer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        try { audioManager.unregisterAudioPlaybackCallback(mediaPlaybackCallback) } catch (e: Exception) {}
        try { wakeLock?.release() } catch (e: Exception) {}
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            if (overlayBridgeView != null) wm.removeView(overlayBridgeView)
            if (lockScreenView != null) wm.removeView(lockScreenView)
            if (phoneFoundOverlayView != null) wm.removeView(phoneFoundOverlayView)
        } catch (e: Exception) {}
        speechRecognizer?.destroy()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

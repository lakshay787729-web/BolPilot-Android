package `in`.examdarpan.vaanix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.text.format.DateFormat
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Explicit opt-in continuous speech service. Android shows a persistent notification
 * while the microphone is active. This is not an always-on offline wake-word detector.
 */
class BolPilotListeningService : Service() {
    companion object {
        const val ACTION_START = "in.examdarpan.vaanix.action.START_LISTENING"
        const val ACTION_STOP = "in.examdarpan.vaanix.action.STOP_LISTENING"
        private const val CHANNEL_ID = "bolpilot_handsfree"
        private const val NOTIFICATION_ID = 731
    }

    private var recognizer: SpeechRecognizer? = null
    private var stopping = false
    private var lastCommandAt = 0L
    private var lastCommandText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopListeningService(); return START_NOT_STICKY }
            ACTION_START, null -> {
                stopping = false
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                startRecognition()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BolPilot hands-free listening", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Visible notification while you have enabled voice listening"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, BolPilotListeningService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 732, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPending = PendingIntent.getActivity(
            this, 733, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("BolPilot hands-free is on")
            .setContentText("Listening for explicit voice commands · Tap Stop to turn off")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopPending)
            .build()
    }

    private fun startRecognition() {
        if (stopping) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition unavailable on this device")
            stopListeningService()
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { updateNotification("Listening for commands") }
                    override fun onBeginningOfSpeech() { updateNotification("Hearing a command…") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { updateNotification("Processing speech…") }
                    override fun onError(error: Int) {
                        updateNotification(errorMessage(error) + " Hands-free stopped to prevent repeated mic tones. Tap BolPilot to start again.")
                        stopListeningService()
                    }
                    override fun onResults(results: Bundle?) {
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (phrase.isNotBlank()) handleCommand(phrase)
                        if (phrase.isBlank()) updateNotification("No speech recognized. Hands-free stopped; tap to retry.")
                        else updateNotification("Command received. Hands-free stopped; tap to listen again.")
                        stopListeningService()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val prefs = getSharedPreferences("bolpilot_settings", MODE_PRIVATE)
            val language = prefs.getString("language", "hi-IN") ?: "hi-IN"
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer?.startListening(speechIntent)
        } catch (_: Exception) {
            updateNotification("Could not start speech recognition. Hands-free stopped; tap to retry.")
            stopListeningService()
        }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service network error."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No command heard."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy."
        else -> "Speech recognition failed."
    }

    private fun handleCommand(raw: String) {
        val phrase = raw.trim()
        val q = phrase.lowercase(Locale.ROOT)
        val now = android.os.SystemClock.elapsedRealtime()
        if (q == lastCommandText && now - lastCommandAt < 2500L) return
        lastCommandText = q
        lastCommandAt = now
        when {
            q.startsWith("open ") || q.startsWith("launch ") || q.startsWith("start ") ||
                q.startsWith("खोलो ") || q.startsWith("खोल ") || q.startsWith("चालू करो ") -> {
                val appName = when {
                    q.startsWith("open ") -> phrase.substring(5)
                    q.startsWith("launch ") -> phrase.substring(7)
                    q.startsWith("start ") -> phrase.substring(6)
                    q.startsWith("खोलो ") -> phrase.substring(5)
                    q.startsWith("खोल ") -> phrase.substring(4)
                    else -> phrase.substringAfter("चालू करो ")
                }.trim().removeSuffix(" app").removeSuffix(" ऐप")
                if (appName.isNotBlank()) launchAppByName(appName)
            }
            q.contains("search google for ") || q.startsWith("search for ") || q.startsWith("google par search karo ") ||
                q.startsWith("सर्च करो ") || q.startsWith("खोजो ") -> {
                val query = when {
                    q.contains("search google for ") -> phrase.substringAfter("search google for ")
                    q.startsWith("search for ") -> phrase.substringAfter("search for ")
                    q.startsWith("google par search karo ") -> phrase.substringAfter("google par search karo ")
                    q.startsWith("सर्च करो ") -> phrase.substringAfter("सर्च करो ")
                    else -> phrase.substringAfter("खोजो ")
                }.trim()
                if (query.isNotBlank()) {
                    val search = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(search) }
                }
            }
            q.contains("lock phone") || q.contains("lock screen") || q.contains("screen lock") ||
                q.contains("फोन लॉक") || q.contains("मोबाइल लॉक") || q.contains("स्क्रीन लॉक") -> lockScreen()
            q.contains("wake screen") || q.contains("turn screen on") || q.contains("unlock phone") ||
                q.contains("unlock screen") || q.contains("फोन अनलॉक") || q.contains("स्क्रीन चालू") -> wakeScreenForUnlock()
            q.contains("flashlight on") || q.contains("torch on") || q.contains("टॉर्च चालू") || q.contains("फ्लैशलाइट चालू") || q.contains("लाइट चालू") -> setFlashlight(true)
            q.contains("flashlight off") || q.contains("torch off") || q.contains("टॉर्च बंद") || q.contains("फ्लैशलाइट बंद") || q.contains("लाइट बंद") -> setFlashlight(false)
            q.contains("what time") || q.contains("time is it") || q.contains("समय क्या") || q.contains("कितने बजे") -> {
                val time = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date())
                updateNotification("Time: $time")
            }
            q.contains("today's date") || q.contains("what is the date") || q.contains("आज की तारीख") || q.contains("आज कौन सी तारीख") -> {
                val date = java.text.SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(java.util.Date())
                updateNotification("Date: $date")
            }
            q.contains("battery") || q.contains("बैटरी") -> {
                val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
                val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                updateNotification("Battery: $level percent")
            }
            q.contains("open settings") || q.contains("settings kholo") || q.contains("सेटिंग खोलो") || q.contains("सेटिंग्स खोलो") -> {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    private fun launchAppByName(requested: String) {
        val normalized = requested.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(mainIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        val match = activities.firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString().lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
            label == normalized || label.contains(normalized) || normalized.contains(label)
        } ?: return
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }

    private fun setFlashlight(enabled: Boolean) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Flashlight needs Camera permission. Allow it in Android Settings for BolPilot.")
            return
        }
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                updateNotification("This device has no supported flashlight.")
                return
            }
            cameraManager.setTorchMode(cameraId, enabled)
            updateNotification(if (enabled) "Flashlight turned on." else "Flashlight turned off.")
        } catch (_: Exception) {
            updateNotification("Could not control flashlight. Check Camera permission and device support.")
        }
    }

    private fun wakeScreenForUnlock() {
        // Wake the display, but never bypass the owner's PIN, password, or biometric.
        val power = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "BolPilot:VoiceWake"
        )
        try {
            wakeLock.acquire(2500L)
            updateNotification("Screen awakened. Unlock with your normal PIN or biometric.")
        } catch (_: Exception) {
            updateNotification("Wake the screen and unlock with your normal security method.")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun lockScreen() {
        val manager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, BolPilotDeviceAdminReceiver::class.java)
        if (manager.isAdminActive(admin)) runCatching { manager.lockNow() }
        else {
            val prompt = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "BolPilot can lock the screen only after you approve this Android permission.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(prompt) }
        }
    }

    private fun updateNotification(message: String) {
        if (stopping) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("BolPilot hands-free is on")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening",
                PendingIntent.getService(this, 732, Intent(this, BolPilotListeningService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun stopListeningService() {
        stopping = true
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}

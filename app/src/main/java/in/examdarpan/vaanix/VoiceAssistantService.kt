package `in`.examdarpan.vaanix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

/** User-started voice assistant with a visible, stoppable foreground notification. */
class VoiceAssistantService : Service() {
    companion object {
        const val ACTION_START = "in.examdarpan.vaanix.action.START_VOICE"
        const val ACTION_STOP = "in.examdarpan.vaanix.action.STOP_VOICE"
        private const val CHANNEL = "bolpilot_voice"
        private const val NOTIFICATION_ID = 731
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVoice()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        stopped = false
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        if (tts == null) tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("hi", "IN")
        }
        startRecognition()
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "BolPilot voice assistant", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Visible notification while voice assistant is listening"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val stop = Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 732, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 733, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("BolPilot voice assistant")
            .setContentText("Listening is on — tap Stop to turn it off")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun startRecognition() {
        if (stopped) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { scheduleRecognition(1400) }
            override fun onResults(results: android.os.Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                handleCommand(spoken)
                scheduleRecognition(700)
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { scheduleRecognition(1500) }
    }

    private fun scheduleRecognition(delay: Long) {
        if (!stopped) handler.postDelayed({ startRecognition() }, delay)
    }

    private fun handleCommand(raw: String) {
        val command = raw.lowercase(Locale.ROOT)
        when {
            command.contains("stop listening") || command.contains("stop voice") || command.contains("बंद करो") -> stopVoice()
            command.contains("youtube") -> openUrl("https://www.youtube.com")
            command.contains("google") || command.contains("search") || command.contains("खोजो") || command.contains("सर्च") ->
                openUrl("https://www.google.com/search?q=" + android.net.Uri.encode(raw))
            command.contains("time") || command.contains("समय") -> speak("समय देखने के लिए अपने फोन की घड़ी खोलें")
            raw.isNotBlank() -> speak("मैंने सुना: $raw")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { speak("यह कमांड अभी उपलब्ध नहीं है") }
    }

    private fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bolpilot_reply") }

    private fun stopVoice() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
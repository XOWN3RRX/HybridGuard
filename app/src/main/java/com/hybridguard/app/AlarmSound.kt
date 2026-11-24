package com.hybridguard.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Sounds an alarm in one sequence, so the warning is heard over the music:
 *
 * 1. takes the audio focus: music and podcasts pause ("Pause the music") or
 *    duck, and resume when it is given back;
 * 2. for a STOP alarm, raises the alarm volume and the media volume (the
 *    spoken warning, also the car speakers) to at least the owner's levels;
 * 3. rings the tone, then says the warning (one after the other, not over it);
 * 4. puts the volumes back and gives the focus back.
 *
 * A new alarm replaces the one sounding; the volumes saved first are the ones
 * put back. Whatever happens, everything is put back after [SAFETY_MS].
 * Runs on the main thread.
 */
object AlarmSound {
    private const val SAFETY_MS = 45_000L
    private const val GAP_MS = 300L

    private val main = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingWords: String? = null
    private var pendingId = 0
    private var ringtone: Ringtone? = null
    private var focus: AudioFocusRequest? = null

    /** Stream -> the owner's volume before it was raised. */
    private val saved = mutableMapOf<Int, Int>()

    /** The sequence sounding now; older callbacks see a different number and do nothing. */
    private var token = 0

    fun play(
        context: Context,
        tone: Boolean,
        loud: Boolean,
        words: String?,
        pauseMusic: Boolean,
        alarmVolume: Int,
        voiceVolume: Int,
    ) = main.post {
        val app = context.applicationContext
        val am = app.getSystemService(AudioManager::class.java).also { audio = it }
        cancelPlayback()
        val id = ++token
        if (!tone && words == null) {
            release()
            return@post
        }
        requestFocus(am, loud, pauseMusic)
        if (tone && loud) raise(am, AudioManager.STREAM_ALARM, alarmVolume)
        if (words != null) raise(am, AudioManager.STREAM_MUSIC, voiceVolume)
        val toneMs = when {
            !tone -> 0L
            loud -> 5_000L
            else -> 2_000L
        }
        if (tone) startTone(app, loud)
        main.postDelayed({
            if (id != token) return@postDelayed
            stopTone()
            if (words != null) speak(app, words, id) else release()
        }, toneMs + if (tone && words != null) GAP_MS else 0L)
        main.postDelayed({
            if (id == token) {
                cancelPlayback()
                release()
            }
        }, SAFETY_MS)
    }

    /** "OK, seen" or the alarm ended: silence now, volumes and music back. */
    fun stop() = main.post {
        token++
        cancelPlayback()
        release()
    }

    private fun requestFocus(am: AudioManager, loud: Boolean, pauseMusic: Boolean) {
        if (focus != null) return // still held from the alarm before: keep the music paused
        val attrs = AudioAttributes.Builder()
            .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val gain = if (pauseMusic) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { am.requestAudioFocus(request) }
        focus = request
    }

    /** Raises [stream] to at least [percent] of its maximum; the owner's level is kept to put back. */
    private fun raise(am: AudioManager, stream: Int, percent: Int) {
        if (percent <= 0) return
        val max = am.getStreamMaxVolume(stream)
        val target = ((max * percent.coerceAtMost(100)) + 99) / 100
        val now = am.getStreamVolume(stream)
        if (now >= target) return
        saved.putIfAbsent(stream, now)
        runCatching { am.setStreamVolume(stream, target, 0) }
    }

    private fun release() {
        val am = audio
        if (am != null) {
            saved.forEach { (stream, volume) -> runCatching { am.setStreamVolume(stream, volume, 0) } }
            focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        }
        saved.clear()
        focus = null
    }

    /** [loud]: the alarm tone on the alarm volume (heard with the ringer silent); else the notification tone. */
    private fun startTone(context: Context, loud: Boolean) {
        val uri = RingtoneManager.getDefaultUri(if (loud) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val r = RingtoneManager.getRingtone(context, uri) ?: return
        r.audioAttributes = AudioAttributes.Builder()
            .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching { r.play() }
        ringtone = r
    }

    private fun stopTone() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun cancelPlayback() {
        stopTone()
        pendingWords = null
        runCatching { tts?.stop() }
    }

    /** Spoken like navigation guidance: through the car speakers when the phone is connected to the car. */
    private fun speak(context: Context, words: String, id: Int) {
        val engine = tts
        if (engine != null && ttsReady) {
            say(engine, words, id)
            return
        }
        pendingWords = words
        pendingId = id
        if (engine != null) return // still starting: it says the pending words when ready
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                val t = tts ?: return@post
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) {
                    tts = null
                    if (pendingId == token) release()
                    return@post
                }
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finished(utteranceId)

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
                })
                val w = pendingWords
                pendingWords = null
                if (w != null && pendingId == token) say(t, w, pendingId)
            }
        }
    }

    private fun say(t: TextToSpeech, words: String, id: Int) {
        t.speak(words, TextToSpeech.QUEUE_FLUSH, Bundle(), "alarm-$id")
    }

    private fun finished(utteranceId: String?) {
        main.post { if (utteranceId == "alarm-$token") release() }
    }
}

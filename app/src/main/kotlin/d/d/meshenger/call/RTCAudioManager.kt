/*
 * This file originates from the WebRTC project that is governed by a BSD-style license.
 * The code was rewritten, but many comments remain.
 */
package d.d.meshenger.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import d.d.meshenger.Log
import d.d.meshenger.Utils

/**
 * RTCAudioManager manages all audio-related parts.
 */
class RTCAudioManager(contextArg: Context) {
    enum class SpeakerphoneMode {
        AUTO, ON, OFF
    }

    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE
    }

    interface AudioManagerEvents {
        // Callback fired once audio device is changed.
        fun onAudioDeviceChanged(oldDevice: AudioDevice, newDevice: AudioDevice)
    }

    private val audioManager = contextArg.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioManagerEvents: AudioManagerEvents? = null
    private var audioManagerInitialized = false

    private var savedAudioMode = AudioManager.MODE_INVALID
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false

    private var speakerphoneMode = SpeakerphoneMode.AUTO
    private var isProximityNear = true // Default to speaker turned off for AUTO
    private var isSpeakerphoneOn = false

    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * This method needs to be called when the proximity sensor reports a state change,
     * e.g., from "NEAR to FAR" or from "FAR to NEAR".
     */
    fun onProximitySensorChangedState(isProximityNear: Boolean) {
        Log.d(this, "onProximitySensorChangedState() isProximityNear=$isProximityNear")
        this.isProximityNear = isProximityNear

        if (audioManagerInitialized) {
            updateAudioDeviceState()
        }
    }

    fun getSpeakerphoneMode(): SpeakerphoneMode {
        return speakerphoneMode
    }

    fun setSpeakerphoneMode(mode: SpeakerphoneMode) {
        this.speakerphoneMode = mode
        updateAudioDeviceState()
    }

    fun setEventListener(audioManagerEvents: AudioManagerEvents? = null) {
        this.audioManagerEvents = audioManagerEvents
    }

    /**
     * Checks whether a wired headset is connected or not.
     */
    private fun hasWiredHeadset(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }
        } else {
            // For older versions, return false or implement an alternative logic
            false
        }
    }

    fun getAudioDevice(): AudioDevice {
        return when {
            isSpeakerphoneOn -> AudioDevice.SPEAKER_PHONE
            hasWiredHeadset() -> AudioDevice.WIRED_HEADSET
            else -> AudioDevice.EARPIECE
        }
    }

    // Callback method for changes in audio focus.
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    fun start() {
        Log.d(this, "start()")
        Utils.checkIsOnMainThread()
        if (audioManagerInitialized) {
            Log.w(this, "start() already active")
            return
        }

        audioManagerInitialized = true

        // Store current audio state so we can restore it when stop() is called.
        savedAudioMode = audioManager.mode
        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create an AudioManager.OnAudioFocusChangeListener instance.
            audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                val typeOfChange = when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "AUDIOFOCUS_GAIN_TRANSIENT"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE"
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"
                    AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
                    else -> "AUDIOFOCUS_INVALID"
                }
                Log.d(this, "onAudioFocusChange: $typeOfChange")
            }

            // Create the AudioFocusRequest.
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d(this, "start() Audio focus request granted for VOICE_CALL streams")
            } else {
                Log.w(this, "start() Audio focus request failed")
            }
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        updateAudioDeviceState()
    }

    fun stop() {
        Log.d(this, "stop()")
        Utils.checkIsOnMainThread()
        if (!audioManagerInitialized) {
            Log.w(this, "stop() Was not initialized.")
            return
        }
        audioManagerInitialized = false

        // Restore previously stored audio states.
        audioManager.isSpeakerphoneOn = savedIsSpeakerPhoneOn
        audioManager.isMicrophoneMute = savedIsMicrophoneMute

        @SuppressLint("WrongConstant")
        audioManager.mode = savedAudioMode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
        }
        audioFocusChangeListener = null

        isProximityNear = true
        isSpeakerphoneOn = false
    }

    fun getMicrophoneEnabled(): Boolean {
        return !audioManager.isMicrophoneMute
    }

    private fun updateAudioDeviceState() {
        Utils.checkIsOnMainThread()
        Log.d(this, "updateAudioDeviceState()")

        if (!audioManagerInitialized) {
            Log.d(this, "updateAudioDeviceState() RTCAudioManager not running")
            return
        }

        val oldAudioDevice = getAudioDevice()

        // Main audio logic
        isSpeakerphoneOn = when (speakerphoneMode) {
            SpeakerphoneMode.AUTO -> !isProximityNear
            SpeakerphoneMode.ON -> true
            SpeakerphoneMode.OFF -> false
        }

        val newAudioDevice = getAudioDevice()

        if (audioManager.isSpeakerphoneOn != isSpeakerphoneOn) {
            audioManager.isSpeakerphoneOn = isSpeakerphoneOn
        }

        Log.d(this, "updateAudioDeviceState() "
                + "isSpeakerphoneOn: $isSpeakerphoneOn, "
                + "isProximityNear: $isProximityNear, "
                + "oldDevice: $oldAudioDevice, "
                + "newDevice: $newAudioDevice")

        if (oldAudioDevice != newAudioDevice) {
            audioManagerEvents?.onAudioDeviceChanged(oldAudioDevice, newAudioDevice)
        }
    }
}
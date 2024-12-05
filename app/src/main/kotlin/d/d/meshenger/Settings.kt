// Settings.kt

package d.d.meshenger

import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import java.util.Arrays

class Settings {
    var username = ""
    var publicKey: ByteArray = ByteArray(0)
    var encryptedSecretKey: String = "" // Nueva propiedad para almacenar la clave privada cifrada
    var nightMode = "auto" // on, off, auto
    var speakerphoneMode = "auto" // on, off, auto
    var blockUnknown = false
    var useNeighborTable = false
    var guessEUI64Address = true
    var promptOutgoingCalls = false
    var videoHardwareAcceleration = true
    var disableCallHistory = false
    var disableProximitySensor = false
    var disableAudioProcessing = false
    var showUsernameAsLogo = true
    var pushToTalk = false
    var startOnBootup = false
    var connectRetries = 1
    var connectTimeout = 500
    var enableMicrophoneByDefault = true
    var enableCameraByDefault = false
    var selectFrontCameraByDefault = true
    var disableCpuOveruseDetection = false
    var autoAcceptCalls = false
    var menuPassword = ""
    var videoDegradationMode = "balanced"
    var cameraResolution = "auto"
    var cameraFramerate = "auto"
    var automaticStatusUpdates = true
    var themeName = "sky_blue"
    var ignoreOverlayPermission = false
    var addresses = mutableListOf<String>()

    // Campo para almacenar el contacto propio
    var ownContact: Contact? = null

    fun destroy() {
        Arrays.fill(publicKey, 0.toByte())
    }

    companion object {
        fun fromJSON(obj: JSONObject): Settings {
            val s = Settings()
            s.username = obj.optString("username", "")
            s.publicKey = Utils.hexStringToByteArray(obj.optString("public_key", ""))
            s.encryptedSecretKey = obj.optString("encrypted_secret_key", "")
            s.nightMode = obj.optString("night_mode", "auto")
            s.speakerphoneMode = obj.optString("speakerphone_mode", "auto")
            s.blockUnknown = obj.optBoolean("block_unknown", false)
            s.useNeighborTable = obj.optBoolean("use_neighbor_table", false)
            s.guessEUI64Address = obj.optBoolean("guess_eui64_address", true)
            s.videoHardwareAcceleration = obj.optBoolean("video_hardware_acceleration", true)
            s.disableAudioProcessing = obj.optBoolean("disable_audio_processing", false)
            s.connectTimeout = obj.optInt("connect_timeout", 500)
            s.disableCallHistory = obj.optBoolean("disable_call_history", false)
            s.disableProximitySensor = obj.optBoolean("disable_proximity_sensor", false)
            s.promptOutgoingCalls = obj.optBoolean("prompt_outgoing_calls", false)
            s.showUsernameAsLogo = obj.optBoolean("show_username_as_logo", true)
            s.pushToTalk = obj.optBoolean("push_to_talk", false)
            s.startOnBootup = obj.optBoolean("start_on_bootup", false)
            s.connectRetries = obj.optInt("connect_retries", 1)
            s.enableMicrophoneByDefault = obj.optBoolean("enable_microphone_by_default", true)
            s.enableCameraByDefault = obj.optBoolean("enable_camera_by_default", false)
            s.selectFrontCameraByDefault = obj.optBoolean("select_front_camera_by_default", true)
            s.disableCpuOveruseDetection = obj.optBoolean("disable_cpu_overuse_detection", false)
            s.autoAcceptCalls = obj.optBoolean("auto_accept_calls", false)
            s.menuPassword = obj.optString("menu_password", "")
            s.videoDegradationMode = obj.optString("video_degradation_mode", "balanced")
            s.cameraResolution = obj.optString("camera_resolution", "auto")
            s.cameraFramerate = obj.optString("camera_framerate", "auto")
            s.automaticStatusUpdates = obj.optBoolean("automatic_status_updates", true)
            s.themeName = obj.optString("theme_name", "sky_blue")
            s.ignoreOverlayPermission = obj.optBoolean("ignore_overlay_permission", false)

            val addressesArray = obj.optJSONArray("addresses")
            if (addressesArray != null) {
                for (i in 0 until addressesArray.length()) {
                    var address = addressesArray.getString(i)
                    if (AddressUtils.isIPAddress(address) || AddressUtils.isDomain(address)) {
                        address = address.lowercase(Locale.ROOT)
                    } else if (AddressUtils.isMACAddress(address)) {
                        // Convertir dirección MAC a dirección EUI64 de enlace local
                        val linkLocalAddress = AddressUtils.getLinkLocalFromMAC(address)
                        if (linkLocalAddress != null) {
                            address = linkLocalAddress
                        } else {
                            continue
                        }
                    } else {
                        Log.d("Settings", "Invalid address $address")
                        continue
                    }
                    if (!s.addresses.contains(address)) {
                        s.addresses.add(address)
                    }
                }
            }

            // Deserializar el contacto propio si existe
            if (obj.has("own_contact")) {
                s.ownContact = Contact.fromJSON(obj.getJSONObject("own_contact"), false)
            }

            return s
        }

        fun toJSON(s: Settings): JSONObject {
            val obj = JSONObject()
            obj.put("username", s.username)
            obj.put("public_key", Utils.byteArrayToHexString(s.publicKey))
            obj.put("encrypted_secret_key", s.encryptedSecretKey)
            obj.put("night_mode", s.nightMode)
            obj.put("speakerphone_mode", s.speakerphoneMode)
            obj.put("block_unknown", s.blockUnknown)
            obj.put("use_neighbor_table", s.useNeighborTable)
            obj.put("guess_eui64_address", s.guessEUI64Address)
            obj.put("connect_timeout", s.connectTimeout)
            obj.put("video_hardware_acceleration", s.videoHardwareAcceleration)
            obj.put("disable_audio_processing", s.disableAudioProcessing)
            obj.put("disable_call_history", s.disableCallHistory)
            obj.put("disable_proximity_sensor", s.disableProximitySensor)
            obj.put("prompt_outgoing_calls", s.promptOutgoingCalls)
            obj.put("show_username_as_logo", s.showUsernameAsLogo)
            obj.put("push_to_talk", s.pushToTalk)
            obj.put("start_on_bootup", s.startOnBootup)
            obj.put("connect_retries", s.connectRetries)
            obj.put("enable_microphone_by_default", s.enableMicrophoneByDefault)
            obj.put("enable_camera_by_default", s.enableCameraByDefault)
            obj.put("select_front_camera_by_default", s.selectFrontCameraByDefault)
            obj.put("disable_cpu_overuse_detection", s.disableCpuOveruseDetection)
            obj.put("auto_accept_calls", s.autoAcceptCalls)
            obj.put("menu_password", s.menuPassword)
            obj.put("video_degradation_mode", s.videoDegradationMode)
            obj.put("camera_resolution", s.cameraResolution)
            obj.put("camera_framerate", s.cameraFramerate)
            obj.put("automatic_status_updates", s.automaticStatusUpdates)
            obj.put("theme_name", s.themeName)
            obj.put("ignore_overlay_permission", s.ignoreOverlayPermission)

            val addressesArray = JSONArray()
            for (address in s.addresses) {
                addressesArray.put(address)
            }
            obj.put("addresses", addressesArray)

            // Serializar el contacto propio si existe
            s.ownContact?.let {
                obj.put("own_contact", Contact.toJSON(it, false))
            }

            return obj
        }
    }
}

// Crypto.kt

package d.d.meshenger

import android.content.Context
import android.os.Build
import android.util.Base64
import org.libsodium.jni.Sodium
import org.libsodium.jni.SodiumConstants
import java.nio.charset.Charset
import java.security.KeyPair // Añade esta importación
import java.security.MessageDigest
import java.util.*

internal object Crypto {
    // Para desarrollo/pruebas solamente
    private const val disableCrypto = false

    // Generar una huella digital (fingerprint) de una clave pública
    fun generateFingerprint(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        // Usar android.util.Base64 para compatibilidad con API Level 21+
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    // Desencriptar base de datos usando una contraseña
    @JvmStatic
    fun decryptDatabase(encryptedMessage: ByteArray?, password: ByteArray?): ByteArray? {
        if (encryptedMessage == null || password == null) {
            return null
        }

        if (encryptedMessage.size <= (4 + Sodium.crypto_pwhash_saltbytes() + SodiumConstants.NONCE_BYTES + SodiumConstants.MAC_BYTES)) {
            return null
        }

        if (disableCrypto) {
            return encryptedMessage
        }

        // Separar salt, nonce y datos encriptados
        val header = ByteArray(4)
        val salt = ByteArray(Sodium.crypto_pwhash_saltbytes())
        val nonce = ByteArray(SodiumConstants.NONCE_BYTES)
        val encryptedData =
            ByteArray(encryptedMessage.size - header.size - salt.size - nonce.size)
        System.arraycopy(encryptedMessage, 0, header, 0, header.size)
        System.arraycopy(encryptedMessage, header.size, salt, 0, salt.size)
        System.arraycopy(encryptedMessage, header.size + salt.size, nonce, 0, nonce.size)
        System.arraycopy(
            encryptedMessage,
            header.size + salt.size + nonce.size,
            encryptedData,
            0,
            encryptedData.size
        )

        // Se espera que el header sea 0
        if (!(header[0].toInt() == 0 && header[1].toInt() == 0 && header[2].toInt() == 0 && header[3].toInt() == 0)) {
            return null
        }

        // Hash de la contraseña para obtener la clave
        val key = ByteArray(Sodium.crypto_box_seedbytes())
        val rc1 = Sodium.crypto_pwhash(
            key, key.size, password, password.size, salt,
            Sodium.crypto_pwhash_opslimit_interactive(),
            Sodium.crypto_pwhash_memlimit_interactive(),
            Sodium.crypto_pwhash_alg_default()
        )

        // Desencriptar
        val decryptedData = ByteArray(encryptedData.size - SodiumConstants.MAC_BYTES)
        val rc2 = Sodium.crypto_secretbox_open_easy(
            decryptedData,
            encryptedData,
            encryptedData.size,
            nonce,
            key
        )

        // Limpiar memoria
        Arrays.fill(header, 0.toByte())
        Arrays.fill(salt, 0.toByte())
        Arrays.fill(key, 0.toByte())
        Arrays.fill(nonce, 0.toByte())
        Arrays.fill(encryptedData, 0.toByte())
        return if (rc1 == 0 && rc2 == 0) {
            decryptedData
        } else {
            Arrays.fill(decryptedData, 0.toByte())
            null
        }
    }

    // Encriptar base de datos usando una contraseña
    @JvmStatic
    fun encryptDatabase(data: ByteArray?, password: ByteArray?): ByteArray? {
        if (data == null || password == null) {
            return null
        }

        if (disableCrypto) {
            return data
        }

        // Hash de la contraseña para obtener la clave
        val salt = ByteArray(Sodium.crypto_pwhash_saltbytes())
        Sodium.randombytes_buf(salt, salt.size)

        // Hash de la contraseña para obtener la clave
        val key = ByteArray(Sodium.crypto_box_seedbytes())
        val rc1 = Sodium.crypto_pwhash(
            key, key.size, password, password.size, salt,
            Sodium.crypto_pwhash_opslimit_interactive(),
            Sodium.crypto_pwhash_memlimit_interactive(),
            Sodium.crypto_pwhash_alg_default()
        )
        val header = ByteArray(4)
        header[0] = 0
        header[1] = 0
        header[2] = 0
        header[3] = 0

        // Crear nonce
        val nonce = ByteArray(SodiumConstants.NONCE_BYTES)
        Sodium.randombytes_buf(nonce, nonce.size)

        // Encriptar
        val encryptedData = ByteArray(SodiumConstants.MAC_BYTES + data.size)
        val rc2 = Sodium.crypto_secretbox_easy(encryptedData, data, data.size, nonce, key)

        // Prepend header, salt y nonce
        val encryptedMessage =
            ByteArray(header.size + salt.size + nonce.size + encryptedData.size)
        System.arraycopy(header, 0, encryptedMessage, 0, header.size)
        System.arraycopy(salt, 0, encryptedMessage, header.size, salt.size)
        System.arraycopy(nonce, 0, encryptedMessage, header.size + salt.size, nonce.size)
        System.arraycopy(
            encryptedData,
            0,
            encryptedMessage,
            header.size + salt.size + nonce.size,
            encryptedData.size
        )

        // Limpiar memoria
        Arrays.fill(header, 0.toByte())
        Arrays.fill(salt, 0.toByte())
        Arrays.fill(key, 0.toByte())
        Arrays.fill(nonce, 0.toByte())
        Arrays.fill(encryptedData, 0.toByte())
        return if (rc1 == 0 && rc2 == 0) {
            encryptedMessage
        } else {
            Arrays.fill(encryptedMessage, 0.toByte())
            null
        }
    }

    @JvmStatic
    fun encryptMessage(
        message: String,
        otherPublicKey: ByteArray?,
        context: Context
    ): ByteArray? {
        if (disableCrypto) {
            return message.toByteArray()
        }

        // Verificar si el dispositivo soporta API Level 23 o superior
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Dispositivo incompatible
            return null
        }

        // Obtener el par de claves propio desde el KeyStore
        val keyPair = KeyStoreManager.getOrCreateKeyPair(context) ?: return null
        val ownPublicKey = keyPair.public.encoded
        val ownPrivateKey = keyPair.private.encoded

        val messageBytes = message.toByteArray()
        val signed = sign(messageBytes, ownPrivateKey) ?: return null
        val data = ByteArray(ownPublicKey.size + signed.size)
        System.arraycopy(ownPublicKey, 0, data, 0, ownPublicKey.size)
        System.arraycopy(signed, 0, data, ownPublicKey.size, signed.size)
        return encrypt(data, otherPublicKey)
    }

    @JvmStatic
    fun decryptMessage(
        message: ByteArray?,
        otherPublicKeySignOut: ByteArray?,
        context: Context
    ): String? {
        if (otherPublicKeySignOut == null || otherPublicKeySignOut.size != Sodium.crypto_sign_publickeybytes()) {
            return null
        }

        // Verificar si el dispositivo soporta API Level 23 o superior
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Dispositivo incompatible
            return null
        }

        // Obtener el par de claves propio desde el KeyStore
        val keyPair = KeyStoreManager.getOrCreateKeyPair(context) ?: return null
        val ownPublicKey = keyPair.public.encoded
        val ownPrivateKey = keyPair.private.encoded

        if (disableCrypto) {
            return String(message!!, Charset.forName("UTF-8"))
        }

        val messageData = decrypt(message, ownPublicKey, ownPrivateKey)
        if (messageData == null || messageData.size <= otherPublicKeySignOut.size) {
            return null
        }

        // Dividir los datos del mensaje en clave pública del remitente y contenido
        val senderPublicKey = ByteArray(otherPublicKeySignOut.size)
        System.arraycopy(messageData, 0, senderPublicKey, 0, senderPublicKey.size)
        val messageSignedData = ByteArray(messageData.size - senderPublicKey.size)
        System.arraycopy(
            messageData,
            senderPublicKey.size,
            messageSignedData,
            0,
            messageSignedData.size
        )
        val unsignedData = unsign(messageSignedData, senderPublicKey)
            ?: // La firma no coincide con la clave pública transmitida
            return null
        return String(unsignedData, Charset.forName("UTF-8"))
    }

    private fun sign(data: ByteArray?, secretKey: ByteArray?): ByteArray? {
        if (data == null) {
            return null
        }
        if (secretKey == null || secretKey.size != Sodium.crypto_sign_secretkeybytes()) {
            return null
        }
        val signedMessage = ByteArray(Sodium.crypto_sign_bytes() + data.size)
        val signedMessageLength = IntArray(1)
        val rc = Sodium.crypto_sign(signedMessage, signedMessageLength, data, data.size, secretKey)
        return if (rc == 0 && signedMessage.size == signedMessageLength[0]) {
            signedMessage
        } else {
            null
        }
    }

    // Verificar mensaje firmado
    private fun unsign(signedMessage: ByteArray?, publicKey: ByteArray?): ByteArray? {
        if (signedMessage == null || signedMessage.size < Sodium.crypto_sign_bytes()) {
            return null
        }
        if (publicKey == null || publicKey.size != Sodium.crypto_sign_publickeybytes()) {
            return null
        }
        val unsignedMessage = ByteArray(signedMessage.size - Sodium.crypto_sign_bytes())
        val messageSize = IntArray(1)
        val rc = Sodium.crypto_sign_open(
            unsignedMessage,
            messageSize,
            signedMessage,
            signedMessage.size,
            publicKey
        )
        return if (rc == 0 && unsignedMessage.size == messageSize[0]) {
            unsignedMessage
        } else {
            null
        }
    }

    // Encriptar un mensaje anónimo usando la clave pública del receptor
    private fun encrypt(data: ByteArray?, publicKeySign: ByteArray?): ByteArray? {
        if (data == null) {
            return null
        }
        if (publicKeySign == null || publicKeySign.size != Sodium.crypto_sign_publickeybytes()) {
            return null
        }
        val publicKeyBox = ByteArray(Sodium.crypto_box_publickeybytes())
        val rc1 = Sodium.crypto_sign_ed25519_pk_to_curve25519(publicKeyBox, publicKeySign)
        if (rc1 != 0 || publicKeyBox.size != Sodium.crypto_box_publickeybytes()) {
            return null
        }
        val ciphertext = ByteArray(SodiumConstants.SEAL_BYTES + data.size)
        val rc = Sodium.crypto_box_seal(ciphertext, data, data.size, publicKeyBox)
        return if (rc == 0) {
            ciphertext
        } else {
            null
        }
    }

    // Desencriptar un mensaje anónimo usando las claves pública y privada del receptor
    private fun decrypt(
        ciphertext: ByteArray?,
        publicKeySign: ByteArray?,
        secretKeySign: ByteArray?
    ): ByteArray? {
        if (ciphertext == null || ciphertext.size < SodiumConstants.SEAL_BYTES) {
            return null
        }
        if (publicKeySign == null || publicKeySign.size != Sodium.crypto_sign_publickeybytes()) {
            return null
        }
        if (secretKeySign == null || secretKeySign.size != Sodium.crypto_sign_secretkeybytes()) {
            return null
        }

        // Convertir claves de firma a claves de caja
        val publicKeyBox = ByteArray(Sodium.crypto_box_publickeybytes())
        val secretKeyBox = ByteArray(Sodium.crypto_box_secretkeybytes())
        val rc1 = Sodium.crypto_sign_ed25519_pk_to_curve25519(publicKeyBox, publicKeySign)
        val rc2 = Sodium.crypto_sign_ed25519_sk_to_curve25519(secretKeyBox, secretKeySign)
        if (rc1 != 0 || rc2 != 0) {
            return null
        }
        val decrypted = ByteArray(ciphertext.size - SodiumConstants.SEAL_BYTES)
        val rc = Sodium.crypto_box_seal_open(decrypted, ciphertext, ciphertext.size, publicKeyBox, secretKeyBox)
        return if (rc == 0) {
            decrypted
        } else {
            null
        }
    }
}

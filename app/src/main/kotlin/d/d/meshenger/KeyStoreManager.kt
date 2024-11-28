package d.d.meshenger

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore

object KeyStoreManager {

    private const val KEYSTORE_ALIAS = "MeshengerKeyAlias"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * Retrieves or creates a key pair in the Android Keystore
     */
    fun getOrCreateKeyPair(context: Context): KeyPair? {
        // Verifica si el dispositivo soporta API Level 23 o superior
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Dispositivo incompatible
            return null
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // Check if the key pair already exists
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val privateKey = keyStore.getKey(KEYSTORE_ALIAS, null) as java.security.PrivateKey
            val publicKey = keyStore.getCertificate(KEYSTORE_ALIAS).publicKey
            return KeyPair(publicKey, privateKey)
        }

        // Generate a new key pair
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE
        )

        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
        ).setDigests(
            KeyProperties.DIGEST_SHA256,
            KeyProperties.DIGEST_SHA512
        ).setEncryptionPaddings(
            KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1
        ).build()

        keyPairGenerator.initialize(keyGenParameterSpec)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Deletes the key pair (optional for migration or reset purposes)
     */
    fun deleteKeyPair() {
        // Verifica si el dispositivo soporta API Level 23 o superior
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Dispositivo incompatible
            return
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }
    }
}
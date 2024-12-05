// KeyStoreManager.kt

package d.d.meshenger

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

object KeyStoreManager {

    private const val KEY_ALIAS = "MeshengerSecretKeyAlias"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val RSA_MODE = "RSA/ECB/PKCS1Padding"
    private const val RSA_ALGORITHM = "RSA"
    private const val RSA_KEY_SIZE = 2048

    /**
     * Encrypts data using a key stored in the Android Keystore (API 18+).
     */
    fun encryptData(context: Context, data: ByteArray): String? {
        return try {
            val encryptedData: ByteArray

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 23+: Use AES key stored in Keystore
                val secretKey = getSecretKey()
                if (secretKey == null) {
                    throw Exception("SecretKey not found")
                }
                val cipher = Cipher.getInstance(AES_MODE)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val encryptedBytes = cipher.doFinal(data)

                // Combine IV and encrypted data
                encryptedData = iv + encryptedBytes
            } else {
                // API 18-22: Use RSA to encrypt AES key
                val publicKey = getPublicKey(context)
                if (publicKey == null) {
                    throw Exception("PublicKey not found")
                }
                val secretKey = generateAESKey()

                // Encrypt the data with the AES key
                val aesCipher = Cipher.getInstance(AES_MODE)
                val iv = ByteArray(12)
                SecureRandom().nextBytes(iv)
                val gcmSpec = GCMParameterSpec(128, iv)
                aesCipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
                val encryptedDataBytes = aesCipher.doFinal(data)

                // Encrypt the AES key with RSA
                val rsaCipher = Cipher.getInstance(RSA_MODE)
                rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey)
                val encryptedSecretKey = rsaCipher.doFinal(secretKey.encoded)

                // Combine encrypted AES key, IV, y encrypted data
                encryptedData = encryptedSecretKey + iv + encryptedDataBytes
            }

            Base64.encodeToString(encryptedData, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decrypts data using a key stored in the Android Keystore.
     */
    fun decryptData(context: Context, encryptedData: String): ByteArray? {
        return try {
            val decodedData = Base64.decode(encryptedData, Base64.DEFAULT)
            val decryptedData: ByteArray

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 23+: Use AES key stored in Keystore
                val secretKey = getSecretKey()
                if (secretKey == null) {
                    throw Exception("SecretKey not found")
                }
                val cipher = Cipher.getInstance(AES_MODE)
                val iv = decodedData.copyOfRange(0, 12)
                val encryptedBytes = decodedData.copyOfRange(12, decodedData.size)
                val gcmSpec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
                decryptedData = cipher.doFinal(encryptedBytes)
            } else {
                // API 18-22: Use RSA to decrypt AES key
                val privateKey = getPrivateKey(context)
                if (privateKey == null) {
                    throw Exception("PrivateKey not found")
                }

                // Extract the encrypted AES key, IV, y encrypted data
                val encryptedSecretKey = decodedData.copyOfRange(0, 256) // RSA key size is 2048 bits = 256 bytes
                val iv = decodedData.copyOfRange(256, 268)
                val encryptedDataBytes = decodedData.copyOfRange(268, decodedData.size)

                // Decrypt the AES key with RSA private key
                val rsaCipher = Cipher.getInstance(RSA_MODE)
                rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
                val secretKeyBytes = rsaCipher.doFinal(encryptedSecretKey)
                val secretKey = SecretKeySpec(secretKeyBytes, "AES")

                // Decrypt the data with the AES key
                val aesCipher = Cipher.getInstance(AES_MODE)
                val gcmSpec = GCMParameterSpec(128, iv)
                aesCipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
                decryptedData = aesCipher.doFinal(encryptedDataBytes)
            }

            decryptedData
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Genera o recupera un par de claves desde el Android Keystore.
     * Para API >= 23, usa KeyGenParameterSpec.
     * Para API < 23, usa KeyPairGeneratorSpec.
     */
    fun getOrCreateKeyPair(context: Context): KeyPair? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

            if (keyStore.containsAlias(KEY_ALIAS)) {
                val privateKey = getPrivateKey(context)
                val publicKey = getPublicKey(context)
                if (privateKey != null && publicKey != null) {
                    KeyPair(publicKey, privateKey)
                } else {
                    null
                }
            } else {
                generateKeyPair(context)
                val privateKey = getPrivateKey(context)
                val publicKey = getPublicKey(context)
                if (privateKey != null && publicKey != null) {
                    KeyPair(publicKey, privateKey)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Genera o recupera una clave secreta desde el Android Keystore.
     */
    private fun getSecretKey(): SecretKey? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

                if (keyStore.containsAlias(KEY_ALIAS)) {
                    val secretKeyEntry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
                    secretKeyEntry.secretKey
                } else {
                    val keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
                    )

                    val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()

                    keyGenerator.init(keyGenParameterSpec)
                    keyGenerator.generateKey()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }
    }

    /**
     * Genera un par de claves RSA y las almacena en el Keystore (para API 18-22).
     */
    private fun generateKeyPair(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val start = Calendar.getInstance()
                val end = Calendar.getInstance()
                end.add(Calendar.YEAR, 30)

                @Suppress("DEPRECATION")
                val spec = KeyPairGeneratorSpec.Builder(context)
                    .setAlias(KEY_ALIAS)
                    .setSubject(X500Principal("CN=$KEY_ALIAS"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .setKeySize(RSA_KEY_SIZE)
                    .build()

                val kpg = KeyPairGenerator.getInstance(RSA_ALGORITHM, ANDROID_KEYSTORE)
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }
        }
    }

    /**
     * Recupera la clave pública desde el Keystore.
     */
    private fun getPublicKey(context: Context): PublicKey? {
        generateKeyPair(context)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        return entry?.certificate?.publicKey
    }

    /**
     * Recupera la clave privada desde el Keystore.
     */
    private fun getPrivateKey(context: Context): PrivateKey? {
        generateKeyPair(context)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        return entry?.privateKey
    }

    /**
     * Genera una clave AES aleatoria (para API 18-22).
     */
    private fun generateAESKey(): SecretKey {
        val keyBytes = ByteArray(16) // 128 bits
        SecureRandom().nextBytes(keyBytes)
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Elimina el par de claves desde el Keystore.
     */
    fun deleteKeyPair() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

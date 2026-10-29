package edu.ucsd.sysnet.cluetoothscanner.utils

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

object EncryptionUtils {

    const val PUBLIC_KEY_BASE64 = "C3MTLw8eWNw//LyV8EcI0MBh5OQu2WS9x2CkOIHL+k8="

    private val lazySodium = LazySodiumAndroid(SodiumAndroid())

    /**
     * Encrypts data using libsodium's sealed box (anonymous public key encryption)
     * This uses Curve25519 for key exchange and ChaCha20-Poly1305 for encryption
     *
     * @param data The data to encrypt
     * @param publicKeyBase64 The recipient's public key as a Base64 string
     * @return The encrypted data with nonce prepended
     * @throws IllegalArgumentException if the public key is invalid
     * @throws RuntimeException if encryption fails
     */
    fun encryptData(data: ByteArray, publicKeyBase64: String = PUBLIC_KEY_BASE64): ByteArray {
        if (!validateKey(publicKeyBase64)) {
            throw IllegalArgumentException("Invalid public key")
        }

        val publicKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)

        val encryptedBytes = ByteArray(data.size + 48)
        val success = lazySodium.cryptoBoxSeal(encryptedBytes, data, data.size.toLong(), publicKeyBytes)

        if (!success) {
            throw RuntimeException("Encryption failed")
        }

        return encryptedBytes
    }


    /**
     * Validates that a public key is in the correct format
     *
     * @param publicKeyBase64 The public key as a Base64 string
     * @return true if the key is valid, false otherwise
     */
    fun validateKey(publicKeyBase64: String): Boolean {
        return try {
            val decoded = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            decoded.size == 32 // 32 bytes for Curve25519 public key
        } catch (_: Exception) {
            false
        }
    }
}

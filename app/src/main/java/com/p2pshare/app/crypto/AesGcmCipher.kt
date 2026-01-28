package com.p2pshare.app.crypto

import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM cipher implementation for authenticated encryption.
 * 
 * This class provides secure encryption and decryption using AES in Galois/Counter Mode (GCM).
 * GCM provides both confidentiality and authenticity, making it ideal for P2P file sharing
 * where we need to ensure data hasn't been tampered with during transmission.
 * 
 * Key features:
 * - AES-256 encryption for strong confidentiality
 * - GCM mode for built-in authentication
 * - Random IV generation for each encryption
 * - Constant-time operations where possible
 */
class AesGcmCipher {
    
    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12 // 96 bits - recommended for GCM
        private const val GCM_TAG_LENGTH = 16 // 128 bits - authentication tag length
        private const val KEY_LENGTH = 32 // 256 bits for AES-256
    }
    
    private val secureRandom = SecureRandom()
    
    /**
     * Encrypts data using AES-256-GCM with a randomly generated IV.
     * 
     * The IV is prepended to the ciphertext, so the output format is:
     * [IV (12 bytes)] + [Ciphertext + Authentication Tag]
     * 
     * @param plaintext The data to encrypt
     * @param key The 256-bit (32-byte) encryption key
     * @return Encrypted data with IV prepended
     * @throws IllegalArgumentException if key length is incorrect
     * @throws NoSuchAlgorithmException if AES-GCM is not available
     * @throws NoSuchPaddingException if padding is not available
     * @throws InvalidKeyException if the key is invalid
     * @throws InvalidAlgorithmParameterException if GCM parameters are invalid
     */
    @Throws(
        IllegalArgumentException::class,
        NoSuchAlgorithmException::class,
        NoSuchPaddingException::class,
        InvalidKeyException::class,
        InvalidAlgorithmParameterException::class
    )
    fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_LENGTH) { 
            "Key must be exactly $KEY_LENGTH bytes (256 bits) for AES-256" 
        }
        require(plaintext.isNotEmpty()) { 
            "Plaintext cannot be empty" 
        }
        
        // Generate random IV for this encryption
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        
        return encrypt(plaintext, key, iv)
    }
    
    /**
     * Encrypts data using AES-256-GCM with a provided IV.
     * 
     * This method allows specifying the IV, which is useful for testing
     * or when the IV needs to be derived deterministically.
     * 
     * @param plaintext The data to encrypt
     * @param key The 256-bit (32-byte) encryption key
     * @param iv The 96-bit (12-byte) initialization vector
     * @return Encrypted data with IV prepended
     */
    @Throws(
        IllegalArgumentException::class,
        NoSuchAlgorithmException::class,
        NoSuchPaddingException::class,
        InvalidKeyException::class,
        InvalidAlgorithmParameterException::class
    )
    fun encrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == KEY_LENGTH) { 
            "Key must be exactly $KEY_LENGTH bytes (256 bits) for AES-256" 
        }
        require(iv.size == GCM_IV_LENGTH) { 
            "IV must be exactly $GCM_IV_LENGTH bytes (96 bits) for GCM" 
        }
        require(plaintext.isNotEmpty()) { 
            "Plaintext cannot be empty" 
        }
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv) // Tag length in bits
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext)
        
        // Prepend IV to ciphertext for transmission
        return iv + ciphertext
    }
    
    /**
     * Decrypts data that was encrypted with AES-256-GCM.
     * 
     * Expects the input format: [IV (12 bytes)] + [Ciphertext + Authentication Tag]
     * 
     * @param encryptedData The encrypted data with IV prepended
     * @param key The 256-bit (32-byte) decryption key
     * @return Decrypted plaintext data
     * @throws IllegalArgumentException if input format or key length is incorrect
     * @throws AEADBadTagException if authentication fails (data was tampered with)
     * @throws NoSuchAlgorithmException if AES-GCM is not available
     * @throws NoSuchPaddingException if padding is not available
     * @throws InvalidKeyException if the key is invalid
     * @throws InvalidAlgorithmParameterException if GCM parameters are invalid
     */
    @Throws(
        IllegalArgumentException::class,
        AEADBadTagException::class,
        NoSuchAlgorithmException::class,
        NoSuchPaddingException::class,
        InvalidKeyException::class,
        InvalidAlgorithmParameterException::class
    )
    fun decrypt(encryptedData: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_LENGTH) { 
            "Key must be exactly $KEY_LENGTH bytes (256 bits) for AES-256" 
        }
        require(encryptedData.size > GCM_IV_LENGTH + GCM_TAG_LENGTH) { 
            "Encrypted data too short: must contain IV (${GCM_IV_LENGTH} bytes) + ciphertext + tag (${GCM_TAG_LENGTH} bytes)" 
        }
        
        // Extract IV from the beginning of encrypted data
        val iv = encryptedData.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = encryptedData.sliceArray(GCM_IV_LENGTH until encryptedData.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv) // Tag length in bits
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        
        // This will throw AEADBadTagException if authentication fails
        return cipher.doFinal(ciphertext)
    }
    
    /**
     * Generates a cryptographically secure random key for AES-256.
     * 
     * @return A new 256-bit (32-byte) random key
     */
    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_LENGTH)
        secureRandom.nextBytes(key)
        return key
    }
    
    /**
     * Generates a cryptographically secure random IV for GCM.
     * 
     * @return A new 96-bit (12-byte) random IV
     */
    fun generateIv(): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        return iv
    }
    
    /**
     * Validates that a key is the correct length for AES-256.
     * 
     * @param key The key to validate
     * @return true if the key is valid for AES-256, false otherwise
     */
    fun isValidKey(key: ByteArray): Boolean {
        return key.size == KEY_LENGTH
    }
    
    /**
     * Validates that an IV is the correct length for GCM.
     * 
     * @param iv The IV to validate
     * @return true if the IV is valid for GCM, false otherwise
     */
    fun isValidIv(iv: ByteArray): Boolean {
        return iv.size == GCM_IV_LENGTH
    }
    
    /**
     * Securely clears a key from memory.
     * 
     * @param key The key to clear
     */
    fun clearKey(key: ByteArray?) {
        key?.fill(0)
    }
    
    /**
     * Gets the expected key length for AES-256.
     * 
     * @return Key length in bytes (32)
     */
    fun getKeyLength(): Int = KEY_LENGTH
    
    /**
     * Gets the IV length for GCM mode.
     * 
     * @return IV length in bytes (12)
     */
    fun getIvLength(): Int = GCM_IV_LENGTH
    
    /**
     * Gets the authentication tag length for GCM mode.
     * 
     * @return Tag length in bytes (16)
     */
    fun getTagLength(): Int = GCM_TAG_LENGTH
}
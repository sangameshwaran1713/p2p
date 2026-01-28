package com.p2pshare.app.crypto

import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * HKDF (HMAC-based Key Derivation Function) implementation using SHA-256.
 * 
 * This class implements RFC 5869 HKDF-SHA256 for deriving cryptographic keys
 * from shared secrets. HKDF is essential for converting the raw output of
 * ECDH key agreement into usable encryption keys.
 * 
 * HKDF consists of two phases:
 * 1. Extract: Create a pseudorandom key from the input key material
 * 2. Expand: Generate the desired amount of key material from the pseudorandom key
 */
class Hkdf {
    
    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HASH_LENGTH = 32 // SHA-256 produces 32-byte hashes
    }
    
    /**
     * Derives cryptographic key material using HKDF-SHA256.
     * 
     * This is the main HKDF function that combines extract and expand phases
     * to produce the requested amount of key material from input key material.
     * 
     * @param ikm Input Key Material (e.g., ECDH shared secret)
     * @param salt Optional salt value (can be empty). Salt should be random but can be public
     * @param info Optional context and application specific information (can be empty)
     * @param length Length of output key material in bytes (must be <= 255 * HashLen)
     * @return Derived key material of the requested length
     * @throws IllegalArgumentException if length is too large or negative
     * @throws NoSuchAlgorithmException if HMAC-SHA256 is not available
     * @throws InvalidKeyException if the key material is invalid
     */
    @Throws(IllegalArgumentException::class, NoSuchAlgorithmException::class, InvalidKeyException::class)
    fun deriveKey(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length >= 0) { "Length must be non-negative" }
        require(length <= 255 * HASH_LENGTH) { 
            "Length too large: maximum is ${255 * HASH_LENGTH} bytes" 
        }
        
        if (length == 0) {
            return ByteArray(0)
        }
        
        // Step 1: Extract - derive a pseudorandom key
        val prk = extract(salt, ikm)
        
        // Step 2: Expand - generate the desired amount of key material
        return expand(prk, info, length)
    }
    
    /**
     * HKDF Extract phase: derives a pseudorandom key from input key material.
     * 
     * PRK = HMAC-SHA256(salt, IKM)
     * 
     * @param salt Salt value (if empty, uses zero-filled array of hash length)
     * @param ikm Input Key Material
     * @return Pseudorandom Key (PRK) of hash length
     */
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt.isEmpty()) {
            ByteArray(HASH_LENGTH) // Zero-filled salt if none provided
        } else {
            salt
        }
        
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(actualSalt, HMAC_ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(ikm)
    }
    
    /**
     * HKDF Expand phase: generates key material from pseudorandom key.
     * 
     * The expand phase generates the output key material by repeatedly
     * applying HMAC to build up the desired length of key material.
     * 
     * @param prk Pseudorandom Key from extract phase
     * @param info Context and application specific information
     * @param length Desired length of output key material
     * @return Expanded key material of requested length
     */
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(prk, HMAC_ALGORITHM)
        mac.init(keySpec)
        
        val iterations = ceil(length.toDouble() / HASH_LENGTH).toInt()
        val okm = ByteArray(length)
        var previousT = ByteArray(0)
        
        for (i in 1..iterations) {
            mac.reset()
            mac.update(previousT)
            mac.update(info)
            mac.update(i.toByte())
            
            val t = mac.doFinal()
            val copyLength = minOf(t.size, length - (i - 1) * HASH_LENGTH)
            System.arraycopy(t, 0, okm, (i - 1) * HASH_LENGTH, copyLength)
            
            previousT = t
        }
        
        return okm
    }
    
    /**
     * Convenience method for deriving AES-256 keys (32 bytes).
     * 
     * @param sharedSecret The shared secret from ECDH key agreement
     * @param salt Optional salt (can be empty)
     * @param context Context information (e.g., "P2P-FileShare-AES")
     * @return 32-byte key suitable for AES-256
     */
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    fun deriveAesKey(sharedSecret: ByteArray, salt: ByteArray = ByteArray(0), context: String = ""): ByteArray {
        return deriveKey(sharedSecret, salt, context.toByteArray(Charsets.UTF_8), 32)
    }
    
    /**
     * Convenience method for deriving HMAC keys (32 bytes).
     * 
     * @param sharedSecret The shared secret from ECDH key agreement
     * @param salt Optional salt (can be empty)
     * @param context Context information (e.g., "P2P-FileShare-HMAC")
     * @return 32-byte key suitable for HMAC-SHA256
     */
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    fun deriveHmacKey(sharedSecret: ByteArray, salt: ByteArray = ByteArray(0), context: String = ""): ByteArray {
        return deriveKey(sharedSecret, salt, context.toByteArray(Charsets.UTF_8), 32)
    }
    
    /**
     * Securely clears sensitive key material from memory.
     * 
     * @param keyMaterial The key material to clear
     */
    fun clearKeyMaterial(keyMaterial: ByteArray?) {
        keyMaterial?.fill(0)
    }
}
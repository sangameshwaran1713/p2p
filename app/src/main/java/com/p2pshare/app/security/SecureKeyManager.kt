package com.p2pshare.app.security

import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * Secure key management utility for handling cryptographic keys safely.
 * 
 * This class provides:
 * - Secure key storage and cleanup
 * - Memory clearing for sensitive data
 * - Key lifecycle management
 * - Automatic cleanup on app termination
 */
class SecureKeyManager private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: SecureKeyManager? = null
        
        fun getInstance(): SecureKeyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureKeyManager().also { INSTANCE = it }
            }
        }
    }

    // Secure storage for active keys
    private val activeKeys = ConcurrentHashMap<String, SecureKeyHolder>()
    private val secureRandom = SecureRandom()

    /**
     * Secure key holder that manages key lifecycle.
     */
    private class SecureKeyHolder(
        val key: Any, // Can be PrivateKey, PublicKey, SecretKey, or ByteArray
        val createdAt: Long = System.currentTimeMillis(),
        var lastAccessed: Long = System.currentTimeMillis()
    ) {
        fun updateAccess() {
            lastAccessed = System.currentTimeMillis()
        }
        
        fun isExpired(maxAgeMs: Long): Boolean {
            return System.currentTimeMillis() - createdAt > maxAgeMs
        }
        
        fun isStale(maxIdleMs: Long): Boolean {
            return System.currentTimeMillis() - lastAccessed > maxIdleMs
        }
    }

    init {
        // Register shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(Thread {
            clearAllKeys()
        })
    }

    /**
     * Stores a private key securely with automatic cleanup.
     */
    fun storePrivateKey(keyId: String, privateKey: PrivateKey): String {
        val holder = SecureKeyHolder(privateKey)
        activeKeys[keyId] = holder
        return keyId
    }

    /**
     * Stores a public key securely.
     */
    fun storePublicKey(keyId: String, publicKey: PublicKey): String {
        val holder = SecureKeyHolder(publicKey)
        activeKeys[keyId] = holder
        return keyId
    }

    /**
     * Stores a secret key securely with automatic cleanup.
     */
    fun storeSecretKey(keyId: String, secretKey: SecretKey): String {
        val holder = SecureKeyHolder(secretKey)
        activeKeys[keyId] = holder
        return keyId
    }

    /**
     * Stores sensitive byte array data securely.
     */
    fun storeSecureData(keyId: String, data: ByteArray): String {
        val holder = SecureKeyHolder(data.clone()) // Store a copy
        activeKeys[keyId] = holder
        return keyId
    }

    /**
     * Retrieves a private key by ID.
     */
    fun getPrivateKey(keyId: String): PrivateKey? {
        val holder = activeKeys[keyId] ?: return null
        holder.updateAccess()
        return holder.key as? PrivateKey
    }

    /**
     * Retrieves a public key by ID.
     */
    fun getPublicKey(keyId: String): PublicKey? {
        val holder = activeKeys[keyId] ?: return null
        holder.updateAccess()
        return holder.key as? PublicKey
    }

    /**
     * Retrieves a secret key by ID.
     */
    fun getSecretKey(keyId: String): SecretKey? {
        val holder = activeKeys[keyId] ?: return null
        holder.updateAccess()
        return holder.key as? SecretKey
    }

    /**
     * Retrieves secure byte array data by ID.
     */
    fun getSecureData(keyId: String): ByteArray? {
        val holder = activeKeys[keyId] ?: return null
        holder.updateAccess()
        return (holder.key as? ByteArray)?.clone() // Return a copy
    }

    /**
     * Clears a specific key from memory.
     */
    fun clearKey(keyId: String) {
        val holder = activeKeys.remove(keyId)
        holder?.let { securelyWipeKey(it.key) }
    }

    /**
     * Clears all keys from memory.
     */
    fun clearAllKeys() {
        val keys = activeKeys.keys.toList()
        keys.forEach { keyId ->
            clearKey(keyId)
        }
    }

    /**
     * Clears expired keys based on maximum age.
     */
    fun clearExpiredKeys(maxAgeMs: Long = 30 * 60 * 1000L) { // Default 30 minutes
        val expiredKeys = activeKeys.entries
            .filter { it.value.isExpired(maxAgeMs) }
            .map { it.key }
        
        expiredKeys.forEach { keyId ->
            clearKey(keyId)
        }
    }

    /**
     * Clears stale keys based on last access time.
     */
    fun clearStaleKeys(maxIdleMs: Long = 10 * 60 * 1000L) { // Default 10 minutes
        val staleKeys = activeKeys.entries
            .filter { it.value.isStale(maxIdleMs) }
            .map { it.key }
        
        staleKeys.forEach { keyId ->
            clearKey(keyId)
        }
    }

    /**
     * Securely wipes a key from memory.
     */
    private fun securelyWipeKey(key: Any) {
        when (key) {
            is ByteArray -> {
                // Overwrite with random data multiple times
                repeat(3) {
                    secureRandom.nextBytes(key)
                }
                // Final overwrite with zeros
                key.fill(0)
            }
            is PrivateKey -> {
                // For private keys, we can't directly wipe the internal data
                // but we can try to clear any accessible byte representations
                try {
                    val encoded = key.encoded
                    if (encoded != null) {
                        repeat(3) {
                            secureRandom.nextBytes(encoded)
                        }
                        encoded.fill(0)
                    }
                } catch (e: Exception) {
                    // Some keys may not support encoding, ignore
                }
            }
            // PublicKey and SecretKey don't contain sensitive data that needs wiping
            // or may not support direct memory access
        }
    }

    /**
     * Generates a secure random key ID.
     */
    fun generateKeyId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Gets statistics about stored keys.
     */
    fun getKeyStatistics(): KeyStatistics {
        val now = System.currentTimeMillis()
        var privateKeyCount = 0
        var publicKeyCount = 0
        var secretKeyCount = 0
        var dataCount = 0
        var oldestKey = now
        var newestKey = 0L

        activeKeys.values.forEach { holder ->
            when (holder.key) {
                is PrivateKey -> privateKeyCount++
                is PublicKey -> publicKeyCount++
                is SecretKey -> secretKeyCount++
                is ByteArray -> dataCount++
            }
            
            if (holder.createdAt < oldestKey) {
                oldestKey = holder.createdAt
            }
            if (holder.createdAt > newestKey) {
                newestKey = holder.createdAt
            }
        }

        return KeyStatistics(
            totalKeys = activeKeys.size,
            privateKeys = privateKeyCount,
            publicKeys = publicKeyCount,
            secretKeys = secretKeyCount,
            secureData = dataCount,
            oldestKeyAge = if (activeKeys.isNotEmpty()) now - oldestKey else 0L,
            newestKeyAge = if (activeKeys.isNotEmpty()) now - newestKey else 0L
        )
    }

    /**
     * Key storage statistics.
     */
    data class KeyStatistics(
        val totalKeys: Int,
        val privateKeys: Int,
        val publicKeys: Int,
        val secretKeys: Int,
        val secureData: Int,
        val oldestKeyAge: Long,
        val newestKeyAge: Long
    )

    /**
     * Performs maintenance on stored keys.
     */
    fun performMaintenance() {
        clearExpiredKeys()
        clearStaleKeys()
    }

    /**
     * Checks if a key exists.
     */
    fun hasKey(keyId: String): Boolean {
        return activeKeys.containsKey(keyId)
    }

    /**
     * Gets the age of a key in milliseconds.
     */
    fun getKeyAge(keyId: String): Long? {
        val holder = activeKeys[keyId] ?: return null
        return System.currentTimeMillis() - holder.createdAt
    }

    /**
     * Gets the time since last access for a key.
     */
    fun getKeyIdleTime(keyId: String): Long? {
        val holder = activeKeys[keyId] ?: return null
        return System.currentTimeMillis() - holder.lastAccessed
    }

    /**
     * Creates a secure session for temporary key storage.
     */
    fun createSecureSession(): SecureSession {
        return SecureSession(this)
    }

    /**
     * Secure session for managing temporary keys with automatic cleanup.
     */
    class SecureSession(private val keyManager: SecureKeyManager) {
        private val sessionKeys = mutableSetOf<String>()
        private var isActive = true

        fun storePrivateKey(privateKey: PrivateKey): String {
            checkActive()
            val keyId = keyManager.generateKeyId()
            keyManager.storePrivateKey(keyId, privateKey)
            sessionKeys.add(keyId)
            return keyId
        }

        fun storeSecretKey(secretKey: SecretKey): String {
            checkActive()
            val keyId = keyManager.generateKeyId()
            keyManager.storeSecretKey(keyId, secretKey)
            sessionKeys.add(keyId)
            return keyId
        }

        fun storeSecureData(data: ByteArray): String {
            checkActive()
            val keyId = keyManager.generateKeyId()
            keyManager.storeSecureData(keyId, data)
            sessionKeys.add(keyId)
            return keyId
        }

        fun getPrivateKey(keyId: String): PrivateKey? {
            checkActive()
            return if (keyId in sessionKeys) keyManager.getPrivateKey(keyId) else null
        }

        fun getSecretKey(keyId: String): SecretKey? {
            checkActive()
            return if (keyId in sessionKeys) keyManager.getSecretKey(keyId) else null
        }

        fun getSecureData(keyId: String): ByteArray? {
            checkActive()
            return if (keyId in sessionKeys) keyManager.getSecureData(keyId) else null
        }

        fun close() {
            if (isActive) {
                sessionKeys.forEach { keyId ->
                    keyManager.clearKey(keyId)
                }
                sessionKeys.clear()
                isActive = false
            }
        }

        private fun checkActive() {
            if (!isActive) {
                throw IllegalStateException("SecureSession has been closed")
            }
        }

        protected fun finalize() {
            close()
        }
    }
}
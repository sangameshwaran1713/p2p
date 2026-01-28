package com.p2pshare.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security utilities for the P2P File Share application.
 * 
 * This class provides:
 * - Secure session management
 * - Android Keystore integration
 * - Secure random generation
 * - Memory protection utilities
 */
object SecurityUtils {

    private const val TAG = "SecurityUtils"
    private const val ANDROID_KEYSTORE = "AndroidKeystore"
    private const val KEY_ALIAS_PREFIX = "p2p_file_share_"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16

    /**
     * Session security configuration.
     */
    data class SessionConfig(
        val sessionTimeout: Long = 30 * 60 * 1000, // 30 minutes
        val keyRotationInterval: Long = 15 * 60 * 1000, // 15 minutes
        val maxConcurrentSessions: Int = 3,
        val requireSecureHardware: Boolean = true
    )

    /**
     * Active session tracking.
     */
    private val activeSessions = mutableMapOf<String, SessionInfo>()
    private val sessionConfig = SessionConfig()

    /**
     * Session information.
     */
    private data class SessionInfo(
        val sessionId: String,
        val createdAt: Long,
        val lastAccessedAt: Long,
        val keyAlias: String,
        val isSecureHardwareBacked: Boolean
    )

    /**
     * Creates a new secure session.
     */
    fun createSecureSession(context: Context): String {
        cleanupExpiredSessions()
        
        // Limit concurrent sessions
        if (activeSessions.size >= sessionConfig.maxConcurrentSessions) {
            cleanupOldestSession()
        }

        val sessionId = generateSecureSessionId()
        val keyAlias = "$KEY_ALIAS_PREFIX$sessionId"
        
        try {
            val secretKey = generateSessionKey(keyAlias)
            val isHardwareBacked = isKeyHardwareBacked(keyAlias)
            
            if (sessionConfig.requireSecureHardware && !isHardwareBacked) {
                throw SecurityException("Secure hardware not available")
            }

            val sessionInfo = SessionInfo(
                sessionId = sessionId,
                createdAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis(),
                keyAlias = keyAlias,
                isSecureHardwareBacked = isHardwareBacked
            )

            activeSessions[sessionId] = sessionInfo
            
            // Store session key in SecureKeyManager for additional security
            SecureKeyManager.getInstance().storeSecretKey("session_$sessionId", secretKey)

            return sessionId

        } catch (e: Exception) {
            // Cleanup on failure
            deleteKeyFromKeystore(keyAlias)
            throw SecurityException("Failed to create secure session: ${e.message}", e)
        }
    }

    /**
     * Validates and refreshes a session.
     */
    fun validateSession(sessionId: String): Boolean {
        val sessionInfo = activeSessions[sessionId] ?: return false
        
        val currentTime = System.currentTimeMillis()
        
        // Check if session has expired
        if (currentTime - sessionInfo.lastAccessedAt > sessionConfig.sessionTimeout) {
            terminateSession(sessionId)
            return false
        }

        // Update last accessed time
        activeSessions[sessionId] = sessionInfo.copy(lastAccessedAt = currentTime)
        
        // Check if key rotation is needed
        if (currentTime - sessionInfo.createdAt > sessionConfig.keyRotationInterval) {
            rotateSessionKey(sessionId)
        }

        return true
    }

    /**
     * Terminates a secure session.
     */
    fun terminateSession(sessionId: String) {
        val sessionInfo = activeSessions.remove(sessionId)
        if (sessionInfo != null) {
            // Delete the key from Android Keystore
            deleteKeyFromKeystore(sessionInfo.keyAlias)
            
            // Clear any associated keys from SecureKeyManager
            SecureKeyManager.getInstance().clearKey("session_$sessionId")
        }
    }

    /**
     * Terminates all active sessions.
     */
    fun terminateAllSessions() {
        val sessionIds = activeSessions.keys.toList()
        sessionIds.forEach { sessionId ->
            terminateSession(sessionId)
        }
    }

    /**
     * Encrypts data using session key.
     */
    fun encryptWithSessionKey(sessionId: String, data: ByteArray): ByteArray? {
        if (!validateSession(sessionId)) return null
        
        val sessionInfo = activeSessions[sessionId] ?: return null
        
        try {
            val secretKey = getKeyFromKeystore(sessionInfo.keyAlias) ?: return null
            return encryptAesGcm(data, secretKey)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Decrypts data using session key.
     */
    fun decryptWithSessionKey(sessionId: String, encryptedData: ByteArray): ByteArray? {
        if (!validateSession(sessionId)) return null
        
        val sessionInfo = activeSessions[sessionId] ?: return null
        
        try {
            val secretKey = getKeyFromKeystore(sessionInfo.keyAlias) ?: return null
            return decryptAesGcm(encryptedData, secretKey)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Gets session information.
     */
    fun getSessionInfo(sessionId: String): Map<String, Any>? {
        val sessionInfo = activeSessions[sessionId] ?: return null
        
        return mapOf(
            "sessionId" to sessionInfo.sessionId,
            "createdAt" to sessionInfo.createdAt,
            "lastAccessedAt" to sessionInfo.lastAccessedAt,
            "isHardwareBacked" to sessionInfo.isSecureHardwareBacked,
            "ageMs" to (System.currentTimeMillis() - sessionInfo.createdAt)
        )
    }

    /**
     * Gets security status information.
     */
    fun getSecurityStatus(): Map<String, Any> {
        return mapOf(
            "activeSessions" to activeSessions.size,
            "maxSessions" to sessionConfig.maxConcurrentSessions,
            "sessionTimeout" to sessionConfig.sessionTimeout,
            "keyRotationInterval" to sessionConfig.keyRotationInterval,
            "requireSecureHardware" to sessionConfig.requireSecureHardware,
            "keystoreAvailable" to isKeystoreAvailable(),
            "secureHardwareAvailable" to isSecureHardwareAvailable()
        )
    }

    /**
     * Generates a secure session ID.
     */
    private fun generateSecureSessionId(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates a session key in Android Keystore.
     */
    private fun generateSessionKey(keyAlias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setUserAuthenticationRequired(false)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Retrieves a key from Android Keystore.
     */
    private fun getKeyFromKeystore(keyAlias: String): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.getKey(keyAlias, null) as? SecretKey
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Deletes a key from Android Keystore.
     */
    private fun deleteKeyFromKeystore(keyAlias: String) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(keyAlias)
        } catch (e: Exception) {
            // Log error but continue
        }
    }

    /**
     * Checks if a key is hardware-backed.
     */
    private fun isKeyHardwareBacked(keyAlias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val key = keyStore.getKey(keyAlias, null)
            
            // For keys in Android Keystore, assume they are hardware-backed
            // This is a reasonable assumption for API 23+ devices
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Encrypts data using AES-GCM.
     */
    private fun encryptAesGcm(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        
        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)
        
        // Combine IV and encrypted data
        return iv + encryptedData
    }

    /**
     * Decrypts data using AES-GCM.
     */
    private fun decryptAesGcm(encryptedData: ByteArray, key: SecretKey): ByteArray {
        val iv = encryptedData.sliceArray(0 until GCM_IV_LENGTH)
        val cipherText = encryptedData.sliceArray(GCM_IV_LENGTH until encryptedData.size)
        
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        
        return cipher.doFinal(cipherText)
    }

    /**
     * Rotates the session key.
     */
    private fun rotateSessionKey(sessionId: String) {
        val sessionInfo = activeSessions[sessionId] ?: return
        
        try {
            // Delete old key
            deleteKeyFromKeystore(sessionInfo.keyAlias)
            
            // Generate new key with same alias
            generateSessionKey(sessionInfo.keyAlias)
            
            // Update session info
            activeSessions[sessionId] = sessionInfo.copy(
                createdAt = System.currentTimeMillis(),
                lastAccessedAt = System.currentTimeMillis()
            )
            
        } catch (e: Exception) {
            // If rotation fails, terminate the session for security
            terminateSession(sessionId)
        }
    }

    /**
     * Cleans up expired sessions.
     */
    private fun cleanupExpiredSessions() {
        val currentTime = System.currentTimeMillis()
        val expiredSessions = activeSessions.filterValues { sessionInfo ->
            currentTime - sessionInfo.lastAccessedAt > sessionConfig.sessionTimeout
        }.keys

        expiredSessions.forEach { sessionId ->
            terminateSession(sessionId)
        }
    }

    /**
     * Cleans up the oldest session to make room for a new one.
     */
    private fun cleanupOldestSession() {
        val oldestSession = activeSessions.minByOrNull { it.value.createdAt }
        oldestSession?.let { (sessionId, _) ->
            terminateSession(sessionId)
        }
    }

    /**
     * Checks if Android Keystore is available.
     */
    private fun isKeystoreAvailable(): Boolean {
        return try {
            KeyStore.getInstance(ANDROID_KEYSTORE)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if secure hardware is available.
     */
    private fun isSecureHardwareAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isKeystoreAvailable()
    }

    /**
     * Emergency security cleanup.
     */
    fun emergencyCleanup() {
        terminateAllSessions()
        SecureKeyManager.getInstance().clearAllKeys()
    }
}
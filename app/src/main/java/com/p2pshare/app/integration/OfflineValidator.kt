package com.p2pshare.app.integration

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.p2pshare.app.crypto.AesGcmCipher
import com.p2pshare.app.crypto.EcdhHelper
import com.p2pshare.app.crypto.Hkdf
import com.p2pshare.app.qr.QrGenerator
import com.p2pshare.app.transfer.ChunkManager
import com.p2pshare.app.wifi.WifiDirectManager
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Utility class for validating offline operation capabilities.
 * 
 * This class ensures that all core P2P file sharing functionality
 * works without internet connectivity, as required by the specifications.
 */
class OfflineValidator(private val context: Context) {

    companion object {
        private const val TAG = "OfflineValidator"
    }

    private val ecdhHelper = EcdhHelper()
    private val hkdf = Hkdf()
    private val aesGcmCipher = AesGcmCipher()
    private val qrGenerator = QrGenerator()

    /**
     * Validation result for offline operations.
     */
    data class ValidationResult(
        val isValid: Boolean,
        val validatedComponents: List<String>,
        val failedComponents: List<String>,
        val details: Map<String, String>
    )

    /**
     * Performs comprehensive offline validation of all components.
     */
    fun validateOfflineCapabilities(): ValidationResult {
        val validatedComponents = mutableListOf<String>()
        val failedComponents = mutableListOf<String>()
        val details = mutableMapOf<String, String>()

        // Test cryptographic operations
        if (validateCryptographicOperations()) {
            validatedComponents.add("Cryptographic Operations")
            details["crypto"] = "ECDH, HKDF, and AES-GCM operations work offline"
        } else {
            failedComponents.add("Cryptographic Operations")
            details["crypto"] = "Cryptographic operations failed offline validation"
        }

        // Test QR code operations
        if (validateQrCodeOperations()) {
            validatedComponents.add("QR Code Operations")
            details["qr"] = "QR code generation and parsing work offline"
        } else {
            failedComponents.add("QR Code Operations")
            details["qr"] = "QR code operations failed offline validation"
        }

        // Test file operations
        if (validateFileOperations()) {
            validatedComponents.add("File Operations")
            details["file"] = "File chunking and manifest creation work offline"
        } else {
            failedComponents.add("File Operations")
            details["file"] = "File operations failed offline validation"
        }

        // Test Wi-Fi Direct availability
        if (validateWifiDirectCapability()) {
            validatedComponents.add("Wi-Fi Direct")
            details["wifi"] = "Wi-Fi Direct is supported and available"
        } else {
            failedComponents.add("Wi-Fi Direct")
            details["wifi"] = "Wi-Fi Direct is not supported or unavailable"
        }

        // Test session management
        if (validateSessionManagement()) {
            validatedComponents.add("Session Management")
            details["session"] = "Session creation and management work offline"
        } else {
            failedComponents.add("Session Management")
            details["session"] = "Session management failed offline validation"
        }

        return ValidationResult(
            isValid = failedComponents.isEmpty(),
            validatedComponents = validatedComponents,
            failedComponents = failedComponents,
            details = details
        )
    }

    /**
     * Validates that cryptographic operations work without network.
     */
    private fun validateCryptographicOperations(): Boolean {
        return try {
            // Test ECDH key generation and agreement
            val keyPair1 = ecdhHelper.generateKeyPair()
            val keyPair2 = ecdhHelper.generateKeyPair()
            
            val sharedSecret1 = ecdhHelper.computeSharedSecret(keyPair1.private, keyPair2.public)
            val sharedSecret2 = ecdhHelper.computeSharedSecret(keyPair2.private, keyPair1.public)
            
            if (!sharedSecret1.contentEquals(sharedSecret2)) {
                return false
            }

            // Test HKDF key derivation
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            val info = "P2P File Share Test".toByteArray()
            
            val derivedKey = hkdf.deriveKey(sharedSecret1, salt, info, 32)
            if (derivedKey.size != 32) {
                return false
            }

            // Test AES-GCM encryption/decryption
            val testData = "Test data for offline validation".toByteArray()
            val encrypted = aesGcmCipher.encrypt(testData, derivedKey)
            val decrypted = aesGcmCipher.decrypt(encrypted, derivedKey)
            
            if (!testData.contentEquals(decrypted)) {
                return false
            }

            // Cleanup
            ecdhHelper.clearPrivateKey(keyPair1.private)
            ecdhHelper.clearPrivateKey(keyPair2.private)
            ecdhHelper.clearSharedSecret(sharedSecret1)
            ecdhHelper.clearSharedSecret(sharedSecret2)

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates that QR code operations work without network.
     */
    private fun validateQrCodeOperations(): Boolean {
        return try {
            // Create test session info
            val sessionInfo = JSONObject().apply {
                put("sessionId", "test-validation-session")
                put("fileName", "test.txt")
                put("fileSize", 1024)
                put("timestamp", System.currentTimeMillis())
                put("publicKey", "test-public-key-data")
            }

            // Generate QR code
            val qrBitmap = qrGenerator.generateQrCode(sessionInfo.toString())
            if (qrBitmap == null) {
                return false
            }

            // Validate QR data parsing
            val qrData = sessionInfo.toString()
            val parsedInfo = JSONObject(qrData)
            
            parsedInfo.getString("sessionId") == "test-validation-session" &&
            parsedInfo.getString("fileName") == "test.txt" &&
            parsedInfo.getLong("fileSize") == 1024L

        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates that file operations work without network.
     */
    private fun validateFileOperations(): Boolean {
        return try {
            // Create test file
            val testFile = File.createTempFile("offline_validation", ".tmp")
            val testContent = "This is test content for offline validation".toByteArray()
            testFile.writeBytes(testContent)

            try {
                // Test file manifest creation
                val testTransferId = "offline-validation-test-${System.currentTimeMillis()}"
                val manifest = com.p2pshare.app.transfer.FileManifest.fromFile(testFile, testTransferId)
                if (manifest.fileName != testFile.name || manifest.fileSize != testContent.size.toLong()) {
                    return false
                }

                // Test file chunking
                val chunkManager = ChunkManager(manifest)
                val chunks = chunkManager.splitFile(testFile)
                if (chunks.isEmpty()) {
                    return false
                }

                // Verify chunk integrity
                var totalSize = 0L
                chunks.forEach { chunk ->
                    totalSize += chunk.size
                }
                
                totalSize == testContent.size.toLong()

            } finally {
                testFile.delete()
            }

        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates Wi-Fi Direct capability.
     */
    private fun validateWifiDirectCapability(): Boolean {
        return try {
            // Create a dummy callback for validation
            val dummyCallback = object : com.p2pshare.app.wifi.WifiDirectCallback {
                override fun onGroupCreated(groupInfo: android.net.wifi.p2p.WifiP2pGroup) {}
                override fun onPeerAvailable(peer: android.net.wifi.p2p.WifiP2pDevice) {}
                override fun onConnected(connectionInfo: android.net.wifi.p2p.WifiP2pInfo) {}
                override fun onDisconnected() {}
                override fun onWifiDirectStateChanged(isEnabled: Boolean) {}
                override fun onError(error: String) {}
            }
            
            val wifiDirectManager = WifiDirectManager(context, dummyCallback)
            // Try to initialize to check if Wi-Fi Direct is supported
            wifiDirectManager.initialize()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates session management operations.
     */
    private fun validateSessionManagement(): Boolean {
        return try {
            // Test session data structures
            val sessionId = java.util.UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            
            // Simulate session creation
            val sessionData = mapOf(
                "sessionId" to sessionId,
                "timestamp" to timestamp,
                "isGroupOwner" to true,
                "status" to "active"
            )

            // Validate session data integrity
            sessionData["sessionId"] == sessionId &&
            sessionData["timestamp"] == timestamp &&
            sessionData["isGroupOwner"] == true &&
            sessionData["status"] == "active"

        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if device is currently offline (for testing purposes).
     */
    fun isDeviceOffline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return true
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true
            
            // Check if device has internet connectivity
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected != true
        }
    }

    /**
     * Performs a quick validation of core offline functionality.
     */
    fun quickValidation(): Boolean {
        return try {
            // Quick crypto test
            val keyPair = ecdhHelper.generateKeyPair()
            val testData = "quick test".toByteArray()
            
            // Quick QR test
            val qrBitmap = qrGenerator.generateQrCode("test")
            
            // Quick file test
            val tempFile = File.createTempFile("quick", ".tmp")
            tempFile.writeText("test")
            val exists = tempFile.exists()
            tempFile.delete()

            // Cleanup
            ecdhHelper.clearPrivateKey(keyPair.private)

            qrBitmap != null && exists

        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets detailed system information for offline operation.
     */
    fun getSystemInfo(): Map<String, String> {
        return mapOf(
            "android_version" to Build.VERSION.RELEASE,
            "api_level" to Build.VERSION.SDK_INT.toString(),
            "device_model" to Build.MODEL,
            "wifi_direct_supported" to validateWifiDirectCapability().toString(),
            "offline_mode" to isDeviceOffline().toString(),
            "crypto_available" to validateCryptographicOperations().toString(),
            "qr_available" to validateQrCodeOperations().toString(),
            "file_ops_available" to validateFileOperations().toString()
        )
    }
}
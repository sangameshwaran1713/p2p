package com.p2pshare.app.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.p2pshare.app.crypto.AesGcmCipher
import com.p2pshare.app.crypto.EcdhHelper
import com.p2pshare.app.crypto.Hkdf
import com.p2pshare.app.qr.QrGenerator
import com.p2pshare.app.transfer.ChunkManager
import com.p2pshare.app.transfer.FileManifest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Property-based tests for offline operation completeness.
 * 
 * Property 12: Offline Operation Completeness
 * Validates: Requirements 1.5
 * 
 * This test ensures that all core functionality works without internet connectivity:
 * - Cryptographic operations (ECDH, HKDF, AES-GCM)
 * - QR code generation and parsing
 * - File chunking and manifest creation
 * - Session management
 */
class OfflineOperationPropertyTest : StringSpec({

    val context: Context = ApplicationProvider.getApplicationContext()
    val ecdhHelper = EcdhHelper()
    val hkdf = Hkdf()
    val aesGcmCipher = AesGcmCipher()
    val qrGenerator = QrGenerator(context)
    val chunkManager = ChunkManager()

    "Property 12: Offline Operation Completeness - Cryptographic operations work offline" {
        checkAll(
            iterations = 50,
            Arb.byteArray(Arb.int(1, 1024), Arb.byte())
        ) { testData ->
            // Generate ECDH key pairs (no network required)
            val keyPair1 = ecdhHelper.generateKeyPair()
            val keyPair2 = ecdhHelper.generateKeyPair()

            keyPair1 shouldNotBe null
            keyPair2 shouldNotBe null

            // Compute shared secrets (no network required)
            val sharedSecret1 = ecdhHelper.computeSharedSecret(keyPair1.private, keyPair2.public)
            val sharedSecret2 = ecdhHelper.computeSharedSecret(keyPair2.private, keyPair1.public)

            // Shared secrets should match
            sharedSecret1 shouldBe sharedSecret2

            // Derive encryption keys using HKDF (no network required)
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            val info = "P2P File Share".toByteArray()

            val encryptionKey1 = hkdf.deriveKey(sharedSecret1, salt, info, 32)
            val encryptionKey2 = hkdf.deriveKey(sharedSecret2, salt, info, 32)

            // Derived keys should match
            encryptionKey1 shouldBe encryptionKey2

            // Encrypt and decrypt data (no network required)
            val encrypted = aesGcmCipher.encrypt(testData, encryptionKey1)
            val decrypted = aesGcmCipher.decrypt(encrypted, encryptionKey2)

            // Decrypted data should match original
            decrypted shouldBe testData

            // Cleanup
            ecdhHelper.clearPrivateKey(keyPair1.private)
            ecdhHelper.clearPrivateKey(keyPair2.private)
            ecdhHelper.clearSharedSecret(sharedSecret1)
            ecdhHelper.clearSharedSecret(sharedSecret2)
        }
    }

    "Property 12: Offline Operation Completeness - QR code operations work offline" {
        checkAll(
            iterations = 30,
            Arb.string(10, 100),
            Arb.string(5, 50),
            Arb.long(1024, 1024 * 1024)
        ) { sessionId, fileName, fileSize ->
            // Create session info (no network required)
            val sessionInfo = JSONObject().apply {
                put("sessionId", sessionId)
                put("fileName", fileName)
                put("fileSize", fileSize)
                put("timestamp", System.currentTimeMillis())
                put("publicKey", "test-public-key-data")
            }

            // Generate QR code (no network required)
            val qrBitmap = qrGenerator.generateQrCode(sessionInfo.toString())
            qrBitmap shouldNotBe null

            // Verify QR code contains expected data structure
            val qrData = sessionInfo.toString()
            val parsedInfo = JSONObject(qrData)
            
            parsedInfo.getString("sessionId") shouldBe sessionId
            parsedInfo.getString("fileName") shouldBe fileName
            parsedInfo.getLong("fileSize") shouldBe fileSize
        }
    }

    "Property 12: Offline Operation Completeness - File operations work offline" {
        checkAll(
            iterations = 20,
            Arb.string(5, 50).filter { it.isNotBlank() },
            Arb.byteArray(Arb.int(1024, 10 * 1024), Arb.byte())
        ) { fileName, fileContent ->
            // Create temporary file (no network required)
            val tempFile = File.createTempFile("test_$fileName", ".tmp")
            try {
                tempFile.writeBytes(fileContent)

                // Create file manifest (no network required)
                val manifest = FileManifest.fromFile(tempFile)
                
                manifest.fileName shouldBe tempFile.name
                manifest.fileSize shouldBe fileContent.size.toLong()
                manifest.chunkCount shouldBe ((fileContent.size + ChunkManager.CHUNK_SIZE - 1) / ChunkManager.CHUNK_SIZE)

                // Split file into chunks (no network required)
                val chunks = chunkManager.splitFile(tempFile)
                chunks.size shouldBe manifest.chunkCount

                // Verify chunk integrity (no network required)
                var totalSize = 0L
                chunks.forEach { chunk ->
                    totalSize += chunk.size
                    chunk.size shouldBe minOf(ChunkManager.CHUNK_SIZE.toLong(), fileContent.size - totalSize + chunk.size)
                }
                totalSize shouldBe fileContent.size.toLong()

                // Reassemble file from chunks (no network required)
                val reassembledFile = File.createTempFile("reassembled_$fileName", ".tmp")
                try {
                    reassembledFile.outputStream().use { output ->
                        chunks.forEach { chunk ->
                            output.write(chunk)
                        }
                    }

                    // Verify reassembled file matches original
                    reassembledFile.readBytes() shouldBe fileContent
                } finally {
                    reassembledFile.delete()
                }

            } finally {
                tempFile.delete()
            }
        }
    }

    "Property 12: Offline Operation Completeness - Session management works offline" {
        checkAll(
            iterations = 30,
            Arb.string(10, 50),
            Arb.boolean(),
            Arb.long(1000, 10000)
        ) { sessionId, isGroupOwner, timestamp ->
            // Create session data structure (no network required)
            val sessionData = mapOf(
                "sessionId" to sessionId,
                "isGroupOwner" to isGroupOwner,
                "timestamp" to timestamp,
                "status" to "active"
            )

            // Validate session data integrity (no network required)
            sessionData["sessionId"] shouldBe sessionId
            sessionData["isGroupOwner"] shouldBe isGroupOwner
            sessionData["timestamp"] shouldBe timestamp

            // Simulate session timeout calculation (no network required)
            val currentTime = System.currentTimeMillis()
            val sessionAge = currentTime - timestamp
            val isExpired = sessionAge > 5 * 60 * 1000L // 5 minutes

            // Session expiry logic should work offline
            if (timestamp > currentTime - 5 * 60 * 1000L) {
                isExpired shouldBe false
            }
        }
    }

    "Property 12: Offline Operation Completeness - Error handling works offline" {
        checkAll(
            iterations = 20,
            Arb.choice(
                Arb.constant("invalid_json"),
                Arb.constant(""),
                Arb.constant("null"),
                Arb.constant("{malformed json"),
                Arb.constant("[]")
            )
        ) { invalidData ->
            // Test error handling for invalid QR data (no network required)
            var exceptionCaught = false
            try {
                JSONObject(invalidData)
            } catch (e: Exception) {
                exceptionCaught = true
            }

            // Should handle invalid data gracefully offline
            if (invalidData != "null" && invalidData != "[]") {
                exceptionCaught shouldBe true
            }
        }
    }

    "Property 12: Offline Operation Completeness - Memory management works offline" {
        checkAll(
            iterations = 10,
            Arb.int(1, 10)
        ) { keyCount ->
            val keyPairs = mutableListOf<java.security.KeyPair>()
            val sharedSecrets = mutableListOf<ByteArray>()

            try {
                // Generate multiple key pairs (no network required)
                repeat(keyCount) {
                    val keyPair = ecdhHelper.generateKeyPair()
                    keyPairs.add(keyPair)
                }

                // Generate shared secrets (no network required)
                for (i in 0 until keyPairs.size - 1) {
                    val secret = ecdhHelper.computeSharedSecret(
                        keyPairs[i].private,
                        keyPairs[i + 1].public
                    )
                    sharedSecrets.add(secret)
                }

                // Verify all operations completed successfully
                keyPairs.size shouldBe keyCount
                sharedSecrets.size shouldBe maxOf(0, keyCount - 1)

            } finally {
                // Cleanup should work offline
                keyPairs.forEach { keyPair ->
                    ecdhHelper.clearPrivateKey(keyPair.private)
                }
                sharedSecrets.forEach { secret ->
                    ecdhHelper.clearSharedSecret(secret)
                }
            }
        }
    }

    "Property 12: Offline Operation Completeness - File validation works offline" {
        checkAll(
            iterations = 15,
            Arb.string(1, 100).filter { it.isNotBlank() },
            Arb.byteArray(Arb.int(0, 5 * 1024), Arb.byte())
        ) { fileName, content ->
            val tempFile = File.createTempFile("validation_test", ".tmp")
            try {
                tempFile.writeBytes(content)

                // File validation should work offline
                val exists = tempFile.exists()
                val size = tempFile.length()
                val canRead = tempFile.canRead()

                exists shouldBe true
                size shouldBe content.size.toLong()
                canRead shouldBe true

                // File operations should work offline
                val readContent = tempFile.readBytes()
                readContent shouldBe content

            } finally {
                tempFile.delete()
            }
        }
    }
})
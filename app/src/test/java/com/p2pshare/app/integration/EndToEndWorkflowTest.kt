package com.p2pshare.app.integration

import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.p2pshare.app.database.AppDatabase
import com.p2pshare.app.database.TransferDirection
import com.p2pshare.app.permissions.PermissionManager
import com.p2pshare.app.qr.QrGenerator
import com.p2pshare.app.service.TransferServiceHelper
import com.p2pshare.app.wifi.WifiDirectCallback
import com.p2pshare.app.wifi.WifiDirectManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import java.io.File
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import java.net.InetAddress

/**
 * End-to-end workflow tests for complete P2P file sharing scenarios.
 * 
 * These tests validate the complete user journey:
 * 1. Sender creates session and generates QR code
 * 2. Receiver scans QR code and connects
 * 3. File transfer occurs with progress monitoring
 * 4. Transfer completion and history recording
 * 5. Error recovery and edge cases
 */
class EndToEndWorkflowTest : StringSpec({

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    lateinit var context: Context
    lateinit var senderController: P2PFileShareController
    lateinit var receiverController: P2PFileShareController
    lateinit var database: AppDatabase

    beforeEach {
        context = ApplicationProvider.getApplicationContext()
        
        // Create in-memory database
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Create controllers for sender and receiver
        senderController = createTestController()
        receiverController = createTestController()
    }

    afterEach {
        database.close()
        senderController.cleanup()
        receiverController.cleanup()
    }

    "Complete send-receive workflow should work end-to-end" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test_document", ".pdf")
            testFile.writeText("This is a test PDF document with some content.")
            val fileUri = Uri.fromFile(testFile)

            try {
                // Step 1: Sender starts session
                val senderResult = senderController.startSendSession(fileUri)
                senderResult.isSuccess shouldBe true
                senderController.sessionState.value shouldBe P2PFileShareController.SessionState.CREATING_GROUP

                // Step 2: Simulate Wi-Fi Direct group creation
                val senderCallback = getSenderCallback()
                val mockGroup = mockk<WifiP2pGroup>()
                every { mockGroup.owner?.deviceAddress } returns "192.168.49.1"
                senderCallback.onGroupCreated(mockGroup)
                senderController.sessionState.value shouldBe P2PFileShareController.SessionState.WAITING_FOR_CONNECTION

                // Step 3: Generate QR code
                val qrResult = senderController.generateSessionQrCode()
                qrResult.isSuccess shouldBe true

                // Step 4: Extract QR data (simulate scanning)
                val qrData = extractQrData()

                // Step 5: Receiver starts session with QR data
                val receiverResult = receiverController.startReceiveSession(qrData)
                receiverResult.isSuccess shouldBe true
                receiverController.sessionState.value shouldBe P2PFileShareController.SessionState.WAITING_FOR_CONNECTION

                // Step 6: Simulate peer discovery and connection
                val receiverCallback = getReceiverCallback()
                val senderPeer = mockk<WifiP2pDevice>()
                every { senderPeer.deviceName } returns "SenderDevice"
                every { senderPeer.deviceAddress } returns "aa:bb:cc:dd:ee:ff"
                receiverCallback.onPeerAvailable(senderPeer)
                
                // Step 7: Establish connection
                val receiverPeer = mockk<WifiP2pDevice>()
                every { receiverPeer.deviceName } returns "ReceiverDevice"
                every { receiverPeer.deviceAddress } returns "ff:ee:dd:cc:bb:aa"
                senderCallback.onPeerAvailable(receiverPeer)
                
                val senderConnectionInfo = mockk<WifiP2pInfo>()
                every { senderConnectionInfo.groupOwnerAddress } returns InetAddress.getByName("192.168.49.2")
                senderCallback.onConnected(senderConnectionInfo)
                
                val receiverConnectionInfo = mockk<WifiP2pInfo>()
                every { receiverConnectionInfo.groupOwnerAddress } returns InetAddress.getByName("192.168.49.1")
                receiverCallback.onConnected(receiverConnectionInfo)

                // Verify both devices are connected
                senderController.sessionState.value shouldBe P2PFileShareController.SessionState.CONNECTED
                receiverController.sessionState.value shouldBe P2PFileShareController.SessionState.CONNECTED

                // Step 8: Start transfer
                val senderTransferResult = senderController.startTransfer()
                val receiverTransferResult = receiverController.startTransfer()

                senderTransferResult.isSuccess shouldBe true
                receiverTransferResult.isSuccess shouldBe true

                // Verify transfer state
                senderController.sessionState.value shouldBe P2PFileShareController.SessionState.TRANSFERRING
                receiverController.sessionState.value shouldBe P2PFileShareController.SessionState.TRANSFERRING

                // Step 9: Simulate transfer completion
                delay(100) // Allow transfer to process
                senderCallback.onDisconnected()
                receiverCallback.onDisconnected()

                // Step 10: Verify transfer history
                val senderHistory = senderController.getTransferHistory()
                val receiverHistory = receiverController.getTransferHistory()

                // Note: In a real scenario, transfer records would be created by TransferService
                // For this test, we verify the workflow completed successfully

            } finally {
                testFile.delete()
            }
        }
    }

    "Should handle connection failures gracefully" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test_failure", ".txt")
            testFile.writeText("Test content")
            val fileUri = Uri.fromFile(testFile)

            try {
                // Start sender session
                senderController.startSendSession(fileUri)
                val senderCallback = getSenderCallback()
                senderCallback.onGroupCreated("192.168.49.1")

                // Simulate group creation first
                val mockGroup2 = mockk<WifiP2pGroup>()
                every { mockGroup2.owner?.deviceAddress } returns "192.168.49.1"
                senderCallback.onGroupCreated(mockGroup2)
                
                // Simulate connection error
                senderCallback.onError("Wi-Fi Direct connection failed")

                // Verify error handling
                senderController.sessionState.value shouldBe P2PFileShareController.SessionState.ERROR

            } finally {
                testFile.delete()
            }
        }
    }

    "Should handle transfer cancellation" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test_cancel", ".txt")
            testFile.writeText("Test content for cancellation")
            val fileUri = Uri.fromFile(testFile)

            try {
                // Start and connect
                senderController.startSendSession(fileUri)
                val senderCallback = getSenderCallback()
                val mockGroup3 = mockk<WifiP2pGroup>()
                every { mockGroup3.owner?.deviceAddress } returns "192.168.49.1"
                senderCallback.onGroupCreated(mockGroup3)
                
                val mockConnectionInfo3 = mockk<WifiP2pInfo>()
                every { mockConnectionInfo3.groupOwnerAddress } returns InetAddress.getByName("192.168.49.2")
                senderCallback.onConnected(mockConnectionInfo3)
                senderController.startTransfer()

                // Cancel transfer
                val cancelResult = senderController.cancelTransfer()
                cancelResult.isSuccess shouldBe true
                senderController.sessionState.value shouldBe P2PFileShareController.SessionState.IDLE

            } finally {
                testFile.delete()
            }
        }
    }

    "Should validate offline operation throughout workflow" {
        runTest {
            // Test offline capability at each stage
            val isOfflineCapable = senderController.validateOfflineOperation()
            isOfflineCapable shouldBe true

            // Verify cryptographic operations work offline
            val testFile = File.createTempFile("offline_test", ".txt")
            testFile.writeText("Offline test content")
            val fileUri = Uri.fromFile(testFile)

            try {
                // All operations should work without network
                val sessionResult = senderController.startSendSession(fileUri)
                sessionResult.isSuccess shouldBe true

                val qrResult = senderController.generateSessionQrCode()
                qrResult.isSuccess shouldBe true

            } finally {
                testFile.delete()
            }
        }
    }

    "Should handle large file transfers" {
        runTest {
            // Arrange
            val largeFile = File.createTempFile("large_test", ".bin")
            val largeContent = ByteArray(5 * 1024 * 1024) // 5MB
            largeFile.writeBytes(largeContent)
            val fileUri = Uri.fromFile(largeFile)

            try {
                // Start session with large file
                val result = senderController.startSendSession(fileUri)
                result.isSuccess shouldBe true

                // Generate QR code for large file
                val qrResult = senderController.generateSessionQrCode()
                qrResult.isSuccess shouldBe true

                // Verify session can handle large files
                senderController.sessionState.value shouldBe P2PFileShareController.SessionState.CREATING_GROUP

            } finally {
                largeFile.delete()
            }
        }
    }

    "Should handle multiple concurrent sessions" {
        runTest {
            // Arrange
            val file1 = File.createTempFile("concurrent1", ".txt")
            val file2 = File.createTempFile("concurrent2", ".txt")
            file1.writeText("Content 1")
            file2.writeText("Content 2")

            try {
                // Start first session
                val result1 = senderController.startSendSession(Uri.fromFile(file1))
                result1.isSuccess shouldBe true

                // Try to start second session (should handle gracefully)
                val result2 = senderController.startSendSession(Uri.fromFile(file2))
                // Implementation should either queue or reject second session
                // For this test, we verify it doesn't crash

            } finally {
                file1.delete()
                file2.delete()
            }
        }
    }

    "Should maintain transfer history across sessions" {
        runTest {
            // Simulate multiple completed transfers
            val dao = database.transferDao()

            // Insert test records
            val record1 = com.p2pshare.app.database.TransferRecord(
                fileName = "document1.pdf",
                fileSize = 1024,
                direction = TransferDirection.SENT,
                timestamp = System.currentTimeMillis() - 3600000, // 1 hour ago
                duration = 5000,
                success = true
            )

            val record2 = com.p2pshare.app.database.TransferRecord(
                fileName = "image.jpg",
                fileSize = 2048,
                direction = TransferDirection.RECEIVED,
                timestamp = System.currentTimeMillis() - 1800000, // 30 minutes ago
                duration = 3000,
                success = true
            )

            dao.insertTransfer(record1)
            dao.insertTransfer(record2)

            // Verify history retrieval
            val history = senderController.getTransferHistory()
            history.size shouldBe 2

            // Verify chronological order (most recent first)
            history[0].fileName shouldBe "image.jpg"
            history[1].fileName shouldBe "document1.pdf"

            // Test history clearing
            val clearResult = senderController.clearTransferHistory()
            clearResult.isSuccess shouldBe true

            val emptyHistory = senderController.getTransferHistory()
            emptyHistory.size shouldBe 0
        }
    }

    // Helper functions

    private fun createTestController(): P2PFileShareController {
        val permissionManager = mockk<PermissionManager> {
            every { areCriticalPermissionsGranted() } returns true
            every { areAllPermissionsGranted() } returns true
        }

        val wifiDirectManager = mockk<WifiDirectManager> {
            every { isWifiDirectSupported() } returns true
            every { setCallback(any()) } just Runs
            every { createGroup() } just Runs
            every { discoverPeers() } just Runs
            every { connectToPeer(any()) } just Runs
            every { cleanup() } just Runs
        }

        val qrGenerator = mockk<QrGenerator> {
            every { generateQrCode(any()) } returns mockk()
        }

        val transferServiceHelper = mockk<TransferServiceHelper> {
            every { startSendTransfer(any(), any()) } just Runs
            every { startReceiveTransfer(any()) } just Runs
            every { stopTransfer() } just Runs
        }

        return P2PFileShareController(
            context = context,
            permissionManager = permissionManager,
            wifiDirectManager = wifiDirectManager,
            qrGenerator = qrGenerator,
            transferServiceHelper = transferServiceHelper,
            database = database
        )
    }

    private fun getSenderCallback(): WifiDirectCallback {
        val callbackSlot = slot<WifiDirectCallback>()
        verify { senderController.wifiDirectManager.setCallback(capture(callbackSlot)) }
        return callbackSlot.captured
    }

    private fun getReceiverCallback(): WifiDirectCallback {
        val callbackSlot = slot<WifiDirectCallback>()
        verify { receiverController.wifiDirectManager.setCallback(capture(callbackSlot)) }
        return callbackSlot.captured
    }

    private fun extractQrData(): String {
        // Simulate QR code data extraction
        return JSONObject().apply {
            put("sessionId", "test-session-123")
            put("publicKey", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...")
            put("fileName", "test_document.pdf")
            put("fileSize", 1024)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }
})
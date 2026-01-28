package com.p2pshare.app.integration

import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.p2pshare.app.database.AppDatabase
import com.p2pshare.app.permissions.PermissionManager
import com.p2pshare.app.qr.QrGenerator
import com.p2pshare.app.service.TransferServiceHelper
import com.p2pshare.app.wifi.WifiDirectManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.io.File
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import java.net.InetAddress

/**
 * Integration tests for the complete P2P file sharing workflow.
 * 
 * These tests validate end-to-end functionality including:
 * - Session creation and management
 * - QR code generation and parsing
 * - Wi-Fi Direct integration
 * - Transfer coordination
 * - Database operations
 * - Offline operation validation
 */
class P2PFileShareIntegrationTest : StringSpec({

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    lateinit var context: Context
    lateinit var permissionManager: PermissionManager
    lateinit var wifiDirectManager: WifiDirectManager
    lateinit var qrGenerator: QrGenerator
    lateinit var transferServiceHelper: TransferServiceHelper
    lateinit var database: AppDatabase
    lateinit var controller: P2PFileShareController

    beforeEach {
        context = ApplicationProvider.getApplicationContext()
        
        // Create in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Mock dependencies
        permissionManager = mockk {
            every { areCriticalPermissionsGranted() } returns true
            every { areAllPermissionsGranted() } returns true
        }

        wifiDirectManager = mockk {
            every { isWifiDirectSupported() } returns true
            every { setCallback(any()) } just Runs
            every { createGroup() } just Runs
            every { discoverPeers() } just Runs
            every { connectToPeer(any()) } just Runs
            every { cleanup() } just Runs
        }

        qrGenerator = mockk()
        transferServiceHelper = mockk {
            every { startSendTransfer(any(), any()) } just Runs
            every { startReceiveTransfer(any()) } just Runs
            every { stopTransfer() } just Runs
        }

        controller = P2PFileShareController(
            context = context,
            permissionManager = permissionManager,
            wifiDirectManager = wifiDirectManager,
            qrGenerator = qrGenerator,
            transferServiceHelper = transferServiceHelper,
            database = database
        )
    }

    afterEach {
        database.close()
        controller.cleanup()
    }

    "should create send session successfully" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test", ".txt")
            testFile.writeText("Test content")
            val fileUri = Uri.fromFile(testFile)

            // Act
            val result = controller.startSendSession(fileUri)

            // Assert
            result.isSuccess shouldBe true
            result.getOrNull() shouldNotBe null
            controller.sessionState.value shouldBe P2PFileShareController.SessionState.CREATING_GROUP

            // Verify Wi-Fi Direct group creation was called
            verify { wifiDirectManager.createGroup() }

            // Cleanup
            testFile.delete()
        }
    }

    "should generate QR code for active session" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test", ".txt")
            testFile.writeText("Test content")
            val fileUri = Uri.fromFile(testFile)
            
            val mockBitmap = mockk<android.graphics.Bitmap>()
            every { qrGenerator.generateQrCode(any()) } returns mockBitmap

            // Start session first
            controller.startSendSession(fileUri)

            // Act
            val result = controller.generateSessionQrCode()

            // Assert
            result.isSuccess shouldBe true
            result.getOrNull() shouldBe mockBitmap

            // Verify QR generator was called with session info
            verify { qrGenerator.generateQrCode(match { qrData ->
                qrData.contains("sessionId") && 
                qrData.contains("publicKey") && 
                qrData.contains("fileName")
            }) }

            // Cleanup
            testFile.delete()
        }
    }

    "should start receive session from QR data" {
        runTest {
            // Arrange
            val qrData = """
                {
                    "sessionId": "test-session-123",
                    "publicKey": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
                    "fileName": "test.txt",
                    "fileSize": 1024,
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()

            // Act
            val result = controller.startReceiveSession(qrData)

            // Assert
            result.isSuccess shouldBe true
            result.getOrNull() shouldBe "test-session-123"
            controller.sessionState.value shouldBe P2PFileShareController.SessionState.WAITING_FOR_CONNECTION

            // Verify peer discovery was initiated
            verify { wifiDirectManager.discoverPeers() }
        }
    }

    "should handle Wi-Fi Direct connection flow" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test", ".txt")
            testFile.writeText("Test content")
            val fileUri = Uri.fromFile(testFile)

            // Start send session
            controller.startSendSession(fileUri)

            // Get the callback that was set on WifiDirectManager
            val callbackSlot = slot<com.p2pshare.app.wifi.WifiDirectCallback>()
            verify { wifiDirectManager.setCallback(capture(callbackSlot)) }
            val callback = callbackSlot.captured

            // Act - Simulate Wi-Fi Direct events
            val mockGroup = mockk<WifiP2pGroup>()
            every { mockGroup.owner?.deviceAddress } returns "192.168.49.1"
            callback.onGroupCreated(mockGroup)
            controller.sessionState.value shouldBe P2PFileShareController.SessionState.WAITING_FOR_CONNECTION

            val mockPeer = mockk<WifiP2pDevice>()
            every { mockPeer.deviceName } returns "TestDevice"
            every { mockPeer.deviceAddress } returns "aa:bb:cc:dd:ee:ff"
            callback.onPeerAvailable(mockPeer)
            
            val mockConnectionInfo = mockk<WifiP2pInfo>()
            every { mockConnectionInfo.groupOwnerAddress } returns InetAddress.getByName("192.168.49.2")
            callback.onConnected(mockConnectionInfo)
            controller.sessionState.value shouldBe P2PFileShareController.SessionState.CONNECTED

            // Cleanup
            testFile.delete()
        }
    }

    "should start transfer after connection" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test", ".txt")
            testFile.writeText("Test content")
            val fileUri = Uri.fromFile(testFile)

            // Start session and simulate connection
            controller.startSendSession(fileUri)
            
            val callbackSlot = slot<com.p2pshare.app.wifi.WifiDirectCallback>()
            verify { wifiDirectManager.setCallback(capture(callbackSlot)) }
            
            val mockConnectionInfo = mockk<WifiP2pInfo>()
            every { mockConnectionInfo.groupOwnerAddress } returns InetAddress.getByName("192.168.49.2")
            callbackSlot.captured.onConnected(mockConnectionInfo)

            // Act
            val result = controller.startTransfer()

            // Assert
            result.isSuccess shouldBe true
            controller.sessionState.value shouldBe P2PFileShareController.SessionState.TRANSFERRING

            // Verify transfer service was started
            verify { transferServiceHelper.startSendTransfer(fileUri, any()) }

            // Cleanup
            testFile.delete()
        }
    }

    "should cancel transfer successfully" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test", ".txt")
            testFile.writeText("Test content")
            val fileUri = Uri.fromFile(testFile)

            controller.startSendSession(fileUri)
            controller.startTransfer()

            // Act
            val result = controller.cancelTransfer()

            // Assert
            result.isSuccess shouldBe true
            controller.sessionState.value shouldBe P2PFileShareController.SessionState.IDLE

            // Verify transfer service was stopped
            verify { transferServiceHelper.stopTransfer() }

            // Cleanup
            testFile.delete()
        }
    }

    "should validate offline operation capability" {
        // Act
        val isOfflineCapable = controller.validateOfflineOperation()

        // Assert
        isOfflineCapable shouldBe true

        // Verify Wi-Fi Direct support was checked
        verify { wifiDirectManager.isWifiDirectSupported() }
    }

    "should handle transfer history operations" {
        runTest {
            // Arrange - Insert test record
            val testRecord = com.p2pshare.app.database.TransferRecord(
                fileName = "test.txt",
                fileSize = 1024,
                direction = com.p2pshare.app.database.TransferDirection.SENT,
                timestamp = System.currentTimeMillis(),
                duration = 5000,
                success = true
            )
            database.transferDao().insertTransfer(testRecord)

            // Act - Get history
            val history = controller.getTransferHistory()

            // Assert
            history.size shouldBe 1
            history[0].fileName shouldBe "test.txt"

            // Act - Clear history
            val clearResult = controller.clearTransferHistory()

            // Assert
            clearResult.isSuccess shouldBe true
            val emptyHistory = controller.getTransferHistory()
            emptyHistory.size shouldBe 0
        }
    }

    "should handle permission errors gracefully" {
        runTest {
            // Arrange
            every { permissionManager.areCriticalPermissionsGranted() } returns false
            
            val testFile = File.createTempFile("test", ".txt")
            val fileUri = Uri.fromFile(testFile)

            // Act
            val result = controller.startSendSession(fileUri)

            // Assert
            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe "Critical permissions not granted"

            // Cleanup
            testFile.delete()
        }
    }

    "should handle Wi-Fi Direct errors" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test", ".txt")
            testFile.writeText("Test content")
            val fileUri = Uri.fromFile(testFile)

            controller.startSendSession(fileUri)

            // Get callback and simulate error
            val callbackSlot = slot<com.p2pshare.app.wifi.WifiDirectCallback>()
            verify { wifiDirectManager.setCallback(capture(callbackSlot)) }

            // Act
            callbackSlot.captured.onError("Wi-Fi Direct connection failed")

            // Assert
            controller.sessionState.value shouldBe P2PFileShareController.SessionState.ERROR

            // Cleanup
            testFile.delete()
        }
    }

    "should handle malformed QR data" {
        runTest {
            // Arrange
            val malformedQrData = "invalid json data"

            // Act
            val result = controller.startReceiveSession(malformedQrData)

            // Assert
            result.isFailure shouldBe true
            controller.sessionState.value shouldBe P2PFileShareController.SessionState.ERROR
        }
    }

    "should cleanup resources properly" {
        runTest {
            // Arrange
            val testFile = File.createTempFile("test", ".txt")
            testFile.writeText("Test content")
            val fileUri = Uri.fromFile(testFile)

            controller.startSendSession(fileUri)

            // Act
            controller.cleanup()

            // Assert
            verify { wifiDirectManager.cleanup() }

            // Cleanup
            testFile.delete()
        }
    }
})
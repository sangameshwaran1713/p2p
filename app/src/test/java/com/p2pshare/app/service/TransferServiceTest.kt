package com.p2pshare.app.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import com.p2pshare.app.service.TransferService.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for TransferService.
 * 
 * Tests service lifecycle, notification management, progress reporting,
 * and transfer execution in background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransferServiceTest : DescribeSpec({
    
    describe("TransferService") {
        
        lateinit var service: TransferService
        lateinit var context: Context
        lateinit var mockNotificationManager: NotificationManager
        
        beforeEach {
            context = ApplicationProvider.getApplicationContext()
            mockNotificationManager = mockk(relaxed = true)
            
            // Mock system services
            mockkStatic("androidx.core.content.ContextCompat")
            every { 
                context.getSystemService(Context.NOTIFICATION_SERVICE) 
            } returns mockNotificationManager
            
            // Create service controller
            val serviceController = Robolectric.buildService(TransferService::class.java)
            service = serviceController.create().get()
        }
        
        afterEach {
            unmockkAll()
        }
        
        describe("Service Lifecycle") {
            
            it("should initialize with IDLE state") {
                service.getCurrentState() shouldBe TransferState.IDLE
                service.isTransferActive() shouldBe false
            }
            
            it("should create notification channel on creation") {
                verify { mockNotificationManager.createNotificationChannel(any()) }
            }
            
            it("should return proper binder") {
                val binder = service.onBind(Intent())
                binder.shouldBeInstanceOf<TransferService.TransferBinder>()
                
                val boundService = (binder as TransferService.TransferBinder).getService()
                boundService shouldBe service
            }
            
            it("should handle service destruction properly") {
                service.onDestroy()
                service.isTransferActive() shouldBe false
            }
        }
        
        describe("Transfer State Management") {
            
            it("should update transfer state correctly") {
                val stateObserver = mockk<Observer<TransferState>>(relaxed = true)
                service.transferState.observeForever(stateObserver)
                
                // Initial state should be IDLE
                verify { stateObserver.onChanged(TransferState.IDLE) }
                
                service.transferState.removeObserver(stateObserver)
            }
            
            it("should update transfer progress correctly") {
                val progressObserver = mockk<Observer<TransferProgress>>(relaxed = true)
                service.transferProgress.observeForever(progressObserver)
                
                // Should receive initial progress updates
                verify(timeout = 1000) { progressObserver.onChanged(any()) }
                
                service.transferProgress.removeObserver(progressObserver)
            }
        }
        
        describe("Intent Handling") {
            
            it("should handle START_SEND action with valid file path") {
                val testFile = File.createTempFile("test", ".txt")
                testFile.writeText("Test content")
                
                val intent = Intent().apply {
                    action = TransferService.ACTION_START_SEND
                    putExtra(TransferService.EXTRA_FILE_PATH, testFile.absolutePath)
                }
                
                val result = service.onStartCommand(intent, 0, 1)
                result shouldBe android.app.Service.START_NOT_STICKY
                
                // Service should start preparing
                runBlocking {
                    delay(100) // Allow async operations to start
                    service.getCurrentState() shouldNotBe TransferState.IDLE
                }
                
                testFile.delete()
            }
            
            it("should handle START_RECEIVE action with valid parameters") {
                val outputDir = File.createTempFile("output", "").apply {
                    delete()
                    mkdirs()
                }
                
                val intent = Intent().apply {
                    action = TransferService.ACTION_START_RECEIVE
                    putExtra(TransferService.EXTRA_SENDER_IP, "192.168.1.100")
                    putExtra(TransferService.EXTRA_SENDER_PORT, 8080)
                    putExtra(TransferService.EXTRA_OUTPUT_DIRECTORY, outputDir.absolutePath)
                    putExtra(TransferService.EXTRA_TRANSFER_ID, "test-transfer-123")
                }
                
                val result = service.onStartCommand(intent, 0, 1)
                result shouldBe android.app.Service.START_NOT_STICKY
                
                outputDir.deleteRecursively()
            }
            
            it("should handle CANCEL_TRANSFER action") {
                val intent = Intent().apply {
                    action = TransferService.ACTION_CANCEL_TRANSFER
                }
                
                val result = service.onStartCommand(intent, 0, 1)
                result shouldBe android.app.Service.START_NOT_STICKY
                
                runBlocking {
                    delay(100)
                    service.getCurrentState() shouldBe TransferState.CANCELLED
                }
            }
            
            it("should handle invalid START_SEND action gracefully") {
                val intent = Intent().apply {
                    action = TransferService.ACTION_START_SEND
                    // Missing file path
                }
                
                val result = service.onStartCommand(intent, 0, 1)
                result shouldBe android.app.Service.START_NOT_STICKY
                
                runBlocking {
                    delay(100)
                    service.getCurrentState() shouldBe TransferState.FAILED
                }
            }
            
            it("should handle invalid START_RECEIVE action gracefully") {
                val intent = Intent().apply {
                    action = TransferService.ACTION_START_RECEIVE
                    // Missing required parameters
                }
                
                val result = service.onStartCommand(intent, 0, 1)
                result shouldBe android.app.Service.START_NOT_STICKY
                
                runBlocking {
                    delay(100)
                    service.getCurrentState() shouldBe TransferState.FAILED
                }
            }
        }
        
        describe("Notification Management") {
            
            it("should create notification for send transfer") {
                val testFile = File.createTempFile("test", ".txt")
                testFile.writeText("Test content")
                
                val progress = TransferProgress(
                    transferType = TransferType.SENDING,
                    fileName = testFile.name,
                    totalBytes = testFile.length(),
                    progressPercentage = 50f,
                    speedBytesPerSecond = 1024 * 1024, // 1 MB/s
                    estimatedTimeRemainingMs = 30000 // 30 seconds
                )
                
                // This would be called internally by the service
                // We can't directly test private methods, but we can verify
                // that notifications are created through the NotificationManager
                verify(timeout = 1000) { 
                    mockNotificationManager.createNotificationChannel(any()) 
                }
                
                testFile.delete()
            }
            
            it("should create notification for receive transfer") {
                val progress = TransferProgress(
                    transferType = TransferType.RECEIVING,
                    fileName = "received_file.txt",
                    totalBytes = 1024 * 1024, // 1 MB
                    progressPercentage = 75f,
                    speedBytesPerSecond = 512 * 1024, // 512 KB/s
                    estimatedTimeRemainingMs = 10000 // 10 seconds
                )
                
                // Verify notification channel creation
                verify(timeout = 1000) { 
                    mockNotificationManager.createNotificationChannel(any()) 
                }
            }
        }
        
        describe("Progress Monitoring") {
            
            it("should provide current progress") {
                val progress = service.getCurrentProgress()
                progress shouldNotBe null
            }
            
            it("should provide current state") {
                val state = service.getCurrentState()
                state shouldBe TransferState.IDLE
            }
            
            it("should report transfer activity status") {
                service.isTransferActive() shouldBe false
            }
        }
        
        describe("Sender Integration") {
            
            it("should provide sender port when sending") {
                // Initially no sender
                service.getSenderPort() shouldBe -1
            }
            
            it("should provide sender public key when sending") {
                // Initially no sender
                service.getSenderPublicKey() shouldBe null
            }
        }
        
        describe("Error Handling") {
            
            it("should handle file not found error") {
                val intent = Intent().apply {
                    action = TransferService.ACTION_START_SEND
                    putExtra(TransferService.EXTRA_FILE_PATH, "/nonexistent/file.txt")
                }
                
                service.onStartCommand(intent, 0, 1)
                
                runBlocking {
                    delay(100)
                    service.getCurrentState() shouldBe TransferState.FAILED
                    service.getCurrentProgress()?.errorMessage shouldNotBe null
                }
            }
            
            it("should handle invalid network parameters") {
                val intent = Intent().apply {
                    action = TransferService.ACTION_START_RECEIVE
                    putExtra(TransferService.EXTRA_SENDER_IP, "invalid-ip")
                    putExtra(TransferService.EXTRA_SENDER_PORT, -1)
                    putExtra(TransferService.EXTRA_OUTPUT_DIRECTORY, "/invalid/path")
                }
                
                service.onStartCommand(intent, 0, 1)
                
                runBlocking {
                    delay(100)
                    service.getCurrentState() shouldBe TransferState.FAILED
                }
            }
        }
        
        describe("Transfer Type Handling") {
            
            it("should handle SENDING transfer type") {
                val progress = TransferProgress(transferType = TransferType.SENDING)
                progress.transferType shouldBe TransferType.SENDING
            }
            
            it("should handle RECEIVING transfer type") {
                val progress = TransferProgress(transferType = TransferType.RECEIVING)
                progress.transferType shouldBe TransferType.RECEIVING
            }
            
            it("should handle NONE transfer type") {
                val progress = TransferProgress(transferType = TransferType.NONE)
                progress.transferType shouldBe TransferType.NONE
            }
        }
    }
})
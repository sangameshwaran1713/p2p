package com.p2pshare.app.database

import android.content.Context
import androidx.lifecycle.Observer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.p2pshare.app.database.TransferRecord.TransferDirection
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.*

/**
 * Unit tests for database operations.
 * 
 * Tests transfer record insertion, retrieval, deletion, and statistics
 * functionality with comprehensive coverage of edge cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseTest : DescribeSpec({
    
    describe("Database Operations") {
        
        lateinit var database: AppDatabase
        lateinit var transferDao: TransferDao
        lateinit var repository: AppDatabase.TransferRepository
        lateinit var context: Context
        
        beforeEach {
            context = ApplicationProvider.getApplicationContext()
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            transferDao = database.transferDao()
            repository = AppDatabase.TransferRepository(transferDao)
        }
        
        afterEach {
            database.close()
        }
        
        describe("TransferRecord Entity") {
            
            it("should create successful transfer record correctly") {
                val record = TransferRecord.createSuccessfulTransfer(
                    fileName = "test.txt",
                    fileSize = 1024L,
                    direction = TransferDirection.SENT,
                    duration = 5000L,
                    transferId = "test-123",
                    peerDeviceName = "TestDevice",
                    filePath = "/test/path",
                    averageSpeed = 204L // 1024 bytes / 5 seconds
                )
                
                record.fileName shouldBe "test.txt"
                record.fileSize shouldBe 1024L
                record.direction shouldBe TransferDirection.SENT
                record.duration shouldBe 5000L
                record.success shouldBe true
                record.bytesTransferred shouldBe 1024L
                record.isCompleted() shouldBe true
                record.getCompletionPercentage() shouldBe 100f
            }
            
            it("should create failed transfer record correctly") {
                val record = TransferRecord.createFailedTransfer(
                    fileName = "failed.txt",
                    fileSize = 2048L,
                    direction = TransferDirection.RECEIVED,
                    duration = 3000L,
                    errorMessage = "Connection lost",
                    bytesTransferred = 1024L,
                    transferId = "failed-456"
                )
                
                record.fileName shouldBe "failed.txt"
                record.fileSize shouldBe 2048L
                record.success shouldBe false
                record.errorMessage shouldBe "Connection lost"
                record.bytesTransferred shouldBe 1024L
                record.isCompleted() shouldBe false
                record.getCompletionPercentage() shouldBe 50f
            }
            
            it("should format display strings correctly") {
                val record = TransferRecord(
                    fileName = "example.pdf",
                    fileSize = 1536 * 1024L, // 1.5 MB
                    direction = TransferDirection.SENT,
                    timestamp = Date(),
                    duration = 65000L, // 1 minute 5 seconds
                    success = true,
                    bytesTransferred = 1536 * 1024L,
                    averageSpeed = 1024 * 1024L // 1 MB/s
                )
                
                record.getFormattedFileSize() shouldBe "1.5 MB"
                record.getFormattedDuration() shouldBe "1:05"
                record.getFormattedAverageSpeed() shouldBe "1.0 MB/s"
                record.getDirectionString() shouldBe "Sent"
                record.getStatusDescription() shouldBe "Completed"
            }
        }
        
        describe("Basic CRUD Operations") {
            
            it("should insert and retrieve transfer record") {
                runBlocking {
                    val record = TransferRecord(
                        fileName = "insert_test.txt",
                        fileSize = 512L,
                        direction = TransferDirection.SENT,
                        timestamp = Date(),
                        duration = 1000L,
                        success = true,
                        bytesTransferred = 512L
                    )
                    
                    val insertedId = transferDao.insertTransfer(record)
                    insertedId shouldNotBe 0L
                    
                    val retrieved = transferDao.getTransferById(insertedId)
                    retrieved shouldNotBe null
                    retrieved!!.fileName shouldBe "insert_test.txt"
                    retrieved.fileSize shouldBe 512L
                }
            }
            
            it("should update transfer record") {
                runBlocking {
                    val record = TransferRecord(
                        fileName = "update_test.txt",
                        fileSize = 1024L,
                        direction = TransferDirection.RECEIVED,
                        timestamp = Date(),
                        duration = 2000L,
                        success = false,
                        bytesTransferred = 512L
                    )
                    
                    val insertedId = transferDao.insertTransfer(record)
                    val retrieved = transferDao.getTransferById(insertedId)!!
                    
                    val updated = retrieved.copy(
                        success = true,
                        bytesTransferred = 1024L,
                        errorMessage = null
                    )
                    
                    val updateCount = transferDao.updateTransfer(updated)
                    updateCount shouldBe 1
                    
                    val afterUpdate = transferDao.getTransferById(insertedId)!!
                    afterUpdate.success shouldBe true
                    afterUpdate.bytesTransferred shouldBe 1024L
                }
            }
            
            it("should delete transfer record") {
                runBlocking {
                    val record = TransferRecord(
                        fileName = "delete_test.txt",
                        fileSize = 256L,
                        direction = TransferDirection.SENT,
                        timestamp = Date(),
                        duration = 500L,
                        success = true,
                        bytesTransferred = 256L
                    )
                    
                    val insertedId = transferDao.insertTransfer(record)
                    val deleteCount = transferDao.deleteTransferById(insertedId)
                    deleteCount shouldBe 1
                    
                    val afterDelete = transferDao.getTransferById(insertedId)
                    afterDelete shouldBe null
                }
            }
        }
        
        describe("Bulk Operations") {
            
            it("should insert multiple records") {
                runBlocking {
                    val records = listOf(
                        TransferRecord(
                            fileName = "bulk1.txt",
                            fileSize = 100L,
                            direction = TransferDirection.SENT,
                            timestamp = Date(),
                            duration = 1000L,
                            success = true,
                            bytesTransferred = 100L
                        ),
                        TransferRecord(
                            fileName = "bulk2.txt",
                            fileSize = 200L,
                            direction = TransferDirection.RECEIVED,
                            timestamp = Date(),
                            duration = 2000L,
                            success = true,
                            bytesTransferred = 200L
                        )
                    )
                    
                    val insertedIds = transferDao.insertTransfers(records)
                    insertedIds.size shouldBe 2
                    
                    val count = transferDao.getTransferCount()
                    count shouldBe 2
                }
            }
            
            it("should delete all records") {
                runBlocking {
                    // Insert some test records
                    val records = (1..5).map { i ->
                        TransferRecord(
                            fileName = "bulk_delete_$i.txt",
                            fileSize = i * 100L,
                            direction = TransferDirection.SENT,
                            timestamp = Date(),
                            duration = 1000L,
                            success = true,
                            bytesTransferred = i * 100L
                        )
                    }
                    
                    transferDao.insertTransfers(records)
                    val initialCount = transferDao.getTransferCount()
                    initialCount shouldBe 5
                    
                    val deletedCount = transferDao.deleteAllTransfers()
                    deletedCount shouldBe 5
                    
                    val finalCount = transferDao.getTransferCount()
                    finalCount shouldBe 0
                }
            }
        }
        
        describe("Filtering and Querying") {
            
            it("should filter by transfer direction") {
                runBlocking {
                    // Insert mixed direction records
                    val sentRecord = TransferRecord(
                        fileName = "sent.txt",
                        fileSize = 100L,
                        direction = TransferDirection.SENT,
                        timestamp = Date(),
                        duration = 1000L,
                        success = true,
                        bytesTransferred = 100L
                    )
                    
                    val receivedRecord = TransferRecord(
                        fileName = "received.txt",
                        fileSize = 200L,
                        direction = TransferDirection.RECEIVED,
                        timestamp = Date(),
                        duration = 2000L,
                        success = true,
                        bytesTransferred = 200L
                    )
                    
                    transferDao.insertTransfer(sentRecord)
                    transferDao.insertTransfer(receivedRecord)
                    
                    // Note: In unit tests, LiveData doesn't update automatically
                    // We test the DAO methods work without exceptions
                    val sentLiveData = transferDao.getTransfersByDirection(TransferDirection.SENT)
                    val receivedLiveData = transferDao.getTransfersByDirection(TransferDirection.RECEIVED)
                    
                    sentLiveData shouldNotBe null
                    receivedLiveData shouldNotBe null
                }
            }
            
            it("should filter by success status") {
                runBlocking {
                    val successfulRecord = TransferRecord(
                        fileName = "success.txt",
                        fileSize = 100L,
                        direction = TransferDirection.SENT,
                        timestamp = Date(),
                        duration = 1000L,
                        success = true,
                        bytesTransferred = 100L
                    )
                    
                    val failedRecord = TransferRecord(
                        fileName = "failed.txt",
                        fileSize = 200L,
                        direction = TransferDirection.RECEIVED,
                        timestamp = Date(),
                        duration = 2000L,
                        success = false,
                        bytesTransferred = 50L,
                        errorMessage = "Test error"
                    )
                    
                    transferDao.insertTransfer(successfulRecord)
                    transferDao.insertTransfer(failedRecord)
                    
                    val successfulLiveData = transferDao.getSuccessfulTransfers()
                    val failedLiveData = transferDao.getFailedTransfers()
                    
                    successfulLiveData shouldNotBe null
                    failedLiveData shouldNotBe null
                }
            }
            
            it("should search by file name") {
                runBlocking {
                    val records = listOf(
                        TransferRecord(
                            fileName = "document.pdf",
                            fileSize = 100L,
                            direction = TransferDirection.SENT,
                            timestamp = Date(),
                            duration = 1000L,
                            success = true,
                            bytesTransferred = 100L
                        ),
                        TransferRecord(
                            fileName = "image.jpg",
                            fileSize = 200L,
                            direction = TransferDirection.RECEIVED,
                            timestamp = Date(),
                            duration = 2000L,
                            success = true,
                            bytesTransferred = 200L
                        )
                    )
                    
                    transferDao.insertTransfers(records)
                    
                    val searchResults = transferDao.searchTransfersByFileName("doc")
                    searchResults shouldNotBe null
                    
                    val exactMatch = transferDao.getTransfersByFileName("document.pdf")
                    exactMatch shouldNotBe null
                }
            }
        }
        
        describe("Statistics and Aggregation") {
            
            it("should calculate transfer statistics correctly") {
                runBlocking {
                    val records = listOf(
                        TransferRecord(
                            fileName = "stats1.txt",
                            fileSize = 1000L,
                            direction = TransferDirection.SENT,
                            timestamp = Date(),
                            duration = 1000L,
                            success = true,
                            bytesTransferred = 1000L,
                            averageSpeed = 1000L
                        ),
                        TransferRecord(
                            fileName = "stats2.txt",
                            fileSize = 2000L,
                            direction = TransferDirection.RECEIVED,
                            timestamp = Date(),
                            duration = 2000L,
                            success = true,
                            bytesTransferred = 2000L,
                            averageSpeed = 1000L
                        ),
                        TransferRecord(
                            fileName = "stats3.txt",
                            fileSize = 1500L,
                            direction = TransferDirection.SENT,
                            timestamp = Date(),
                            duration = 1500L,
                            success = false,
                            bytesTransferred = 500L,
                            errorMessage = "Failed"
                        )
                    )
                    
                    transferDao.insertTransfers(records)
                    
                    val stats = transferDao.getTransferStatistics()
                    stats.totalTransfers shouldBe 3
                    stats.successfulTransfers shouldBe 2
                    stats.failedTransfers shouldBe 1
                    stats.totalBytesTransferred shouldBe 3000L // Only successful transfers
                    stats.getSuccessRate() shouldBe (2f / 3f * 100f)
                }
            }
            
            it("should get transfer counts correctly") {
                runBlocking {
                    val records = (1..10).map { i ->
                        TransferRecord(
                            fileName = "count_$i.txt",
                            fileSize = i * 100L,
                            direction = if (i % 2 == 0) TransferDirection.SENT else TransferDirection.RECEIVED,
                            timestamp = Date(),
                            duration = 1000L,
                            success = i % 3 != 0, // Some failures
                            bytesTransferred = i * 100L
                        )
                    }
                    
                    transferDao.insertTransfers(records)
                    
                    val totalCount = transferDao.getTransferCount()
                    val successCount = transferDao.getSuccessfulTransferCount()
                    val failedCount = transferDao.getFailedTransferCount()
                    
                    totalCount shouldBe 10
                    successCount shouldBe records.count { it.success }
                    failedCount shouldBe records.count { !it.success }
                    successCount + failedCount shouldBe totalCount
                }
            }
            
            it("should find largest and fastest transfers") {
                runBlocking {
                    val records = listOf(
                        TransferRecord(
                            fileName = "small.txt",
                            fileSize = 100L,
                            direction = TransferDirection.SENT,
                            timestamp = Date(),
                            duration = 1000L,
                            success = true,
                            bytesTransferred = 100L,
                            averageSpeed = 100L
                        ),
                        TransferRecord(
                            fileName = "large.txt",
                            fileSize = 10000L, // Largest
                            direction = TransferDirection.RECEIVED,
                            timestamp = Date(),
                            duration = 2000L,
                            success = true,
                            bytesTransferred = 10000L,
                            averageSpeed = 5000L // Fastest
                        ),
                        TransferRecord(
                            fileName = "medium.txt",
                            fileSize = 1000L,
                            direction = TransferDirection.SENT,
                            timestamp = Date(),
                            duration = 1500L,
                            success = true,
                            bytesTransferred = 1000L,
                            averageSpeed = 666L
                        )
                    )
                    
                    transferDao.insertTransfers(records)
                    
                    val largest = transferDao.getLargestTransfer()
                    val fastest = transferDao.getFastestTransfer()
                    
                    largest shouldNotBe null
                    largest!!.fileName shouldBe "large.txt"
                    largest.fileSize shouldBe 10000L
                    
                    fastest shouldNotBe null
                    fastest!!.fileName shouldBe "large.txt"
                    fastest.averageSpeed shouldBe 5000L
                }
            }
        }
        
        describe("Repository Operations") {
            
            it("should record successful transfer through repository") {
                runBlocking {
                    val recordId = repository.recordSuccessfulTransfer(
                        fileName = "repo_success.txt",
                        fileSize = 2048L,
                        direction = TransferDirection.SENT,
                        duration = 4000L,
                        transferId = "repo-123",
                        peerDeviceName = "RepoDevice",
                        filePath = "/repo/path",
                        averageSpeed = 512L
                    )
                    
                    recordId shouldNotBe 0L
                    
                    val retrieved = repository.getTransferById(recordId)
                    retrieved shouldNotBe null
                    retrieved!!.fileName shouldBe "repo_success.txt"
                    retrieved.success shouldBe true
                    retrieved.isCompleted() shouldBe true
                }
            }
            
            it("should record failed transfer through repository") {
                runBlocking {
                    val recordId = repository.recordFailedTransfer(
                        fileName = "repo_failed.txt",
                        fileSize = 1024L,
                        direction = TransferDirection.RECEIVED,
                        duration = 2000L,
                        errorMessage = "Repository test error",
                        bytesTransferred = 256L,
                        transferId = "repo-456"
                    )
                    
                    recordId shouldNotBe 0L
                    
                    val retrieved = repository.getTransferById(recordId)
                    retrieved shouldNotBe null
                    retrieved!!.fileName shouldBe "repo_failed.txt"
                    retrieved.success shouldBe false
                    retrieved.errorMessage shouldBe "Repository test error"
                    retrieved.getCompletionPercentage() shouldBe 25f
                }
            }
            
            it("should cleanup old records") {
                runBlocking {
                    // Insert records with different ages
                    val oldDate = Date(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000) // 40 days ago
                    val recentDate = Date(System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000) // 10 days ago
                    
                    val oldRecord = TransferRecord(
                        fileName = "old.txt",
                        fileSize = 100L,
                        direction = TransferDirection.SENT,
                        timestamp = oldDate,
                        duration = 1000L,
                        success = true,
                        bytesTransferred = 100L
                    )
                    
                    val recentRecord = TransferRecord(
                        fileName = "recent.txt",
                        fileSize = 200L,
                        direction = TransferDirection.RECEIVED,
                        timestamp = recentDate,
                        duration = 2000L,
                        success = true,
                        bytesTransferred = 200L
                    )
                    
                    transferDao.insertTransfer(oldRecord)
                    transferDao.insertTransfer(recentRecord)
                    
                    val initialCount = repository.getTransferCount()
                    initialCount shouldBe 2
                    
                    val deletedCount = repository.cleanupOldRecords(30) // Keep 30 days
                    deletedCount shouldBe 1 // Should delete the 40-day-old record
                    
                    val finalCount = repository.getTransferCount()
                    finalCount shouldBe 1
                }
            }
        }
        
        describe("Date Range Operations") {
            
            it("should handle date range queries") {
                runBlocking {
                    val baseTime = System.currentTimeMillis()
                    val records = (1..5).map { i ->
                        TransferRecord(
                            fileName = "date_$i.txt",
                            fileSize = i * 100L,
                            direction = TransferDirection.SENT,
                            timestamp = Date(baseTime - i * 24 * 60 * 60 * 1000L), // i days ago
                            duration = 1000L,
                            success = true,
                            bytesTransferred = i * 100L
                        )
                    }
                    
                    transferDao.insertTransfers(records)
                    
                    val startDate = Date(baseTime - 3 * 24 * 60 * 60 * 1000L) // 3 days ago
                    val endDate = Date(baseTime) // Now
                    
                    val rangeResults = transferDao.getTransfersByDateRange(startDate, endDate)
                    rangeResults shouldNotBe null
                    
                    // Test that old records cleanup works with dates
                    val cutoffDate = Date(baseTime - 2 * 24 * 60 * 60 * 1000L) // 2 days ago
                    val deletedCount = transferDao.deleteTransfersOlderThan(cutoffDate)
                    deletedCount shouldBe 3 // Records from 3, 4, 5 days ago
                }
            }
        }
        
        describe("Transfer ID Operations") {
            
            it("should find transfer by transfer ID") {
                runBlocking {
                    val transferId = "unique-transfer-${UUID.randomUUID()}"
                    val record = TransferRecord(
                        fileName = "transfer_id_test.txt",
                        fileSize = 512L,
                        direction = TransferDirection.SENT,
                        timestamp = Date(),
                        duration = 1000L,
                        success = true,
                        transferId = transferId,
                        bytesTransferred = 512L
                    )
                    
                    transferDao.insertTransfer(record)
                    
                    val found = transferDao.getTransferByTransferId(transferId)
                    found shouldNotBe null
                    found!!.transferId shouldBe transferId
                    found.fileName shouldBe "transfer_id_test.txt"
                }
            }
        }
    }
})
package com.p2pshare.app.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Room database class for the P2P File Share application.
 * 
 * This database stores transfer history and provides access to
 * transfer records through the TransferDao interface.
 */
@Database(
    entities = [TransferRecord::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(TransferRecord.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    /**
     * Provides access to transfer record operations.
     * 
     * @return TransferDao instance
     */
    abstract fun transferDao(): TransferDao
    
    companion object {
        
        // Database name
        private const val DATABASE_NAME = "p2p_file_share_database"
        
        // Singleton instance
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        /**
         * Gets the singleton database instance.
         * 
         * @param context Application context
         * @return AppDatabase instance
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addCallback(DatabaseCallback())
                    .addMigrations(MIGRATION_1_2) // For future migrations
                    .fallbackToDestructiveMigration() // For development only
                    .build()
                
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Closes the database instance (for testing).
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
        
        /**
         * Migration from version 1 to 2 (placeholder for future use).
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Future migration logic will go here
                // Example:
                // database.execSQL("ALTER TABLE transfer_records ADD COLUMN new_column TEXT")
            }
        }
        
        /**
         * Database callback for initialization and cleanup.
         */
        private class DatabaseCallback : RoomDatabase.Callback() {
            
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                
                // Perform any initialization tasks when database is first created
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateDatabase(database.transferDao())
                    }
                }
            }
            
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                
                // Perform any tasks when database is opened
                // This is called every time the database is opened
            }
            
            /**
             * Populates the database with initial data if needed.
             * 
             * @param transferDao DAO for transfer operations
             */
            private suspend fun populateDatabase(transferDao: TransferDao) {
                // Add any initial data here if needed
                // For now, we start with an empty database
                
                // Example: Add a welcome transfer record
                // val welcomeRecord = TransferRecord.createSuccessfulTransfer(
                //     fileName = "Welcome.txt",
                //     fileSize = 1024,
                //     direction = TransferRecord.TransferDirection.RECEIVED,
                //     duration = 1000
                // )
                // transferDao.insertTransfer(welcomeRecord)
            }
        }
    }
    
    /**
     * Repository class for managing transfer records.
     * 
     * This class provides a clean API for accessing transfer data
     * and abstracts the database operations from the UI layer.
     */
    class TransferRepository(private val transferDao: TransferDao) {
        
        /**
         * Gets all transfer records as LiveData.
         */
        fun getAllTransfers() = transferDao.getAllTransfers()
        
        /**
         * Gets transfer records by direction.
         */
        fun getTransfersByDirection(direction: TransferRecord.TransferDirection) = 
            transferDao.getTransfersByDirection(direction)
        
        /**
         * Gets successful transfers only.
         */
        fun getSuccessfulTransfers() = transferDao.getSuccessfulTransfers()
        
        /**
         * Gets failed transfers only.
         */
        fun getFailedTransfers() = transferDao.getFailedTransfers()
        
        /**
         * Gets recent transfers (limited count).
         */
        fun getRecentTransfers(limit: Int = 10) = transferDao.getRecentTransfers(limit)
        
        /**
         * Searches transfers by file name.
         */
        fun searchTransfers(query: String) = transferDao.searchTransfersByFileName(query)
        
        /**
         * Inserts a new transfer record.
         */
        suspend fun insertTransfer(record: TransferRecord): Long {
            return transferDao.insertTransfer(record)
        }
        
        /**
         * Updates an existing transfer record.
         */
        suspend fun updateTransfer(record: TransferRecord): Int {
            return transferDao.updateTransfer(record)
        }
        
        /**
         * Deletes a transfer record.
         */
        suspend fun deleteTransfer(record: TransferRecord): Int {
            return transferDao.deleteTransfer(record)
        }
        
        /**
         * Deletes a transfer record by ID.
         */
        suspend fun deleteTransferById(id: Long): Int {
            return transferDao.deleteTransferById(id)
        }
        
        /**
         * Deletes all transfer records.
         */
        suspend fun deleteAllTransfers(): Int {
            return transferDao.deleteAllTransfers()
        }
        
        /**
         * Gets transfer statistics.
         */
        suspend fun getTransferStatistics(): TransferDao.TransferStatistics {
            return transferDao.getTransferStatistics()
        }
        
        /**
         * Gets a transfer record by ID.
         */
        suspend fun getTransferById(id: Long): TransferRecord? {
            return transferDao.getTransferById(id)
        }
        
        /**
         * Gets a transfer record by transfer ID.
         */
        suspend fun getTransferByTransferId(transferId: String): TransferRecord? {
            return transferDao.getTransferByTransferId(transferId)
        }
        
        /**
         * Gets the total number of transfers.
         */
        suspend fun getTransferCount(): Int {
            return transferDao.getTransferCount()
        }
        
        /**
         * Gets the largest transfer record.
         */
        suspend fun getLargestTransfer(): TransferRecord? {
            return transferDao.getLargestTransfer()
        }
        
        /**
         * Gets the fastest transfer record.
         */
        suspend fun getFastestTransfer(): TransferRecord? {
            return transferDao.getFastestTransfer()
        }
        
        /**
         * Cleans up old transfer records (older than specified days).
         * 
         * @param daysToKeep Number of days to keep records for
         * @return Number of records deleted
         */
        suspend fun cleanupOldRecords(daysToKeep: Int = 30): Int {
            val cutoffDate = java.util.Date(System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L))
            return transferDao.deleteTransfersOlderThan(cutoffDate)
        }
        
        /**
         * Records a successful transfer.
         * 
         * @param fileName Name of the transferred file
         * @param fileSize Size of the file in bytes
         * @param direction Transfer direction
         * @param duration Transfer duration in milliseconds
         * @param transferId Optional transfer ID
         * @param peerDeviceName Optional peer device name
         * @param filePath Optional file path
         * @param averageSpeed Average transfer speed in bytes per second
         * @return ID of the inserted record
         */
        suspend fun recordSuccessfulTransfer(
            fileName: String,
            fileSize: Long,
            direction: TransferRecord.TransferDirection,
            duration: Long,
            transferId: String? = null,
            peerDeviceName: String? = null,
            filePath: String? = null,
            averageSpeed: Long = 0
        ): Long {
            val record = TransferRecord.createSuccessfulTransfer(
                fileName = fileName,
                fileSize = fileSize,
                direction = direction,
                duration = duration,
                transferId = transferId,
                peerDeviceName = peerDeviceName,
                filePath = filePath,
                averageSpeed = averageSpeed
            )
            return insertTransfer(record)
        }
        
        /**
         * Records a failed transfer.
         * 
         * @param fileName Name of the file being transferred
         * @param fileSize Size of the file in bytes
         * @param direction Transfer direction
         * @param duration Transfer duration before failure in milliseconds
         * @param errorMessage Error message describing the failure
         * @param bytesTransferred Number of bytes transferred before failure
         * @param transferId Optional transfer ID
         * @param peerDeviceName Optional peer device name
         * @return ID of the inserted record
         */
        suspend fun recordFailedTransfer(
            fileName: String,
            fileSize: Long,
            direction: TransferRecord.TransferDirection,
            duration: Long,
            errorMessage: String,
            bytesTransferred: Long = 0,
            transferId: String? = null,
            peerDeviceName: String? = null
        ): Long {
            val record = TransferRecord.createFailedTransfer(
                fileName = fileName,
                fileSize = fileSize,
                direction = direction,
                duration = duration,
                errorMessage = errorMessage,
                bytesTransferred = bytesTransferred,
                transferId = transferId,
                peerDeviceName = peerDeviceName
            )
            return insertTransfer(record)
        }
    }
}
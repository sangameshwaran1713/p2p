package com.p2pshare.app.database

import androidx.lifecycle.LiveData
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.*

/**
 * Data Access Object (DAO) for transfer records.
 * 
 * Provides database operations for transfer history including
 * insertion, querying, filtering, and deletion of transfer records.
 */
@Dao
interface TransferDao {
    
    /**
     * Inserts a new transfer record into the database.
     * 
     * @param record Transfer record to insert
     * @return The ID of the inserted record
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(record: TransferRecord): Long
    
    /**
     * Inserts multiple transfer records into the database.
     * 
     * @param records List of transfer records to insert
     * @return List of IDs of the inserted records
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfers(records: List<TransferRecord>): List<Long>
    
    /**
     * Updates an existing transfer record.
     * 
     * @param record Transfer record to update
     * @return Number of records updated
     */
    @Update
    suspend fun updateTransfer(record: TransferRecord): Int
    
    /**
     * Deletes a transfer record from the database.
     * 
     * @param record Transfer record to delete
     * @return Number of records deleted
     */
    @Delete
    suspend fun deleteTransfer(record: TransferRecord): Int
    
    /**
     * Deletes a transfer record by ID.
     * 
     * @param id ID of the record to delete
     * @return Number of records deleted
     */
    @Query("DELETE FROM transfer_records WHERE id = :id")
    suspend fun deleteTransferById(id: Long): Int
    
    /**
     * Deletes all transfer records from the database.
     * 
     * @return Number of records deleted
     */
    @Query("DELETE FROM transfer_records")
    suspend fun deleteAllTransfers(): Int
    
    /**
     * Deletes transfer records older than the specified date.
     * 
     * @param cutoffDate Date before which records should be deleted
     * @return Number of records deleted
     */
    @Query("DELETE FROM transfer_records WHERE timestamp < :cutoffDate")
    suspend fun deleteTransfersOlderThan(cutoffDate: Date): Int
    
    /**
     * Gets a transfer record by ID.
     * 
     * @param id ID of the record to retrieve
     * @return Transfer record or null if not found
     */
    @Query("SELECT * FROM transfer_records WHERE id = :id")
    suspend fun getTransferById(id: Long): TransferRecord?
    
    /**
     * Gets a transfer record by transfer ID.
     * 
     * @param transferId Transfer ID to search for
     * @return Transfer record or null if not found
     */
    @Query("SELECT * FROM transfer_records WHERE transfer_id = :transferId")
    suspend fun getTransferByTransferId(transferId: String): TransferRecord?
    
    /**
     * Gets all transfer records ordered by timestamp (newest first).
     * 
     * @return LiveData list of all transfer records
     */
    @Query("SELECT * FROM transfer_records ORDER BY timestamp DESC")
    fun getAllTransfers(): LiveData<List<TransferRecord>>
    
    /**
     * Gets all transfer records as Flow ordered by timestamp (newest first).
     * 
     * @return Flow of all transfer records
     */
    @Query("SELECT * FROM transfer_records ORDER BY timestamp DESC")
    fun getAllTransfersFlow(): Flow<List<TransferRecord>>
    
    /**
     * Gets all transfer records synchronously (for suspend functions).
     * 
     * @return List of all transfer records ordered by timestamp descending
     */
    @Query("SELECT * FROM transfer_records ORDER BY timestamp DESC")
    suspend fun getAllTransfersSync(): List<TransferRecord>
    
    /**
     * Gets transfer records by direction (sent/received).
     * 
     * @param direction Transfer direction to filter by
     * @return LiveData list of transfer records
     */
    @Query("SELECT * FROM transfer_records WHERE direction = :direction ORDER BY timestamp DESC")
    fun getTransfersByDirection(direction: TransferRecord.TransferDirection): LiveData<List<TransferRecord>>
    
    /**
     * Gets successful transfer records only.
     * 
     * @return LiveData list of successful transfer records
     */
    @Query("SELECT * FROM transfer_records WHERE success = 1 ORDER BY timestamp DESC")
    fun getSuccessfulTransfers(): LiveData<List<TransferRecord>>
    
    /**
     * Gets failed transfer records only.
     * 
     * @return LiveData list of failed transfer records
     */
    @Query("SELECT * FROM transfer_records WHERE success = 0 ORDER BY timestamp DESC")
    fun getFailedTransfers(): LiveData<List<TransferRecord>>
    
    /**
     * Gets transfer records within a date range.
     * 
     * @param startDate Start date (inclusive)
     * @param endDate End date (inclusive)
     * @return LiveData list of transfer records in the date range
     */
    @Query("SELECT * FROM transfer_records WHERE timestamp BETWEEN :startDate AND :endDate ORDER BY timestamp DESC")
    fun getTransfersByDateRange(startDate: Date, endDate: Date): LiveData<List<TransferRecord>>
    
    /**
     * Gets transfer records for a specific file name.
     * 
     * @param fileName File name to search for
     * @return LiveData list of transfer records for the file
     */
    @Query("SELECT * FROM transfer_records WHERE file_name = :fileName ORDER BY timestamp DESC")
    fun getTransfersByFileName(fileName: String): LiveData<List<TransferRecord>>
    
    /**
     * Searches transfer records by file name (case-insensitive partial match).
     * 
     * @param query Search query for file name
     * @return LiveData list of matching transfer records
     */
    @Query("SELECT * FROM transfer_records WHERE file_name LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchTransfersByFileName(query: String): LiveData<List<TransferRecord>>
    
    /**
     * Gets the total number of transfer records.
     * 
     * @return Total count of transfer records
     */
    @Query("SELECT COUNT(*) FROM transfer_records")
    suspend fun getTransferCount(): Int
    
    /**
     * Gets the count of successful transfers.
     * 
     * @return Count of successful transfers
     */
    @Query("SELECT COUNT(*) FROM transfer_records WHERE success = 1")
    suspend fun getSuccessfulTransferCount(): Int
    
    /**
     * Gets the count of failed transfers.
     * 
     * @return Count of failed transfers
     */
    @Query("SELECT COUNT(*) FROM transfer_records WHERE success = 0")
    suspend fun getFailedTransferCount(): Int
    
    /**
     * Gets the total bytes transferred (successful transfers only).
     * 
     * @return Total bytes transferred
     */
    @Query("SELECT SUM(bytes_transferred) FROM transfer_records WHERE success = 1")
    suspend fun getTotalBytesTransferred(): Long?
    
    /**
     * Gets transfer statistics.
     * 
     * @return Transfer statistics
     */
    @Query("""
        SELECT 
            COUNT(*) as totalTransfers,
            SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as successfulTransfers,
            SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) as failedTransfers,
            SUM(CASE WHEN success = 1 THEN bytes_transferred ELSE 0 END) as totalBytesTransferred,
            AVG(CASE WHEN success = 1 THEN duration ELSE NULL END) as averageDuration,
            AVG(CASE WHEN success = 1 THEN average_speed ELSE NULL END) as averageSpeed
        FROM transfer_records
    """)
    suspend fun getTransferStatistics(): TransferStatistics
    
    /**
     * Gets the most recent transfer records (limited count).
     * 
     * @param limit Maximum number of records to return
     * @return LiveData list of recent transfer records
     */
    @Query("SELECT * FROM transfer_records ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransfers(limit: Int): LiveData<List<TransferRecord>>
    
    /**
     * Gets transfer records for a specific peer device.
     * 
     * @param peerDeviceName Name of the peer device
     * @return LiveData list of transfer records with the peer
     */
    @Query("SELECT * FROM transfer_records WHERE peer_device_name = :peerDeviceName ORDER BY timestamp DESC")
    fun getTransfersByPeerDevice(peerDeviceName: String): LiveData<List<TransferRecord>>
    
    /**
     * Gets the largest file transfer record.
     * 
     * @return Transfer record with the largest file size
     */
    @Query("SELECT * FROM transfer_records WHERE success = 1 ORDER BY file_size DESC LIMIT 1")
    suspend fun getLargestTransfer(): TransferRecord?
    
    /**
     * Gets the fastest transfer record (by average speed).
     * 
     * @return Transfer record with the highest average speed
     */
    @Query("SELECT * FROM transfer_records WHERE success = 1 AND average_speed > 0 ORDER BY average_speed DESC LIMIT 1")
    suspend fun getFastestTransfer(): TransferRecord?
    
    /**
     * Data class for transfer statistics.
     */
    data class TransferStatistics(
        val totalTransfers: Int,
        val successfulTransfers: Int,
        val failedTransfers: Int,
        val totalBytesTransferred: Long,
        val averageDuration: Double?,
        val averageSpeed: Double?
    ) {
        /**
         * Calculates the success rate as a percentage.
         * 
         * @return Success rate (0-100)
         */
        fun getSuccessRate(): Float {
            return if (totalTransfers > 0) {
                (successfulTransfers.toFloat() / totalTransfers) * 100f
            } else {
                0f
            }
        }
        
        /**
         * Gets formatted total bytes transferred.
         * 
         * @return Formatted bytes string
         */
        fun getFormattedTotalBytes(): String {
            return when {
                totalBytesTransferred >= 1024 * 1024 * 1024 -> 
                    String.format("%.1f GB", totalBytesTransferred / (1024.0 * 1024.0 * 1024.0))
                totalBytesTransferred >= 1024 * 1024 -> 
                    String.format("%.1f MB", totalBytesTransferred / (1024.0 * 1024.0))
                totalBytesTransferred >= 1024 -> 
                    String.format("%.1f KB", totalBytesTransferred / 1024.0)
                else -> "$totalBytesTransferred B"
            }
        }
        
        /**
         * Gets formatted average duration.
         * 
         * @return Formatted duration string
         */
        fun getFormattedAverageDuration(): String {
            val avgDurationMs = averageDuration?.toLong() ?: 0L
            val seconds = avgDurationMs / 1000
            return when {
                seconds >= 3600 -> String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
                seconds >= 60 -> String.format("%d:%02d", seconds / 60, seconds % 60)
                else -> "${seconds}s"
            }
        }
        
        /**
         * Gets formatted average speed.
         * 
         * @return Formatted speed string
         */
        fun getFormattedAverageSpeed(): String {
            val avgSpeed = averageSpeed?.toLong() ?: 0L
            return when {
                avgSpeed >= 1024 * 1024 -> String.format("%.1f MB/s", avgSpeed / (1024.0 * 1024.0))
                avgSpeed >= 1024 -> String.format("%.1f KB/s", avgSpeed / 1024.0)
                else -> "$avgSpeed B/s"
            }
        }
    }
}
package com.p2pshare.app.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.*

/**
 * Room entity representing a file transfer record in the database.
 * 
 * This entity stores complete information about each file transfer
 * including metadata, timing, and success status for history tracking.
 */
@Entity(tableName = "transfer_records")
@TypeConverters(TransferRecord.Converters::class)
data class TransferRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "file_name")
    val fileName: String,
    
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    
    @ColumnInfo(name = "direction")
    val direction: TransferDirection,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Date,
    
    @ColumnInfo(name = "duration")
    val duration: Long, // Duration in milliseconds
    
    @ColumnInfo(name = "success")
    val success: Boolean,
    
    @ColumnInfo(name = "transfer_id")
    val transferId: String? = null,
    
    @ColumnInfo(name = "peer_device_name")
    val peerDeviceName: String? = null,
    
    @ColumnInfo(name = "file_path")
    val filePath: String? = null,
    
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    
    @ColumnInfo(name = "bytes_transferred")
    val bytesTransferred: Long = 0,
    
    @ColumnInfo(name = "average_speed")
    val averageSpeed: Long = 0 // Bytes per second
) {
    
    /**
     * Transfer direction enumeration.
     */
    enum class TransferDirection {
        SENT,
        RECEIVED
    }
    
    /**
     * Type converters for Room database.
     */
    class Converters {
        @TypeConverter
        fun fromTimestamp(value: Long?): Date? {
            return value?.let { Date(it) }
        }
        
        @TypeConverter
        fun dateToTimestamp(date: Date?): Long? {
            return date?.time
        }
        
        @TypeConverter
        fun fromTransferDirection(direction: TransferDirection): String {
            return direction.name
        }
        
        @TypeConverter
        fun toTransferDirection(direction: String): TransferDirection {
            return TransferDirection.valueOf(direction)
        }
    }
    
    /**
     * Calculates the transfer completion percentage.
     * 
     * @return Percentage of transfer completed (0-100)
     */
    fun getCompletionPercentage(): Float {
        return if (fileSize > 0) {
            (bytesTransferred.toFloat() / fileSize) * 100f
        } else {
            0f
        }
    }
    
    /**
     * Checks if the transfer was completed successfully.
     * 
     * @return true if transfer completed successfully, false otherwise
     */
    fun isCompleted(): Boolean {
        return success && bytesTransferred == fileSize
    }
    
    /**
     * Gets a human-readable status string.
     * 
     * @return Status description
     */
    fun getStatusDescription(): String {
        return when {
            success && isCompleted() -> "Completed"
            success && !isCompleted() -> "Partial"
            !success && errorMessage != null -> "Failed: $errorMessage"
            !success -> "Failed"
            else -> "Unknown"
        }
    }
    
    /**
     * Formats the file size for display.
     * 
     * @return Formatted file size string
     */
    fun getFormattedFileSize(): String {
        return formatBytes(fileSize)
    }
    
    /**
     * Formats the transferred bytes for display.
     * 
     * @return Formatted transferred bytes string
     */
    fun getFormattedBytesTransferred(): String {
        return formatBytes(bytesTransferred)
    }
    
    /**
     * Formats the average speed for display.
     * 
     * @return Formatted speed string
     */
    fun getFormattedAverageSpeed(): String {
        return when {
            averageSpeed >= 1024 * 1024 -> String.format("%.1f MB/s", averageSpeed / (1024.0 * 1024.0))
            averageSpeed >= 1024 -> String.format("%.1f KB/s", averageSpeed / 1024.0)
            else -> "$averageSpeed B/s"
        }
    }
    
    /**
     * Formats the transfer duration for display.
     * 
     * @return Formatted duration string
     */
    fun getFormattedDuration(): String {
        val seconds = duration / 1000
        return when {
            seconds >= 3600 -> String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            seconds >= 60 -> String.format("%d:%02d", seconds / 60, seconds % 60)
            else -> "${seconds}s"
        }
    }
    
    /**
     * Gets the transfer direction as a display string.
     * 
     * @return Direction string for display
     */
    fun getDirectionString(): String {
        return when (direction) {
            TransferDirection.SENT -> "Sent"
            TransferDirection.RECEIVED -> "Received"
        }
    }
    
    /**
     * Creates a summary string for the transfer.
     * 
     * @return Summary description
     */
    fun getSummary(): String {
        val directionStr = getDirectionString().lowercase()
        val sizeStr = getFormattedFileSize()
        val statusStr = getStatusDescription()
        return "$directionStr $fileName ($sizeStr) - $statusStr"
    }
    
    companion object {
        /**
         * Formats bytes for human-readable display.
         * 
         * @param bytes Number of bytes
         * @return Formatted string
         */
        private fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
        
        /**
         * Creates a TransferRecord for a successful transfer.
         * 
         * @param fileName Name of the transferred file
         * @param fileSize Size of the file in bytes
         * @param direction Transfer direction (sent/received)
         * @param duration Transfer duration in milliseconds
         * @param transferId Optional transfer ID
         * @param peerDeviceName Optional peer device name
         * @param filePath Optional file path
         * @param averageSpeed Average transfer speed in bytes per second
         * @return TransferRecord instance
         */
        fun createSuccessfulTransfer(
            fileName: String,
            fileSize: Long,
            direction: TransferDirection,
            duration: Long,
            transferId: String? = null,
            peerDeviceName: String? = null,
            filePath: String? = null,
            averageSpeed: Long = 0
        ): TransferRecord {
            return TransferRecord(
                fileName = fileName,
                fileSize = fileSize,
                direction = direction,
                timestamp = Date(),
                duration = duration,
                success = true,
                transferId = transferId,
                peerDeviceName = peerDeviceName,
                filePath = filePath,
                bytesTransferred = fileSize,
                averageSpeed = averageSpeed
            )
        }
        
        /**
         * Creates a TransferRecord for a failed transfer.
         * 
         * @param fileName Name of the file being transferred
         * @param fileSize Size of the file in bytes
         * @param direction Transfer direction (sent/received)
         * @param duration Transfer duration before failure in milliseconds
         * @param errorMessage Error message describing the failure
         * @param bytesTransferred Number of bytes transferred before failure
         * @param transferId Optional transfer ID
         * @param peerDeviceName Optional peer device name
         * @return TransferRecord instance
         */
        fun createFailedTransfer(
            fileName: String,
            fileSize: Long,
            direction: TransferDirection,
            duration: Long,
            errorMessage: String,
            bytesTransferred: Long = 0,
            transferId: String? = null,
            peerDeviceName: String? = null
        ): TransferRecord {
            return TransferRecord(
                fileName = fileName,
                fileSize = fileSize,
                direction = direction,
                timestamp = Date(),
                duration = duration,
                success = false,
                transferId = transferId,
                peerDeviceName = peerDeviceName,
                errorMessage = errorMessage,
                bytesTransferred = bytesTransferred,
                averageSpeed = if (duration > 0) (bytesTransferred * 1000) / duration else 0
            )
        }
    }
}
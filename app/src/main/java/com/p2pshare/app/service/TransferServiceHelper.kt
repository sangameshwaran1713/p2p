package com.p2pshare.app.service

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Helper class for creating TransferService intents and managing service interactions.
 * 
 * This class provides convenient methods for starting send/receive transfers
 * and managing the TransferService lifecycle.
 */
object TransferServiceHelper {
    
    /**
     * Creates an intent to start a file send transfer.
     * 
     * @param context Application context
     * @param file File to send
     * @return Intent configured for send transfer
     */
    fun createSendIntent(context: Context, file: File): Intent {
        return Intent(context, TransferService::class.java).apply {
            action = TransferService.ACTION_START_SEND
            putExtra(TransferService.EXTRA_FILE_PATH, file.absolutePath)
        }
    }
    
    /**
     * Creates an intent to start a file receive transfer.
     * 
     * @param context Application context
     * @param senderIp IP address of the sender
     * @param senderPort Port of the sender's server
     * @param outputDirectory Directory to save received files
     * @param transferId Optional transfer ID for validation
     * @return Intent configured for receive transfer
     */
    fun createReceiveIntent(
        context: Context,
        senderIp: String,
        senderPort: Int,
        outputDirectory: File,
        transferId: String? = null
    ): Intent {
        return Intent(context, TransferService::class.java).apply {
            action = TransferService.ACTION_START_RECEIVE
            putExtra(TransferService.EXTRA_SENDER_IP, senderIp)
            putExtra(TransferService.EXTRA_SENDER_PORT, senderPort)
            putExtra(TransferService.EXTRA_OUTPUT_DIRECTORY, outputDirectory.absolutePath)
            transferId?.let { putExtra(TransferService.EXTRA_TRANSFER_ID, it) }
        }
    }
    
    /**
     * Creates an intent to cancel the current transfer.
     * 
     * @param context Application context
     * @return Intent configured for transfer cancellation
     */
    fun createCancelIntent(context: Context): Intent {
        return Intent(context, TransferService::class.java).apply {
            action = TransferService.ACTION_CANCEL_TRANSFER
        }
    }
    
    /**
     * Starts a file send transfer using the TransferService.
     * 
     * @param context Application context
     * @param file File to send
     */
    fun startSendTransfer(context: Context, file: File) {
        val intent = createSendIntent(context, file)
        context.startForegroundService(intent)
    }
    
    /**
     * Starts a file receive transfer using the TransferService.
     * 
     * @param context Application context
     * @param senderIp IP address of the sender
     * @param senderPort Port of the sender's server
     * @param outputDirectory Directory to save received files
     * @param transferId Optional transfer ID for validation
     */
    fun startReceiveTransfer(
        context: Context,
        senderIp: String,
        senderPort: Int,
        outputDirectory: File,
        transferId: String? = null
    ) {
        val intent = createReceiveIntent(context, senderIp, senderPort, outputDirectory, transferId)
        context.startForegroundService(intent)
    }
    
    /**
     * Cancels the current transfer.
     * 
     * @param context Application context
     */
    fun cancelTransfer(context: Context) {
        val intent = createCancelIntent(context)
        context.startService(intent)
    }
    
    /**
     * Stops the TransferService.
     * 
     * @param context Application context
     */
    fun stopTransferService(context: Context) {
        val intent = Intent(context, TransferService::class.java)
        context.stopService(intent)
    }
    
    /**
     * Validates file for sending.
     * 
     * @param file File to validate
     * @return true if file is valid for sending, false otherwise
     */
    fun isValidFileForSending(file: File): Boolean {
        return file.exists() && file.isFile && file.canRead() && file.length() > 0
    }
    
    /**
     * Validates output directory for receiving.
     * 
     * @param directory Directory to validate
     * @return true if directory is valid for receiving, false otherwise
     */
    fun isValidOutputDirectory(directory: File): Boolean {
        return (directory.exists() && directory.isDirectory && directory.canWrite()) ||
                (!directory.exists() && directory.parentFile?.canWrite() == true)
    }
    
    /**
     * Validates network parameters for receiving.
     * 
     * @param ip IP address to validate
     * @param port Port to validate
     * @return true if parameters are valid, false otherwise
     */
    fun isValidNetworkParameters(ip: String, port: Int): Boolean {
        return ip.isNotBlank() && 
               port in 1024..65535 && 
               isValidIpAddress(ip)
    }
    
    /**
     * Validates IP address format.
     * 
     * @param ip IP address to validate
     * @return true if IP address is valid, false otherwise
     */
    private fun isValidIpAddress(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false
            
            parts.all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Formats file size for display.
     * 
     * @param bytes File size in bytes
     * @return Formatted file size string
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
    
    /**
     * Formats transfer speed for display.
     * 
     * @param bytesPerSecond Transfer speed in bytes per second
     * @return Formatted speed string
     */
    fun formatTransferSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024 -> String.format("%.1f KB/s", bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }
    
    /**
     * Formats time duration for display.
     * 
     * @param milliseconds Duration in milliseconds
     * @return Formatted duration string
     */
    fun formatDuration(milliseconds: Long): String {
        if (milliseconds <= 0) return "0s"
        
        val seconds = milliseconds / 1000
        return when {
            seconds >= 3600 -> String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            seconds >= 60 -> String.format("%d:%02d", seconds / 60, seconds % 60)
            else -> "${seconds}s"
        }
    }
    
    /**
     * Gets the default download directory for the device.
     * 
     * @param context Application context
     * @return Default download directory
     */
    fun getDefaultDownloadDirectory(context: Context): File {
        return File(context.getExternalFilesDir(null), "Downloads").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
}
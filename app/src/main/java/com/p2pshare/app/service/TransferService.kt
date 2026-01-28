package com.p2pshare.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.p2pshare.app.R
import com.p2pshare.app.transfer.FileReceiver
import com.p2pshare.app.transfer.FileSender
import kotlinx.coroutines.*
import java.io.File
import java.util.*

/**
 * Foreground service for handling P2P file transfers in the background.
 * 
 * This service provides:
 * - Background file transfer execution using FileSender/FileReceiver
 * - Persistent notification with transfer progress
 * - LiveData for progress monitoring by UI components
 * - Service lifecycle management for transfer operations
 * - Automatic cleanup on transfer completion or cancellation
 * 
 * The service runs as a foreground service to ensure transfers continue
 * even when the app is not in the foreground.
 */
class TransferService : Service() {
    
    companion object {
        private const val TAG = "TransferService"
        
        // Notification constants
        private const val NOTIFICATION_CHANNEL_ID = "transfer_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "File Transfers"
        private const val NOTIFICATION_ID = 1001
        
        // Intent actions
        const val ACTION_START_SEND = "com.p2pshare.app.START_SEND"
        const val ACTION_START_RECEIVE = "com.p2pshare.app.START_RECEIVE"
        const val ACTION_CANCEL_TRANSFER = "com.p2pshare.app.CANCEL_TRANSFER"
        
        // Intent extras
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_SENDER_IP = "sender_ip"
        const val EXTRA_SENDER_PORT = "sender_port"
        const val EXTRA_TRANSFER_ID = "transfer_id"
        const val EXTRA_OUTPUT_DIRECTORY = "output_directory"
        
        // Update intervals
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L // 0.5 seconds
    }
    
    // Service binding
    private val binder = TransferBinder()
    
    // Coroutine scope for service operations
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Transfer components
    private var fileSender: FileSender? = null
    private var fileReceiver: FileReceiver? = null
    private var currentTransferJob: Job? = null
    
    // Progress monitoring
    private val _transferProgress = MutableLiveData<TransferProgress>()
    val transferProgress: LiveData<TransferProgress> = _transferProgress
    
    private val _transferState = MutableLiveData<TransferState>()
    val transferState: LiveData<TransferState> = _transferState
    
    private var progressUpdateJob: Job? = null
    
    /**
     * Transfer state enumeration.
     */
    enum class TransferState {
        IDLE,
        PREPARING,
        CONNECTING,
        TRANSFERRING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    /**
     * Transfer progress data class.
     */
    data class TransferProgress(
        val transferType: TransferType = TransferType.NONE,
        val fileName: String = "",
        val bytesTransferred: Long = 0L,
        val totalBytes: Long = 0L,
        val progressPercentage: Float = 0f,
        val speedBytesPerSecond: Long = 0L,
        val estimatedTimeRemainingMs: Long = 0L,
        val chunksTransferred: Int = 0,
        val totalChunks: Int = 0,
        val errorMessage: String? = null
    )
    
    /**
     * Transfer type enumeration.
     */
    enum class TransferType {
        NONE,
        SENDING,
        RECEIVING
    }
    
    /**
     * Service binder for local binding.
     */
    inner class TransferBinder : Binder() {
        fun getService(): TransferService = this@TransferService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        _transferState.value = TransferState.IDLE
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SEND -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                if (filePath != null) {
                    startSendTransfer(File(filePath))
                } else {
                    stopSelfWithError("File path not provided for send transfer")
                }
            }
            
            ACTION_START_RECEIVE -> {
                val senderIp = intent.getStringExtra(EXTRA_SENDER_IP)
                val senderPort = intent.getIntExtra(EXTRA_SENDER_PORT, -1)
                val outputDirPath = intent.getStringExtra(EXTRA_OUTPUT_DIRECTORY)
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
                
                if (senderIp != null && senderPort > 0 && outputDirPath != null) {
                    startReceiveTransfer(senderIp, senderPort, File(outputDirPath), transferId)
                } else {
                    stopSelfWithError("Invalid parameters for receive transfer")
                }
            }
            
            ACTION_CANCEL_TRANSFER -> {
                cancelCurrentTransfer()
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onDestroy() {
        super.onDestroy()
        cancelCurrentTransfer()
        serviceScope.cancel()
    }
    
    /**
     * Starts a file send transfer.
     */
    private fun startSendTransfer(file: File) {
        if (currentTransferJob?.isActive == true) {
            return // Transfer already in progress
        }
        
        _transferState.value = TransferState.PREPARING
        
        currentTransferJob = serviceScope.launch {
            try {
                fileSender = FileSender(file).also { sender ->
                    
                    // Start foreground service with initial notification
                    startForegroundWithNotification(
                        TransferProgress(
                            transferType = TransferType.SENDING,
                            fileName = file.name,
                            totalBytes = file.length()
                        )
                    )
                    
                    // Start progress monitoring
                    startProgressMonitoring(sender = sender)
                    
                    _transferState.postValue(TransferState.CONNECTING)
                    
                    // Start server and wait for connection
                    val port = sender.startServer()
                    sender.waitForConnectionAndTransfer()
                    
                    _transferState.postValue(TransferState.COMPLETED)
                    stopProgressMonitoring()
                    
                    // Update final notification
                    updateNotification(
                        _transferProgress.value?.copy(
                            progressPercentage = 100f
                        ) ?: TransferProgress()
                    )
                    
                    // Stop service after a delay to show completion
                    delay(3000)
                    stopSelf()
                }
                
            } catch (e: Exception) {
                handleTransferError("Send transfer failed: ${e.message}")
            }
        }
    }
    
    /**
     * Starts a file receive transfer.
     */
    private fun startReceiveTransfer(
        senderIp: String, 
        senderPort: Int, 
        outputDirectory: File,
        transferId: String?
    ) {
        if (currentTransferJob?.isActive == true) {
            return // Transfer already in progress
        }
        
        _transferState.value = TransferState.PREPARING
        
        currentTransferJob = serviceScope.launch {
            try {
                fileReceiver = FileReceiver(outputDirectory).also { receiver ->
                    
                    // Start foreground service with initial notification
                    startForegroundWithNotification(
                        TransferProgress(
                            transferType = TransferType.RECEIVING,
                            fileName = "Connecting..."
                        )
                    )
                    
                    // Start progress monitoring
                    startProgressMonitoring(receiver = receiver)
                    
                    _transferState.postValue(TransferState.CONNECTING)
                    
                    // Connect and receive file
                    val receivedFile = receiver.connectAndReceiveFile(senderIp, senderPort, transferId)
                    
                    _transferState.postValue(TransferState.COMPLETED)
                    stopProgressMonitoring()
                    
                    // Update final notification
                    updateNotification(
                        _transferProgress.value?.copy(
                            progressPercentage = 100f,
                            fileName = receivedFile.name
                        ) ?: TransferProgress()
                    )
                    
                    // Stop service after a delay to show completion
                    delay(3000)
                    stopSelf()
                }
                
            } catch (e: Exception) {
                handleTransferError("Receive transfer failed: ${e.message}")
            }
        }
    }
    
    /**
     * Starts monitoring transfer progress and updating LiveData/notification.
     */
    private fun startProgressMonitoring(sender: FileSender? = null, receiver: FileReceiver? = null) {
        progressUpdateJob = serviceScope.launch {
            while (isActive) {
                try {
                    val progress = when {
                        sender != null -> {
                            val senderProgress = sender.progress.value
                            val manifest = sender.getManifest()
                            
                            TransferProgress(
                                transferType = TransferType.SENDING,
                                fileName = manifest?.fileName ?: "",
                                bytesTransferred = senderProgress.bytesTransferred,
                                totalBytes = senderProgress.totalBytes,
                                progressPercentage = senderProgress.progressPercentage,
                                speedBytesPerSecond = senderProgress.speedBytesPerSecond,
                                estimatedTimeRemainingMs = senderProgress.estimatedTimeRemainingMs,
                                chunksTransferred = senderProgress.chunksTransferred,
                                totalChunks = senderProgress.totalChunks
                            )
                        }
                        
                        receiver != null -> {
                            val receiverProgress = receiver.progress.value
                            
                            TransferProgress(
                                transferType = TransferType.RECEIVING,
                                fileName = receiverProgress.fileName,
                                bytesTransferred = receiverProgress.bytesReceived,
                                totalBytes = receiverProgress.totalBytes,
                                progressPercentage = receiverProgress.progressPercentage,
                                speedBytesPerSecond = receiverProgress.speedBytesPerSecond,
                                estimatedTimeRemainingMs = receiverProgress.estimatedTimeRemainingMs,
                                chunksTransferred = receiverProgress.chunksReceived,
                                totalChunks = receiverProgress.totalChunks
                            )
                        }
                        
                        else -> TransferProgress()
                    }
                    
                    _transferProgress.postValue(progress)
                    updateNotification(progress)
                    
                    // Update transfer state based on progress
                    if (progress.progressPercentage > 0f && _transferState.value == TransferState.CONNECTING) {
                        _transferState.postValue(TransferState.TRANSFERRING)
                    }
                    
                    delay(PROGRESS_UPDATE_INTERVAL_MS)
                    
                } catch (e: Exception) {
                    // Continue monitoring even if individual update fails
                }
            }
        }
    }
    
    /**
     * Stops progress monitoring.
     */
    private fun stopProgressMonitoring() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }
    
    /**
     * Cancels the current transfer.
     */
    private fun cancelCurrentTransfer() {
        fileSender?.cancelTransfer()
        fileReceiver?.cancelTransfer()
        
        currentTransferJob?.cancel()
        stopProgressMonitoring()
        
        _transferState.value = TransferState.CANCELLED
        
        // Update notification to show cancellation
        updateNotification(
            _transferProgress.value?.copy(
                errorMessage = "Transfer cancelled"
            ) ?: TransferProgress()
        )
        
        // Stop service after a delay
        serviceScope.launch {
            delay(2000)
            stopSelf()
        }
    }
    
    /**
     * Handles transfer errors.
     */
    private fun handleTransferError(errorMessage: String) {
        _transferState.value = TransferState.FAILED
        stopProgressMonitoring()
        
        _transferProgress.value = _transferProgress.value?.copy(
            errorMessage = errorMessage
        ) ?: TransferProgress(errorMessage = errorMessage)
        
        updateNotification(_transferProgress.value!!)
        
        // Stop service after a delay to show error
        serviceScope.launch {
            delay(5000)
            stopSelf()
        }
    }
    
    /**
     * Stops the service with an error message.
     */
    private fun stopSelfWithError(errorMessage: String) {
        handleTransferError(errorMessage)
    }
    
    /**
     * Creates the notification channel for transfer notifications.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for file transfer progress"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Starts the foreground service with initial notification.
     */
    private fun startForegroundWithNotification(progress: TransferProgress) {
        val notification = createNotification(progress)
        startForeground(NOTIFICATION_ID, notification)
    }
    
    /**
     * Updates the notification with current progress.
     */
    private fun updateNotification(progress: TransferProgress) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Creates a notification for the current transfer progress.
     */
    private fun createNotification(progress: TransferProgress): Notification {
        val title = when (progress.transferType) {
            TransferType.SENDING -> "Sending file"
            TransferType.RECEIVING -> "Receiving file"
            TransferType.NONE -> "File transfer"
        }
        
        val contentText = when {
            progress.errorMessage != null -> progress.errorMessage
            progress.fileName.isNotEmpty() -> progress.fileName
            else -> "Preparing transfer..."
        }
        
        // Create cancel intent
        val cancelIntent = Intent(this, TransferService::class.java).apply {
            action = ACTION_CANCEL_TRANSFER
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_send) // Using existing icon
            .setOngoing(progress.errorMessage == null && _transferState.value != TransferState.COMPLETED)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_send, // Cancel icon (reusing existing)
                "Cancel",
                cancelPendingIntent
            )
        
        // Add progress bar if transfer is active
        if (progress.totalBytes > 0 && progress.errorMessage == null) {
            val progressInt = progress.progressPercentage.toInt()
            builder.setProgress(100, progressInt, false)
            
            // Add speed and ETA info
            if (progress.speedBytesPerSecond > 0) {
                val speedText = formatSpeed(progress.speedBytesPerSecond)
                val etaText = formatEta(progress.estimatedTimeRemainingMs)
                builder.setSubText("$speedText • $etaText")
            }
        } else if (progress.errorMessage == null && progress.totalBytes == 0L) {
            // Indeterminate progress for preparation phase
            builder.setProgress(0, 0, true)
        }
        
        return builder.build()
    }
    
    /**
     * Formats transfer speed for display.
     */
    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024 -> String.format("%.1f KB/s", bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }
    
    /**
     * Formats estimated time remaining for display.
     */
    private fun formatEta(etaMs: Long): String {
        if (etaMs <= 0) return "Calculating..."
        
        val seconds = etaMs / 1000
        return when {
            seconds >= 3600 -> String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            seconds >= 60 -> String.format("%d:%02d", seconds / 60, seconds % 60)
            else -> "${seconds}s"
        }
    }
    
    /**
     * Gets the current transfer progress.
     */
    fun getCurrentProgress(): TransferProgress? {
        return _transferProgress.value
    }
    
    /**
     * Gets the current transfer state.
     */
    fun getCurrentState(): TransferState? {
        return _transferState.value
    }
    
    /**
     * Checks if a transfer is currently active.
     */
    fun isTransferActive(): Boolean {
        return currentTransferJob?.isActive == true
    }
    
    /**
     * Gets the sender's server port (if sending).
     */
    fun getSenderPort(): Int {
        return fileSender?.getServerPort() ?: -1
    }
    
    /**
     * Gets the sender's public key (if sending).
     */
    fun getSenderPublicKey(): String? {
        return fileSender?.getPublicKeyBase64()
    }
}
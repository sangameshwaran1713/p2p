package com.p2pshare.app.integration

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.p2pshare.app.crypto.EcdhHelper
import com.p2pshare.app.database.AppDatabase
import com.p2pshare.app.database.TransferRecord
import com.p2pshare.app.permissions.PermissionManager
import com.p2pshare.app.qr.QrGenerator
import com.p2pshare.app.security.SecureKeyManager
import com.p2pshare.app.service.TransferService
import com.p2pshare.app.service.TransferServiceHelper
import com.p2pshare.app.transfer.FileManifest
import com.p2pshare.app.wifi.WifiDirectCallback
import com.p2pshare.app.wifi.WifiDirectManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.util.*
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo

/**
 * Main controller for P2P file sharing operations.
 * 
 * This class integrates all modules to provide end-to-end functionality:
 * - Wi-Fi Direct connection management
 * - QR code generation and session management
 * - File transfer coordination
 * - Database operations
 * - Security and permission handling
 */
class P2PFileShareController(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val qrGenerator: QrGenerator,
    private val transferServiceHelper: TransferServiceHelper,
    private val database: AppDatabase
) {

    companion object {
        private const val TAG = "P2PFileShareController"
        private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val ecdhHelper = EcdhHelper()
    private val secureKeyManager = SecureKeyManager.getInstance()
    
    // Current session state
    private var currentSession: FileShareSession? = null
    private val _sessionState = MutableLiveData<SessionState>()
    val sessionState: LiveData<SessionState> = _sessionState

    // Transfer progress
    private val _transferProgress = MutableLiveData<TransferProgress>()
    val transferProgress: LiveData<TransferProgress> = _transferProgress

    /**
     * Session state enumeration.
     */
    enum class SessionState {
        IDLE,
        CREATING_GROUP,
        WAITING_FOR_CONNECTION,
        CONNECTED,
        TRANSFERRING,
        COMPLETED,
        ERROR
    }

    /**
     * Transfer progress data.
     */
    data class TransferProgress(
        val bytesTransferred: Long,
        val totalBytes: Long,
        val transferSpeed: Long, // bytes per second
        val eta: Long, // estimated time remaining in milliseconds
        val fileName: String
    ) {
        val progressPercentage: Int get() = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt() else 0
    }

    /**
     * File sharing session data.
     */
    private data class FileShareSession(
        val sessionId: String,
        val keyPair: KeyPair,
        val isGroupOwner: Boolean,
        val fileUri: Uri? = null,
        val manifest: FileManifest? = null,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > SESSION_TIMEOUT_MS
    }

    // Create Wi-Fi Direct manager with callback
    private val wifiDirectManager = WifiDirectManager(context, object : WifiDirectCallback {
            override fun onGroupCreated(groupInfo: WifiP2pGroup) {
                handleGroupCreated(groupInfo.owner?.deviceAddress ?: "")
            }

            override fun onPeerAvailable(peer: WifiP2pDevice) {
                handlePeerAvailable(peer)
            }

            override fun onConnected(connectionInfo: WifiP2pInfo) {
                handlePeerConnected(connectionInfo.groupOwnerAddress?.hostAddress ?: "")
            }

            override fun onDisconnected() {
                handlePeerDisconnected()
            }

            override fun onWifiDirectStateChanged(isEnabled: Boolean) {
                if (!isEnabled) {
                    handleWifiDirectError("Wi-Fi Direct is disabled")
                }
            }

            override fun onError(error: String) {
                handleWifiDirectError(error)
            }
        })

    init {
        // Initialize session state
        _sessionState.value = SessionState.IDLE
    }

    /**
     * Starts a file sending session.
     */
    suspend fun startSendSession(fileUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Validate permissions
            if (!permissionManager.areCriticalPermissionsGranted()) {
                return@withContext Result.failure(Exception("Critical permissions not granted"))
            }

            // Generate session key pair
            val keyPair = ecdhHelper.generateKeyPair()
            val sessionId = UUID.randomUUID().toString()

            // Create file manifest
            val file = getFileFromUri(fileUri)
            val manifest = FileManifest.fromFile(file, sessionId)

            // Create session
            currentSession = FileShareSession(
                sessionId = sessionId,
                keyPair = keyPair,
                isGroupOwner = true,
                fileUri = fileUri,
                manifest = manifest
            )

            // Start Wi-Fi Direct group
            _sessionState.postValue(SessionState.CREATING_GROUP)
            wifiDirectManager.createGroup()

            Result.success(sessionId)
        } catch (e: Exception) {
            _sessionState.postValue(SessionState.ERROR)
            Result.failure(e)
        }
    }

    /**
     * Generates QR code for the current session.
     */
    suspend fun generateSessionQrCode(): Result<android.graphics.Bitmap> = withContext(Dispatchers.IO) {
        try {
            val session = currentSession ?: return@withContext Result.failure(Exception("No active session"))
            
            // Create session info JSON
            val sessionInfo = JSONObject().apply {
                put("sessionId", session.sessionId)
                put("publicKey", Base64.getEncoder().encodeToString(session.keyPair.public.encoded))
                put("fileName", session.manifest?.fileName ?: "")
                put("fileSize", session.manifest?.fileSize ?: 0)
                put("timestamp", System.currentTimeMillis())
            }

            val qrBitmap = qrGenerator.generateQrCode(sessionInfo.toString())
            if (qrBitmap != null) {
                Result.success(qrBitmap)
            } else {
                Result.failure(Exception("Failed to generate QR code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Starts a file receiving session by scanning QR code.
     */
    suspend fun startReceiveSession(qrData: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Validate permissions
            if (!permissionManager.areCriticalPermissionsGranted()) {
                return@withContext Result.failure(Exception("Critical permissions not granted"))
            }

            // Parse QR data
            val sessionInfo = JSONObject(qrData)
            val sessionId = sessionInfo.getString("sessionId")
            val peerPublicKeyBytes = Base64.getDecoder().decode(sessionInfo.getString("publicKey"))
            
            // Generate our key pair
            val keyPair = ecdhHelper.generateKeyPair()

            // Create session
            currentSession = FileShareSession(
                sessionId = sessionId,
                keyPair = keyPair,
                isGroupOwner = false
            )

            // Connect to peer's group
            _sessionState.postValue(SessionState.WAITING_FOR_CONNECTION)
            wifiDirectManager.startPeerDiscovery()

            Result.success(sessionId)
        } catch (e: Exception) {
            _sessionState.postValue(SessionState.ERROR)
            Result.failure(e)
        }
    }

    /**
     * Starts the actual file transfer.
     */
    suspend fun startTransfer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = currentSession ?: return@withContext Result.failure(Exception("No active session"))
            
            _sessionState.postValue(SessionState.TRANSFERRING)

            if (session.isGroupOwner) {
                // Start sending service
                session.fileUri?.let { uri ->
                    val file = getFileFromUri(uri)
                    transferServiceHelper.startSendTransfer(context, file)
                }
            } else {
                // Receiver logic is handled in handlePeerConnected() when connection info becomes available
                // The receive transfer will start automatically when Wi-Fi Direct connection is established
            }

            // Monitor transfer progress
            monitorTransferProgress()

            Result.success(Unit)
        } catch (e: Exception) {
            _sessionState.postValue(SessionState.ERROR)
            Result.failure(e)
        }
    }

    /**
     * Cancels the current transfer.
     */
    suspend fun cancelTransfer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            transferServiceHelper.cancelTransfer(context)
            cleanupSession()
            _sessionState.postValue(SessionState.IDLE)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets transfer history from database.
     */
    suspend fun getTransferHistory(): List<TransferRecord> = withContext(Dispatchers.IO) {
        database.transferDao().getAllTransfersSync()
    }

    /**
     * Clears transfer history.
     */
    suspend fun clearTransferHistory(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.transferDao().deleteAllTransfers()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Validates offline operation capability.
     */
    fun validateOfflineOperation(): Boolean {
        return try {
            // Check if Wi-Fi Direct is available
            val wifiDirectAvailable = wifiDirectManager.initialize()
            
            // Check if cryptographic functions work
            val keyPair = ecdhHelper.generateKeyPair()
            val testSecret = ecdhHelper.computeSharedSecret(keyPair.private, keyPair.public)
            
            // Cleanup test keys
            ecdhHelper.clearPrivateKey(keyPair.private)
            ecdhHelper.clearSharedSecret(testSecret)
            
            wifiDirectAvailable
        } catch (e: Exception) {
            false
        }
    }

    // Private helper methods

    private fun handleGroupCreated(groupOwnerAddress: String) {
        _sessionState.postValue(SessionState.WAITING_FOR_CONNECTION)
    }

    private fun handlePeerAvailable(peer: WifiP2pDevice) {
        // Auto-connect to first available peer when receiving
        currentSession?.let { session ->
            if (!session.isGroupOwner) {
                wifiDirectManager.connectToPeer(peer)
            }
        }
    }

    private fun handlePeerConnected(peerAddress: String) {
        _sessionState.postValue(SessionState.CONNECTED)
        
        // Store connection info for receiver
        val session = currentSession
        if (session != null && !session.isGroupOwner) {
            // For receivers, start the receive transfer when connected
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Use default port for P2P file transfer
                    val senderPort = 8888
                    val outputDirectory = File(context.getExternalFilesDir(null), "received_files")
                    if (!outputDirectory.exists()) {
                        outputDirectory.mkdirs()
                    }
                    
                    transferServiceHelper.startReceiveTransfer(
                        context, 
                        peerAddress, 
                        senderPort, 
                        outputDirectory, 
                        session.sessionId
                    )
                } catch (e: Exception) {
                    _sessionState.postValue(SessionState.ERROR)
                }
            }
        }
    }

    private fun handlePeerDisconnected() {
        if (_sessionState.value != SessionState.COMPLETED) {
            _sessionState.postValue(SessionState.ERROR)
        }
        cleanupSession()
    }

    private fun handleWifiDirectError(error: String) {
        _sessionState.postValue(SessionState.ERROR)
        cleanupSession()
    }

    private suspend fun monitorTransferProgress() {
        // This would integrate with TransferService to get real-time progress
        // For now, we'll simulate progress monitoring
        val session = currentSession ?: return
        
        // In a real implementation, this would observe TransferService LiveData
        // and update _transferProgress accordingly
    }

    private fun cleanupSession() {
        currentSession?.let { session ->
            // Clear cryptographic keys
            ecdhHelper.clearPrivateKey(session.keyPair.private)
            
            // Clear session data
            currentSession = null
        }
        
        // Perform key manager maintenance
        secureKeyManager.performMaintenance()
    }

    private fun getFileFromUri(uri: Uri): File {
        // This is a simplified implementation
        // In a real app, you'd handle content:// URIs properly
        return File(uri.path ?: throw IllegalArgumentException("Invalid file URI"))
    }

    /**
     * Cleanup resources when controller is no longer needed.
     */
    fun cleanup() {
        cleanupSession()
        wifiDirectManager.cleanup()
    }
}
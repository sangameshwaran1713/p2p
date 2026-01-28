package com.p2pshare.app.transfer

import com.p2pshare.app.crypto.AesGcmCipher
import com.p2pshare.app.crypto.EcdhHelper
import com.p2pshare.app.crypto.Hkdf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.PublicKey
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * File sender implementation using ServerSocket for P2P file transfer.
 * 
 * This class handles:
 * - TCP server socket creation and management
 * - ECDH key exchange with receiver
 * - File manifest transmission
 * - Parallel chunk transmission using coroutines
 * - Progress tracking and speed calculation
 * - Encryption of all transmitted data
 * 
 * The sender acts as a TCP server that receivers connect to.
 */
class FileSender(
    private val file: File,
    private val transferId: String = UUID.randomUUID().toString()
) {
    
    companion object {
        private const val TAG = "FileSender"
        private const val DEFAULT_PORT = 8080
        private const val CONNECTION_TIMEOUT_MS = 30000 // 30 seconds
        private const val SOCKET_TIMEOUT_MS = 10000 // 10 seconds
        private const val MAX_PARALLEL_CHUNKS = 4
        private const val SPEED_CALCULATION_INTERVAL_MS = 1000L // 1 second
    }
    
    // Cryptographic components
    private val ecdhHelper = EcdhHelper()
    private val hkdf = Hkdf()
    private val cipher = AesGcmCipher()
    
    // Network components
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var keyPair: KeyPair? = null
    private var encryptionKey: ByteArray? = null
    
    // Transfer state
    private val _transferState = MutableStateFlow(TransferState.IDLE)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()
    
    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()
    
    private val isTransferActive = AtomicBoolean(false)
    private val transferredBytes = AtomicLong(0L)
    private var transferStartTime = 0L
    private var lastSpeedCalculationTime = 0L
    private var lastTransferredBytes = 0L
    
    // File and chunk management
    private lateinit var manifest: FileManifest
    private lateinit var chunkManager: ChunkManager
    
    /**
     * Transfer state enumeration.
     */
    enum class TransferState {
        IDLE,
        STARTING_SERVER,
        WAITING_FOR_CONNECTION,
        PERFORMING_HANDSHAKE,
        SENDING_MANIFEST,
        SENDING_CHUNKS,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    /**
     * Transfer progress data class.
     */
    data class TransferProgress(
        val bytesTransferred: Long = 0L,
        val totalBytes: Long = 0L,
        val progressPercentage: Float = 0f,
        val speedBytesPerSecond: Long = 0L,
        val estimatedTimeRemainingMs: Long = 0L,
        val chunksTransferred: Int = 0,
        val totalChunks: Int = 0
    )
    
    /**
     * Starts the file transfer server on the specified port.
     * 
     * @param port TCP port to listen on (default: 8080)
     * @return The actual port the server is listening on
     * @throws IOException if server cannot be started
     * @throws IllegalStateException if transfer is already active
     */
    @Throws(IOException::class, IllegalStateException::class)
    suspend fun startServer(port: Int = DEFAULT_PORT): Int = withContext(Dispatchers.IO) {
        require(!isTransferActive.get()) { "Transfer is already active" }
        require(file.exists() && file.canRead()) { "File is not accessible: ${file.absolutePath}" }
        
        try {
            _transferState.value = TransferState.STARTING_SERVER
            
            // Generate ECDH key pair
            keyPair = ecdhHelper.generateKeyPair()
            
            // Create file manifest
            manifest = FileManifest.fromFile(file, transferId)
            chunkManager = ChunkManager(manifest)
            
            // Start server socket
            serverSocket = ServerSocket(port).apply {
                soTimeout = CONNECTION_TIMEOUT_MS
            }
            
            val actualPort = serverSocket!!.localPort
            _transferState.value = TransferState.WAITING_FOR_CONNECTION
            
            // Initialize progress
            _progress.value = TransferProgress(
                totalBytes = manifest.fileSize,
                totalChunks = manifest.chunkCount
            )
            
            isTransferActive.set(true)
            actualPort
            
        } catch (e: Exception) {
            cleanup()
            _transferState.value = TransferState.FAILED
            throw IOException("Failed to start server: ${e.message}", e)
        }
    }
    
    /**
     * Waits for a client connection and performs the file transfer.
     * 
     * @throws IOException if connection or transfer fails
     * @throws SecurityException if handshake fails
     */
    suspend fun waitForConnectionAndTransfer() = withContext(Dispatchers.IO) {
        try {
            // Wait for client connection
            clientSocket = serverSocket?.accept()?.apply {
                soTimeout = SOCKET_TIMEOUT_MS
            } ?: throw IOException("Server socket is not available")
            
            _transferState.value = TransferState.PERFORMING_HANDSHAKE
            
            // Perform ECDH handshake
            performHandshake()
            
            _transferState.value = TransferState.SENDING_MANIFEST
            
            // Send file manifest
            sendManifest()
            
            _transferState.value = TransferState.SENDING_CHUNKS
            
            // Send file chunks
            transferStartTime = System.currentTimeMillis()
            lastSpeedCalculationTime = transferStartTime
            
            sendFileChunks()
            
            _transferState.value = TransferState.COMPLETED
            
        } catch (e: Exception) {
            _transferState.value = TransferState.FAILED
            throw e
        } finally {
            cleanup()
        }
    }
    
    /**
     * Performs ECDH key exchange with the receiver.
     */
    private suspend fun performHandshake() = withContext(Dispatchers.IO) {
        val socket = clientSocket ?: throw IOException("Client socket is not available")
        val keyPair = this@FileSender.keyPair ?: throw IllegalStateException("Key pair not generated")
        
        DataOutputStream(socket.getOutputStream()).use { output ->
            DataInputStream(socket.getInputStream()).use { input ->
                
                // Send our public key
                val ourPublicKeyBytes = keyPair.public.encoded
                output.writeInt(ourPublicKeyBytes.size)
                output.write(ourPublicKeyBytes)
                output.flush()
                
                // Receive peer's public key
                val peerPublicKeySize = input.readInt()
                if (peerPublicKeySize <= 0 || peerPublicKeySize > 10000) {
                    throw SecurityException("Invalid public key size: $peerPublicKeySize")
                }
                
                val peerPublicKeyBytes = ByteArray(peerPublicKeySize)
                input.readFully(peerPublicKeyBytes)
                
                // Reconstruct peer's public key
                val keyFactory = java.security.KeyFactory.getInstance("EC")
                val peerPublicKey = keyFactory.generatePublic(
                    java.security.spec.X509EncodedKeySpec(peerPublicKeyBytes)
                )
                
                // Compute shared secret and derive encryption key
                val sharedSecret = ecdhHelper.computeSharedSecret(keyPair.private, peerPublicKey)
                encryptionKey = hkdf.deriveAesKey(sharedSecret, context = "P2P-FileShare-AES")
                
                // Clear shared secret from memory
                sharedSecret.fill(0)
            }
        }
    }
    
    /**
     * Sends the file manifest to the receiver.
     */
    private suspend fun sendManifest() = withContext(Dispatchers.IO) {
        val socket = clientSocket ?: throw IOException("Client socket is not available")
        val key = encryptionKey ?: throw IllegalStateException("Encryption key not established")
        
        DataOutputStream(socket.getOutputStream()).use { output ->
            val manifestJson = manifest.toJson()
            val encryptedManifest = cipher.encrypt(manifestJson.toByteArray(Charsets.UTF_8), key)
            
            output.writeInt(encryptedManifest.size)
            output.write(encryptedManifest)
            output.flush()
        }
    }
    
    /**
     * Sends file chunks in parallel using coroutines.
     */
    private suspend fun sendFileChunks() = withContext(Dispatchers.IO) {
        val socket = clientSocket ?: throw IOException("Client socket is not available")
        val key = encryptionKey ?: throw IllegalStateException("Encryption key not established")
        
        // Create channel for chunk requests from receiver
        val chunkRequestChannel = Channel<Int>(Channel.UNLIMITED)
        
        // Start listening for chunk requests
        val requestListenerJob = launch {
            DataInputStream(socket.getInputStream()).use { input ->
                try {
                    while (isActive && isTransferActive.get()) {
                        val chunkIndex = input.readInt()
                        if (chunkIndex >= 0 && chunkIndex < manifest.chunkCount) {
                            chunkRequestChannel.send(chunkIndex)
                        } else if (chunkIndex == -1) {
                            // Transfer complete signal from receiver
                            break
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, continue
                } catch (e: Exception) {
                    if (isActive) throw e
                }
            }
        }
        
        // Start chunk sender workers
        val senderJobs = (1..MAX_PARALLEL_CHUNKS).map { workerId ->
            launch {
                DataOutputStream(socket.getOutputStream()).use { output ->
                    for (chunkIndex in chunkRequestChannel) {
                        if (!isActive || !isTransferActive.get()) break
                        
                        try {
                            sendChunk(chunkIndex, output, key)
                            updateProgress()
                        } catch (e: Exception) {
                            if (isActive) throw e
                        }
                    }
                }
            }
        }
        
        // Wait for all jobs to complete
        try {
            requestListenerJob.join()
            chunkRequestChannel.close()
            senderJobs.joinAll()
        } finally {
            requestListenerJob.cancel()
            senderJobs.forEach { it.cancel() }
        }
    }
    
    /**
     * Sends a specific chunk to the receiver.
     */
    private suspend fun sendChunk(chunkIndex: Int, output: DataOutputStream, key: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(output) {
            // Read chunk data
            val chunkData = chunkManager.readChunk(file, chunkIndex)
            
            // Encrypt chunk data
            val encryptedChunk = cipher.encrypt(chunkData, key)
            
            // Send chunk header and data
            output.writeInt(chunkIndex) // Chunk index
            output.writeInt(encryptedChunk.size) // Encrypted chunk size
            output.write(encryptedChunk) // Encrypted chunk data
            output.flush()
            
            // Update transferred bytes
            transferredBytes.addAndGet(chunkData.size.toLong())
        }
    }
    
    /**
     * Updates transfer progress and calculates speed.
     */
    private fun updateProgress() {
        val currentTime = System.currentTimeMillis()
        val currentTransferredBytes = transferredBytes.get()
        
        // Calculate speed every second
        if (currentTime - lastSpeedCalculationTime >= SPEED_CALCULATION_INTERVAL_MS) {
            val timeDelta = currentTime - lastSpeedCalculationTime
            val bytesDelta = currentTransferredBytes - lastTransferredBytes
            val speedBytesPerSecond = if (timeDelta > 0) (bytesDelta * 1000) / timeDelta else 0L
            
            // Calculate ETA
            val remainingBytes = manifest.fileSize - currentTransferredBytes
            val estimatedTimeRemainingMs = if (speedBytesPerSecond > 0) {
                (remainingBytes * 1000) / speedBytesPerSecond
            } else {
                0L
            }
            
            // Update progress
            val progressPercentage = (currentTransferredBytes.toFloat() / manifest.fileSize) * 100f
            val chunksTransferred = (currentTransferredBytes / manifest.chunkSize).toInt()
            
            _progress.value = TransferProgress(
                bytesTransferred = currentTransferredBytes,
                totalBytes = manifest.fileSize,
                progressPercentage = progressPercentage,
                speedBytesPerSecond = speedBytesPerSecond,
                estimatedTimeRemainingMs = estimatedTimeRemainingMs,
                chunksTransferred = chunksTransferred,
                totalChunks = manifest.chunkCount
            )
            
            lastSpeedCalculationTime = currentTime
            lastTransferredBytes = currentTransferredBytes
        }
    }
    
    /**
     * Cancels the ongoing transfer.
     */
    fun cancelTransfer() {
        if (isTransferActive.compareAndSet(true, false)) {
            _transferState.value = TransferState.CANCELLED
            cleanup()
        }
    }
    
    /**
     * Gets the sender's public key for QR code generation.
     * 
     * @return Base64 encoded public key, or null if not generated
     */
    fun getPublicKeyBase64(): String? {
        return keyPair?.public?.encoded?.let { 
            Base64.getEncoder().encodeToString(it) 
        }
    }
    
    /**
     * Gets the current server port.
     * 
     * @return Server port, or -1 if server is not running
     */
    fun getServerPort(): Int {
        return serverSocket?.localPort ?: -1
    }
    
    /**
     * Checks if the transfer is currently active.
     * 
     * @return true if transfer is active, false otherwise
     */
    fun isActive(): Boolean {
        return isTransferActive.get()
    }
    
    /**
     * Gets the file manifest.
     * 
     * @return FileManifest for the file being sent
     */
    fun getManifest(): FileManifest? {
        return if (::manifest.isInitialized) manifest else null
    }
    
    /**
     * Cleans up resources and closes connections.
     */
    private fun cleanup() {
        isTransferActive.set(false)
        
        try {
            clientSocket?.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        
        // Clear sensitive data
        encryptionKey?.fill(0)
        encryptionKey = null
        
        keyPair?.let { kp ->
            ecdhHelper.clearPrivateKey(kp.private)
        }
        keyPair = null
        
        clientSocket = null
        serverSocket = null
    }
}
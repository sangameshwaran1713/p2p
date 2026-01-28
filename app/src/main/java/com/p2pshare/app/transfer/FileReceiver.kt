package com.p2pshare.app.transfer

import com.p2pshare.app.crypto.AesGcmCipher
import com.p2pshare.app.crypto.EcdhHelper
import com.p2pshare.app.crypto.Hkdf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * File receiver implementation using client socket for P2P file transfer.
 * 
 * This class handles:
 * - TCP client connection to sender
 * - ECDH key exchange with sender
 * - File manifest reception and validation
 * - Parallel chunk reception and assembly
 * - Progress tracking and speed calculation
 * - Decryption of all received data
 * - Resume capability for interrupted transfers
 * 
 * The receiver acts as a TCP client that connects to the sender's server.
 */
class FileReceiver(
    private val outputDirectory: File
) {
    
    companion object {
        private const val TAG = "FileReceiver"
        private const val CONNECTION_TIMEOUT_MS = 30000 // 30 seconds
        private const val SOCKET_TIMEOUT_MS = 10000 // 10 seconds
        private const val MAX_PARALLEL_REQUESTS = 4
        private const val SPEED_CALCULATION_INTERVAL_MS = 1000L // 1 second
        private const val CHUNK_REQUEST_BATCH_SIZE = 8
    }
    
    // Cryptographic components
    private val ecdhHelper = EcdhHelper()
    private val hkdf = Hkdf()
    private val cipher = AesGcmCipher()
    
    // Network components
    private var socket: Socket? = null
    private var keyPair: KeyPair? = null
    private var encryptionKey: ByteArray? = null
    
    // Transfer state
    private val _transferState = MutableStateFlow(TransferState.IDLE)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()
    
    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()
    
    private val isTransferActive = AtomicBoolean(false)
    private val receivedBytes = AtomicLong(0L)
    private var transferStartTime = 0L
    private var lastSpeedCalculationTime = 0L
    private var lastReceivedBytes = 0L
    
    // File and chunk management
    private var manifest: FileManifest? = null
    private var chunkManager: ChunkManager? = null
    private var outputFile: File? = null
    
    /**
     * Transfer state enumeration.
     */
    enum class TransferState {
        IDLE,
        CONNECTING,
        PERFORMING_HANDSHAKE,
        RECEIVING_MANIFEST,
        RECEIVING_CHUNKS,
        ASSEMBLING_FILE,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    /**
     * Transfer progress data class.
     */
    data class TransferProgress(
        val bytesReceived: Long = 0L,
        val totalBytes: Long = 0L,
        val progressPercentage: Float = 0f,
        val speedBytesPerSecond: Long = 0L,
        val estimatedTimeRemainingMs: Long = 0L,
        val chunksReceived: Int = 0,
        val totalChunks: Int = 0,
        val fileName: String = ""
    )
    
    /**
     * Connects to the sender and receives the file.
     * 
     * @param senderIp IP address of the sender
     * @param senderPort Port of the sender's server
     * @param expectedTransferId Expected transfer ID for validation (optional)
     * @return The received file
     * @throws IOException if connection or transfer fails
     * @throws SecurityException if handshake or validation fails
     */
    @Throws(IOException::class, SecurityException::class)
    suspend fun connectAndReceiveFile(
        senderIp: String, 
        senderPort: Int,
        expectedTransferId: String? = null
    ): File = withContext(Dispatchers.IO) {
        
        require(!isTransferActive.get()) { "Transfer is already active" }
        require(outputDirectory.exists() || outputDirectory.mkdirs()) { 
            "Cannot create output directory: ${outputDirectory.absolutePath}" 
        }
        
        try {
            isTransferActive.set(true)
            transferStartTime = System.currentTimeMillis()
            lastSpeedCalculationTime = transferStartTime
            
            // Connect to sender
            _transferState.value = TransferState.CONNECTING
            connectToSender(senderIp, senderPort)
            
            // Perform ECDH handshake
            _transferState.value = TransferState.PERFORMING_HANDSHAKE
            performHandshake()
            
            // Receive and validate manifest
            _transferState.value = TransferState.RECEIVING_MANIFEST
            receiveManifest(expectedTransferId)
            
            // Receive file chunks
            _transferState.value = TransferState.RECEIVING_CHUNKS
            receiveFileChunks()
            
            // Assemble final file
            _transferState.value = TransferState.ASSEMBLING_FILE
            val finalFile = assembleFile()
            
            _transferState.value = TransferState.COMPLETED
            finalFile
            
        } catch (e: Exception) {
            _transferState.value = TransferState.FAILED
            cleanup()
            throw e
        } finally {
            cleanup()
        }
    }
    
    /**
     * Connects to the sender's server socket.
     */
    private suspend fun connectToSender(ip: String, port: Int) = withContext(Dispatchers.IO) {
        socket = Socket().apply {
            soTimeout = SOCKET_TIMEOUT_MS
            connect(java.net.InetSocketAddress(ip, port), CONNECTION_TIMEOUT_MS)
        }
    }
    
    /**
     * Performs ECDH key exchange with the sender.
     */
    private suspend fun performHandshake() = withContext(Dispatchers.IO) {
        val socket = this@FileReceiver.socket ?: throw IOException("Socket is not connected")
        
        // Generate our key pair
        keyPair = ecdhHelper.generateKeyPair()
        val ourKeyPair = keyPair!!
        
        DataOutputStream(socket.getOutputStream()).use { output ->
            DataInputStream(socket.getInputStream()).use { input ->
                
                // Receive sender's public key
                val senderPublicKeySize = input.readInt()
                if (senderPublicKeySize <= 0 || senderPublicKeySize > 10000) {
                    throw SecurityException("Invalid public key size: $senderPublicKeySize")
                }
                
                val senderPublicKeyBytes = ByteArray(senderPublicKeySize)
                input.readFully(senderPublicKeyBytes)
                
                // Send our public key
                val ourPublicKeyBytes = ourKeyPair.public.encoded
                output.writeInt(ourPublicKeyBytes.size)
                output.write(ourPublicKeyBytes)
                output.flush()
                
                // Reconstruct sender's public key
                val keyFactory = java.security.KeyFactory.getInstance("EC")
                val senderPublicKey = keyFactory.generatePublic(
                    java.security.spec.X509EncodedKeySpec(senderPublicKeyBytes)
                )
                
                // Compute shared secret and derive encryption key
                val sharedSecret = ecdhHelper.computeSharedSecret(ourKeyPair.private, senderPublicKey)
                encryptionKey = hkdf.deriveAesKey(sharedSecret, context = "P2P-FileShare-AES")
                
                // Clear shared secret from memory
                sharedSecret.fill(0)
            }
        }
    }
    
    /**
     * Receives and validates the file manifest.
     */
    private suspend fun receiveManifest(expectedTransferId: String?) = withContext(Dispatchers.IO) {
        val socket = this@FileReceiver.socket ?: throw IOException("Socket is not connected")
        val key = encryptionKey ?: throw IllegalStateException("Encryption key not established")
        
        DataInputStream(socket.getInputStream()).use { input ->
            // Receive encrypted manifest
            val encryptedManifestSize = input.readInt()
            if (encryptedManifestSize <= 0 || encryptedManifestSize > 1024 * 1024) { // Max 1MB manifest
                throw SecurityException("Invalid manifest size: $encryptedManifestSize")
            }
            
            val encryptedManifest = ByteArray(encryptedManifestSize)
            input.readFully(encryptedManifest)
            
            // Decrypt and parse manifest
            val manifestBytes = cipher.decrypt(encryptedManifest, key)
            val manifestJson = String(manifestBytes, Charsets.UTF_8)
            manifest = FileManifest.fromJson(manifestJson)
            
            val receivedManifest = manifest!!
            
            // Validate manifest
            if (!receivedManifest.isValid()) {
                throw SecurityException("Invalid file manifest received")
            }
            
            // Validate transfer ID if expected
            if (expectedTransferId != null && receivedManifest.transferId != expectedTransferId) {
                throw SecurityException("Transfer ID mismatch: expected $expectedTransferId, got ${receivedManifest.transferId}")
            }
            
            // Create chunk manager and output file
            chunkManager = ChunkManager(receivedManifest)
            outputFile = File(outputDirectory, receivedManifest.fileName)
            
            // Initialize progress
            _progress.value = TransferProgress(
                totalBytes = receivedManifest.fileSize,
                totalChunks = receivedManifest.chunkCount,
                fileName = receivedManifest.fileName
            )
        }
    }
    
    /**
     * Receives file chunks in parallel.
     */
    private suspend fun receiveFileChunks() = withContext(Dispatchers.IO) {
        val socket = this@FileReceiver.socket ?: throw IOException("Socket is not connected")
        val key = encryptionKey ?: throw IllegalStateException("Encryption key not established")
        val chunkManager = this@FileReceiver.chunkManager ?: throw IllegalStateException("Chunk manager not initialized")
        val manifest = this@FileReceiver.manifest ?: throw IllegalStateException("Manifest not received")
        
        // Start chunk receiver
        val receiverJob = launch {
            DataInputStream(socket.getInputStream()).use { input ->
                while (isActive && isTransferActive.get() && !chunkManager.isTransferComplete()) {
                    try {
                        receiveChunk(input, key, chunkManager)
                        updateProgress()
                    } catch (e: SocketTimeoutException) {
                        // Continue on timeout
                        continue
                    } catch (e: Exception) {
                        if (isActive) throw e
                    }
                }
            }
        }
        
        // Start chunk requester
        val requesterJob = launch {
            DataOutputStream(socket.getOutputStream()).use { output ->
                while (isActive && isTransferActive.get() && !chunkManager.isTransferComplete()) {
                    try {
                        requestMissingChunks(output, chunkManager)
                        delay(100) // Small delay between request batches
                    } catch (e: Exception) {
                        if (isActive) throw e
                    }
                }
                
                // Send completion signal
                try {
                    output.writeInt(-1) // Transfer complete signal
                    output.flush()
                } catch (e: Exception) {
                    // Ignore errors when sending completion signal
                }
            }
        }
        
        // Wait for completion
        try {
            receiverJob.join()
            requesterJob.join()
        } finally {
            receiverJob.cancel()
            requesterJob.cancel()
        }
        
        // Verify transfer completion
        if (!chunkManager.isTransferComplete()) {
            throw IOException("Transfer incomplete: ${chunkManager.getReceivedChunkCount()}/${manifest.chunkCount} chunks received")
        }
    }
    
    /**
     * Receives a single chunk from the sender.
     */
    private suspend fun receiveChunk(
        input: DataInputStream, 
        key: ByteArray, 
        chunkManager: ChunkManager
    ) = withContext(Dispatchers.IO) {
        
        // Read chunk header
        val chunkIndex = input.readInt()
        val encryptedChunkSize = input.readInt()
        
        if (chunkIndex < 0 || encryptedChunkSize <= 0 || encryptedChunkSize > 1024 * 1024) {
            throw IOException("Invalid chunk header: index=$chunkIndex, size=$encryptedChunkSize")
        }
        
        // Read encrypted chunk data
        val encryptedChunk = ByteArray(encryptedChunkSize)
        input.readFully(encryptedChunk)
        
        // Decrypt chunk
        val chunkData = cipher.decrypt(encryptedChunk, key)
        
        // Store chunk (includes integrity verification)
        if (chunkManager.storeChunk(chunkIndex, chunkData)) {
            receivedBytes.addAndGet(chunkData.size.toLong())
        }
    }
    
    /**
     * Requests missing chunks from the sender.
     */
    private suspend fun requestMissingChunks(
        output: DataOutputStream, 
        chunkManager: ChunkManager
    ) = withContext(Dispatchers.IO) {
        
        val missingChunks = chunkManager.getMissingChunks()
        val chunksToRequest = missingChunks.take(CHUNK_REQUEST_BATCH_SIZE)
        
        synchronized(output) {
            for (chunkIndex in chunksToRequest) {
                output.writeInt(chunkIndex)
            }
            output.flush()
        }
    }
    
    /**
     * Assembles the final file from received chunks.
     */
    private suspend fun assembleFile(): File = withContext(Dispatchers.IO) {
        val chunkManager = this@FileReceiver.chunkManager ?: throw IllegalStateException("Chunk manager not initialized")
        val outputFile = this@FileReceiver.outputFile ?: throw IllegalStateException("Output file not set")
        
        // Assemble file directly to disk (memory efficient)
        chunkManager.assembleFileToFile(outputFile)
        
        // Verify final file
        val manifest = this@FileReceiver.manifest!!
        if (outputFile.length() != manifest.fileSize) {
            throw IOException("File size mismatch: expected ${manifest.fileSize}, actual ${outputFile.length()}")
        }
        
        // Set file timestamp if available
        if (manifest.lastModified > 0) {
            outputFile.setLastModified(manifest.lastModified)
        }
        
        outputFile
    }
    
    /**
     * Updates transfer progress and calculates speed.
     */
    private fun updateProgress() {
        val currentTime = System.currentTimeMillis()
        val currentReceivedBytes = receivedBytes.get()
        val chunkManager = this.chunkManager ?: return
        val manifest = this.manifest ?: return
        
        // Calculate speed every second
        if (currentTime - lastSpeedCalculationTime >= SPEED_CALCULATION_INTERVAL_MS) {
            val timeDelta = currentTime - lastSpeedCalculationTime
            val bytesDelta = currentReceivedBytes - lastReceivedBytes
            val speedBytesPerSecond = if (timeDelta > 0) (bytesDelta * 1000) / timeDelta else 0L
            
            // Calculate ETA
            val remainingBytes = manifest.fileSize - currentReceivedBytes
            val estimatedTimeRemainingMs = if (speedBytesPerSecond > 0) {
                (remainingBytes * 1000) / speedBytesPerSecond
            } else {
                0L
            }
            
            // Update progress
            val progressPercentage = chunkManager.getProgressPercentage()
            
            _progress.value = TransferProgress(
                bytesReceived = currentReceivedBytes,
                totalBytes = manifest.fileSize,
                progressPercentage = progressPercentage,
                speedBytesPerSecond = speedBytesPerSecond,
                estimatedTimeRemainingMs = estimatedTimeRemainingMs,
                chunksReceived = chunkManager.getReceivedChunkCount(),
                totalChunks = manifest.chunkCount,
                fileName = manifest.fileName
            )
            
            lastSpeedCalculationTime = currentTime
            lastReceivedBytes = currentReceivedBytes
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
     * Gets the receiver's public key for handshake.
     * 
     * @return Base64 encoded public key, or null if not generated
     */
    fun getPublicKeyBase64(): String? {
        return keyPair?.public?.encoded?.let { 
            Base64.getEncoder().encodeToString(it) 
        }
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
     * Gets the received file manifest.
     * 
     * @return FileManifest of the file being received, or null if not yet received
     */
    fun getManifest(): FileManifest? {
        return manifest
    }
    
    /**
     * Gets the current chunk manager.
     * 
     * @return ChunkManager for tracking progress, or null if not initialized
     */
    fun getChunkManager(): ChunkManager? {
        return chunkManager
    }
    
    /**
     * Cleans up resources and closes connections.
     */
    private fun cleanup() {
        isTransferActive.set(false)
        
        try {
            socket?.close()
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
        
        // Clear chunk data to free memory
        chunkManager?.clearChunkData()
        
        socket = null
    }
}
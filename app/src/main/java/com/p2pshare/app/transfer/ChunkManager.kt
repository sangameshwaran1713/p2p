package com.p2pshare.app.transfer

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages file chunking and transfer progress tracking for P2P file sharing.
 * 
 * This class handles:
 * - Splitting files into chunks for parallel transmission
 * - Tracking which chunks have been received/sent
 * - Managing resume capability for interrupted transfers
 * - Providing thread-safe progress tracking
 * - Chunk integrity verification
 * 
 * The class is designed to be thread-safe for concurrent chunk operations.
 */
class ChunkManager(private val manifest: FileManifest) {
    
    companion object {
        private const val HASH_ALGORITHM = "SHA-256"
    }
    
    // Thread-safe tracking of received chunks
    private val receivedChunks = BooleanArray(manifest.chunkCount)
    private val receivedChunksCount = AtomicInteger(0)
    
    // Thread-safe chunk data storage for assembly
    private val chunkData = ConcurrentHashMap<Int, ByteArray>()
    
    // Synchronization objects
    private val chunksLock = Any()
    
    /**
     * Represents a range of bytes within a file for chunk processing.
     * 
     * @property chunkIndex Zero-based index of this chunk
     * @property startOffset Byte offset where this chunk starts in the file
     * @property size Size of this chunk in bytes
     * @property endOffset Byte offset where this chunk ends in the file (exclusive)
     */
    data class ChunkRange(
        val chunkIndex: Int,
        val startOffset: Long,
        val size: Int,
        val endOffset: Long = startOffset + size
    ) {
        /**
         * Checks if this chunk range is valid.
         */
        fun isValid(): Boolean {
            return chunkIndex >= 0 && 
                   startOffset >= 0 && 
                   size > 0 && 
                   endOffset > startOffset
        }
    }
    
    /**
     * Splits a file into chunk ranges based on the manifest.
     * 
     * This method creates a list of ChunkRange objects that define how
     * the file should be divided for parallel processing. Each range
     * specifies the byte offset and size for a chunk.
     * 
     * @param file The file to split into chunks
     * @return List of ChunkRange objects defining the file chunks
     * @throws IllegalArgumentException if file doesn't match manifest
     */
    @Throws(IllegalArgumentException::class)
    fun splitFile(file: File): List<ChunkRange> {
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        require(file.length() == manifest.fileSize) { 
            "File size mismatch: expected ${manifest.fileSize}, actual ${file.length()}" 
        }
        
        val chunks = mutableListOf<ChunkRange>()
        
        for (chunkIndex in 0 until manifest.chunkCount) {
            val startOffset = manifest.getChunkOffset(chunkIndex)
            val chunkSize = manifest.getChunkSize(chunkIndex)
            
            chunks.add(ChunkRange(
                chunkIndex = chunkIndex,
                startOffset = startOffset,
                size = chunkSize
            ))
        }
        
        return chunks
    }
    
    /**
     * Reads a specific chunk from a file.
     * 
     * @param file The file to read from
     * @param chunkIndex Zero-based index of the chunk to read
     * @return ByteArray containing the chunk data
     * @throws IllegalArgumentException if chunk index is invalid
     * @throws java.io.IOException if file reading fails
     */
    @Throws(IllegalArgumentException::class, java.io.IOException::class)
    fun readChunk(file: File, chunkIndex: Int): ByteArray {
        require(chunkIndex in 0 until manifest.chunkCount) { 
            "Invalid chunk index: $chunkIndex" 
        }
        
        val chunkSize = manifest.getChunkSize(chunkIndex)
        val chunkOffset = manifest.getChunkOffset(chunkIndex)
        
        RandomAccessFile(file, "r").use { randomAccessFile ->
            randomAccessFile.seek(chunkOffset)
            val buffer = ByteArray(chunkSize)
            val bytesRead = randomAccessFile.read(buffer)
            
            return if (bytesRead == chunkSize) {
                buffer
            } else {
                buffer.copyOf(bytesRead)
            }
        }
    }
    
    /**
     * Stores a received chunk and marks it as received.
     * 
     * This method is thread-safe and can be called concurrently from
     * multiple threads receiving different chunks.
     * 
     * @param chunkIndex Zero-based index of the chunk
     * @param data The chunk data
     * @return true if chunk was successfully stored and verified, false otherwise
     */
    fun storeChunk(chunkIndex: Int, data: ByteArray): Boolean {
        if (chunkIndex !in 0 until manifest.chunkCount) {
            return false
        }
        
        // Verify chunk integrity
        if (!manifest.verifyChunk(chunkIndex, data)) {
            return false
        }
        
        synchronized(chunksLock) {
            if (!receivedChunks[chunkIndex]) {
                chunkData[chunkIndex] = data
                receivedChunks[chunkIndex] = true
                receivedChunksCount.incrementAndGet()
                return true
            }
        }
        
        return false // Chunk already received
    }
    
    /**
     * Gets the current array of received chunks.
     * 
     * @return BooleanArray where true indicates the chunk has been received
     */
    fun getReceivedChunks(): BooleanArray {
        synchronized(chunksLock) {
            return receivedChunks.copyOf()
        }
    }
    
    /**
     * Marks a specific chunk as received without storing data.
     * 
     * This is useful for tracking progress when chunks are written
     * directly to a file instead of being stored in memory.
     * 
     * @param chunkIndex Zero-based index of the chunk to mark
     */
    fun markChunkReceived(chunkIndex: Int) {
        if (chunkIndex in 0 until manifest.chunkCount) {
            synchronized(chunksLock) {
                if (!receivedChunks[chunkIndex]) {
                    receivedChunks[chunkIndex] = true
                    receivedChunksCount.incrementAndGet()
                }
            }
        }
    }
    
    /**
     * Checks if a specific chunk has been received.
     * 
     * @param chunkIndex Zero-based index of the chunk
     * @return true if the chunk has been received, false otherwise
     */
    fun isChunkReceived(chunkIndex: Int): Boolean {
        return if (chunkIndex in 0 until manifest.chunkCount) {
            synchronized(chunksLock) {
                receivedChunks[chunkIndex]
            }
        } else {
            false
        }
    }
    
    /**
     * Checks if the transfer is complete (all chunks received).
     * 
     * @return true if all chunks have been received, false otherwise
     */
    fun isTransferComplete(): Boolean {
        return receivedChunksCount.get() == manifest.chunkCount
    }
    
    /**
     * Gets the number of chunks that have been received.
     * 
     * @return Number of received chunks
     */
    fun getReceivedChunkCount(): Int {
        return receivedChunksCount.get()
    }
    
    /**
     * Gets the transfer progress as a percentage.
     * 
     * @return Progress percentage (0.0 to 100.0)
     */
    fun getProgressPercentage(): Float {
        return (receivedChunksCount.get().toFloat() / manifest.chunkCount) * 100f
    }
    
    /**
     * Gets the number of bytes that have been received.
     * 
     * @return Number of bytes received
     */
    fun getReceivedBytes(): Long {
        synchronized(chunksLock) {
            var totalBytes = 0L
            for (i in receivedChunks.indices) {
                if (receivedChunks[i]) {
                    totalBytes += manifest.getChunkSize(i)
                }
            }
            return totalBytes
        }
    }
    
    /**
     * Gets a list of chunk indices that still need to be received.
     * 
     * @return List of missing chunk indices
     */
    fun getMissingChunks(): List<Int> {
        synchronized(chunksLock) {
            val missing = mutableListOf<Int>()
            for (i in receivedChunks.indices) {
                if (!receivedChunks[i]) {
                    missing.add(i)
                }
            }
            return missing
        }
    }
    
    /**
     * Assembles all received chunks into a complete file.
     * 
     * This method should only be called when isTransferComplete() returns true.
     * The assembled file data is verified against the manifest's file hash.
     * 
     * @return ByteArray containing the complete file data
     * @throws IllegalStateException if transfer is not complete
     * @throws SecurityException if file integrity verification fails
     */
    @Throws(IllegalStateException::class, SecurityException::class)
    fun assembleFile(): ByteArray {
        require(isTransferComplete()) { 
            "Cannot assemble file: transfer is not complete (${receivedChunksCount.get()}/${manifest.chunkCount})" 
        }
        
        val fileData = ByteArray(manifest.fileSize.toInt())
        var offset = 0
        
        synchronized(chunksLock) {
            for (chunkIndex in 0 until manifest.chunkCount) {
                val chunk = chunkData[chunkIndex] ?: throw IllegalStateException(
                    "Missing chunk data for index $chunkIndex"
                )
                
                System.arraycopy(chunk, 0, fileData, offset, chunk.size)
                offset += chunk.size
            }
        }
        
        // Verify complete file integrity
        if (!manifest.verifyFile(fileData)) {
            throw SecurityException("File integrity verification failed")
        }
        
        return fileData
    }
    
    /**
     * Writes all received chunks directly to a file.
     * 
     * This method is more memory-efficient than assembleFile() for large files
     * as it writes chunks directly to disk instead of assembling in memory.
     * 
     * @param outputFile The file to write the assembled data to
     * @throws IllegalStateException if transfer is not complete
     * @throws java.io.IOException if file writing fails
     * @throws SecurityException if file integrity verification fails
     */
    @Throws(IllegalStateException::class, java.io.IOException::class, SecurityException::class)
    fun assembleFileToFile(outputFile: File) {
        require(isTransferComplete()) { 
            "Cannot assemble file: transfer is not complete" 
        }
        
        // Create parent directories if they don't exist
        outputFile.parentFile?.mkdirs()
        
        RandomAccessFile(outputFile, "rw").use { randomAccessFile ->
            randomAccessFile.setLength(manifest.fileSize)
            
            synchronized(chunksLock) {
                for (chunkIndex in 0 until manifest.chunkCount) {
                    val chunk = chunkData[chunkIndex] ?: throw IllegalStateException(
                        "Missing chunk data for index $chunkIndex"
                    )
                    
                    val offset = manifest.getChunkOffset(chunkIndex)
                    randomAccessFile.seek(offset)
                    randomAccessFile.write(chunk)
                }
            }
        }
        
        // Verify complete file integrity
        val fileData = outputFile.readBytes()
        if (!manifest.verifyFile(fileData)) {
            outputFile.delete() // Clean up corrupted file
            throw SecurityException("File integrity verification failed")
        }
    }
    
    /**
     * Clears all stored chunk data to free memory.
     * 
     * This should be called after the file has been assembled or when
     * the transfer is cancelled to free up memory.
     */
    fun clearChunkData() {
        synchronized(chunksLock) {
            chunkData.clear()
        }
    }
    
    /**
     * Resets the chunk manager to initial state.
     * 
     * This clears all progress and stored data, useful for restarting
     * a failed transfer.
     */
    fun reset() {
        synchronized(chunksLock) {
            receivedChunks.fill(false)
            receivedChunksCount.set(0)
            chunkData.clear()
        }
    }
    
    /**
     * Gets a summary of the current transfer state.
     * 
     * @return Human-readable summary string
     */
    fun getSummary(): String {
        val progress = getProgressPercentage()
        val receivedMB = getReceivedBytes() / (1024.0 * 1024.0)
        val totalMB = manifest.fileSize / (1024.0 * 1024.0)
        
        return "ChunkManager(progress=${String.format("%.1f", progress)}%, " +
               "chunks=${receivedChunksCount.get()}/${manifest.chunkCount}, " +
               "data=${String.format("%.2f", receivedMB)}/${String.format("%.2f", totalMB)}MB)"
    }
}
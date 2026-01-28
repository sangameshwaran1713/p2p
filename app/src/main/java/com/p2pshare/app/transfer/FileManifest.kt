package com.p2pshare.app.transfer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * File manifest containing metadata for P2P file transfer.
 * 
 * This data class represents all the information needed to transfer a file
 * in chunks, including integrity verification through SHA-256 hashes.
 * The manifest is exchanged between sender and receiver before the actual
 * file transfer begins.
 * 
 * @property fileName The original name of the file being transferred
 * @property fileSize Total size of the file in bytes
 * @property chunkSize Size of each chunk in bytes (default: 256KB)
 * @property chunkCount Total number of chunks the file is divided into
 * @property chunkHashes List of SHA-256 hashes for each chunk for integrity verification
 * @property fileHash SHA-256 hash of the complete file for final verification
 * @property mimeType MIME type of the file (optional)
 * @property lastModified Last modification timestamp of the original file
 * @property transferId Unique identifier for this transfer session
 */
data class FileManifest(
    val fileName: String,
    val fileSize: Long,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    val chunkCount: Int,
    val chunkHashes: List<String>,
    val fileHash: String,
    val mimeType: String? = null,
    val lastModified: Long = 0L,
    val transferId: String
) {
    
    companion object {
        const val DEFAULT_CHUNK_SIZE = 256 * 1024 // 256KB
        private const val HASH_ALGORITHM = "SHA-256"
        
        /**
         * Creates a FileManifest from a file by analyzing its content and generating hashes.
         * 
         * This method reads the file, divides it into chunks, and computes SHA-256 hashes
         * for each chunk as well as the complete file. This is a potentially expensive
         * operation for large files and should be run on a background thread.
         * 
         * @param file The file to create a manifest for
         * @param transferId Unique identifier for this transfer
         * @param chunkSize Size of each chunk in bytes (default: 256KB)
         * @return FileManifest containing all metadata and hashes
         * @throws IllegalArgumentException if file doesn't exist or is not readable
         * @throws SecurityException if file cannot be read due to permissions
         */
        @Throws(IllegalArgumentException::class, SecurityException::class)
        fun fromFile(
            file: File, 
            transferId: String, 
            chunkSize: Int = DEFAULT_CHUNK_SIZE
        ): FileManifest {
            require(file.exists()) { "File does not exist: ${file.absolutePath}" }
            require(file.isFile) { "Path is not a file: ${file.absolutePath}" }
            require(file.canRead()) { "File is not readable: ${file.absolutePath}" }
            require(chunkSize > 0) { "Chunk size must be positive: $chunkSize" }
            require(transferId.isNotBlank()) { "Transfer ID cannot be blank" }
            
            val fileSize = file.length()
            val chunkCount = if (fileSize == 0L) 1 else ((fileSize + chunkSize - 1) / chunkSize).toInt()
            
            val chunkHashes = mutableListOf<String>()
            val fileDigest = MessageDigest.getInstance(HASH_ALGORITHM)
            
            file.inputStream().use { inputStream ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val chunkData = if (bytesRead == buffer.size) {
                        buffer
                    } else {
                        buffer.copyOf(bytesRead)
                    }
                    
                    // Add to file hash
                    fileDigest.update(chunkData)
                    
                    // Calculate chunk hash
                    val chunkDigest = MessageDigest.getInstance(HASH_ALGORITHM)
                    chunkDigest.update(chunkData)
                    val chunkHash = bytesToHex(chunkDigest.digest())
                    chunkHashes.add(chunkHash)
                }
            }
            
            val fileHash = bytesToHex(fileDigest.digest())
            
            return FileManifest(
                fileName = file.name,
                fileSize = fileSize,
                chunkSize = chunkSize,
                chunkCount = chunkCount,
                chunkHashes = chunkHashes,
                fileHash = fileHash,
                mimeType = guessMimeType(file.name),
                lastModified = file.lastModified(),
                transferId = transferId
            )
        }
        
        /**
         * Creates a FileManifest from JSON string.
         * 
         * @param json JSON string representation of the manifest
         * @return FileManifest parsed from JSON
         * @throws org.json.JSONException if JSON is malformed
         */
        @Throws(org.json.JSONException::class)
        fun fromJson(json: String): FileManifest {
            val jsonObject = JSONObject(json)
            
            val chunkHashesArray = jsonObject.getJSONArray("chunkHashes")
            val chunkHashes = mutableListOf<String>()
            for (i in 0 until chunkHashesArray.length()) {
                chunkHashes.add(chunkHashesArray.getString(i))
            }
            
            return FileManifest(
                fileName = jsonObject.getString("fileName"),
                fileSize = jsonObject.getLong("fileSize"),
                chunkSize = jsonObject.getInt("chunkSize"),
                chunkCount = jsonObject.getInt("chunkCount"),
                chunkHashes = chunkHashes,
                fileHash = jsonObject.getString("fileHash"),
                mimeType = jsonObject.optString("mimeType").takeIf { it.isNotEmpty() },
                lastModified = jsonObject.optLong("lastModified", 0L),
                transferId = jsonObject.getString("transferId")
            )
        }
        
        /**
         * Converts byte array to hexadecimal string.
         */
        private fun bytesToHex(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }
        
        /**
         * Guesses MIME type based on file extension.
         */
        private fun guessMimeType(fileName: String): String? {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when (extension) {
                "txt" -> "text/plain"
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "zip" -> "application/zip"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js" -> "application/javascript"
                else -> "application/octet-stream"
            }
        }
    }
    
    /**
     * Converts the manifest to JSON string for transmission.
     * 
     * @return JSON string representation of this manifest
     */
    fun toJson(): String {
        val jsonObject = JSONObject().apply {
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("chunkSize", chunkSize)
            put("chunkCount", chunkCount)
            put("chunkHashes", JSONArray(chunkHashes))
            put("fileHash", fileHash)
            put("mimeType", mimeType)
            put("lastModified", lastModified)
            put("transferId", transferId)
        }
        return jsonObject.toString()
    }
    
    /**
     * Validates the manifest for consistency and completeness.
     * 
     * @return true if the manifest is valid, false otherwise
     */
    fun isValid(): Boolean {
        return try {
            fileName.isNotBlank() &&
            fileSize >= 0 &&
            chunkSize > 0 &&
            chunkCount > 0 &&
            chunkHashes.size == chunkCount &&
            chunkHashes.all { it.isNotBlank() && it.length == 64 } && // SHA-256 is 64 hex chars
            fileHash.isNotBlank() && fileHash.length == 64 &&
            transferId.isNotBlank() &&
            calculateExpectedChunkCount() == chunkCount
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Calculates the expected number of chunks based on file size and chunk size.
     * 
     * @return Expected chunk count
     */
    fun calculateExpectedChunkCount(): Int {
        return if (fileSize == 0L) 1 else ((fileSize + chunkSize - 1) / chunkSize).toInt()
    }
    
    /**
     * Gets the size of a specific chunk.
     * 
     * @param chunkIndex Zero-based index of the chunk
     * @return Size of the specified chunk in bytes
     * @throws IndexOutOfBoundsException if chunk index is invalid
     */
    @Throws(IndexOutOfBoundsException::class)
    fun getChunkSize(chunkIndex: Int): Int {
        require(chunkIndex in 0 until chunkCount) { 
            "Chunk index $chunkIndex is out of bounds (0..$chunkCount)" 
        }
        
        return if (chunkIndex == chunkCount - 1) {
            // Last chunk might be smaller
            val remainder = (fileSize % chunkSize).toInt()
            if (remainder == 0) chunkSize else remainder
        } else {
            chunkSize
        }
    }
    
    /**
     * Gets the byte offset for a specific chunk.
     * 
     * @param chunkIndex Zero-based index of the chunk
     * @return Byte offset where the chunk starts in the file
     * @throws IndexOutOfBoundsException if chunk index is invalid
     */
    @Throws(IndexOutOfBoundsException::class)
    fun getChunkOffset(chunkIndex: Int): Long {
        require(chunkIndex in 0 until chunkCount) { 
            "Chunk index $chunkIndex is out of bounds (0..$chunkCount)" 
        }
        
        return chunkIndex.toLong() * chunkSize
    }
    
    /**
     * Gets the hash for a specific chunk.
     * 
     * @param chunkIndex Zero-based index of the chunk
     * @return SHA-256 hash of the specified chunk
     * @throws IndexOutOfBoundsException if chunk index is invalid
     */
    @Throws(IndexOutOfBoundsException::class)
    fun getChunkHash(chunkIndex: Int): String {
        require(chunkIndex in 0 until chunkCount) { 
            "Chunk index $chunkIndex is out of bounds (0..$chunkCount)" 
        }
        
        return chunkHashes[chunkIndex]
    }
    
    /**
     * Verifies a chunk's integrity against its expected hash.
     * 
     * @param chunkIndex Zero-based index of the chunk
     * @param chunkData The actual chunk data to verify
     * @return true if the chunk hash matches, false otherwise
     */
    fun verifyChunk(chunkIndex: Int, chunkData: ByteArray): Boolean {
        return try {
            val expectedHash = getChunkHash(chunkIndex)
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            digest.update(chunkData)
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            actualHash == expectedHash
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Verifies the complete file's integrity against its expected hash.
     * 
     * @param fileData The complete file data to verify
     * @return true if the file hash matches, false otherwise
     */
    fun verifyFile(fileData: ByteArray): Boolean {
        return try {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            digest.update(fileData)
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            actualHash == fileHash
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Creates a summary string for logging and debugging.
     * 
     * @return Human-readable summary of the manifest
     */
    fun getSummary(): String {
        val sizeInMB = fileSize / (1024.0 * 1024.0)
        return "FileManifest(fileName='$fileName', size=${String.format("%.2f", sizeInMB)}MB, " +
               "chunks=$chunkCount, chunkSize=${chunkSize/1024}KB, transferId='$transferId')"
    }
}
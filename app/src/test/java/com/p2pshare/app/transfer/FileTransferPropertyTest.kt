package com.p2pshare.app.transfer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*

/**
 * Property-based tests for file transfer round-trip consistency.
 * 
 * Feature: p2p-file-share, Property 1: File Transfer Round Trip
 * Validates: Requirements 5.5
 * 
 * Note: These tests simulate the file transfer process without actual network
 * communication by using the core transfer logic directly.
 */
class FileTransferPropertyTest : StringSpec({
    
    "File transfer round trip should preserve original file data" {
        // Feature: p2p-file-share, Property 1: File Transfer Round Trip
        checkAll<ByteArray>(iterations = 30, Arb.byteArray(Arb.int(100..10000))) { originalData ->
            // Create temporary files for testing
            val sourceFile = createTempFileWithData(originalData, "source")
            val outputDir = createTempDirectory()
            
            try {
                // Create manifest from source file
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(sourceFile, transferId, 1024) // 1KB chunks
                
                // Simulate sender side: split file into chunks
                val senderChunkManager = ChunkManager(manifest)
                val chunkRanges = senderChunkManager.splitFile(sourceFile)
                
                // Simulate receiver side: receive and assemble chunks
                val receiverChunkManager = ChunkManager(manifest)
                
                // Transfer all chunks (simulating network transfer)
                for (chunkRange in chunkRanges) {
                    val chunkData = senderChunkManager.readChunk(sourceFile, chunkRange.chunkIndex)
                    
                    // Verify chunk integrity during transfer
                    manifest.verifyChunk(chunkRange.chunkIndex, chunkData) shouldBe true
                    
                    // Store chunk on receiver side
                    val stored = receiverChunkManager.storeChunk(chunkRange.chunkIndex, chunkData)
                    stored shouldBe true
                }
                
                // Verify transfer completion
                receiverChunkManager.isTransferComplete() shouldBe true
                receiverChunkManager.getReceivedChunkCount() shouldBe manifest.chunkCount
                receiverChunkManager.getProgressPercentage() shouldBe 100f
                
                // Assemble received file
                val receivedData = receiverChunkManager.assembleFile()
                
                // Verify round-trip consistency
                receivedData shouldBe originalData
                receivedData.size shouldBe originalData.size
                
                // Verify file integrity
                manifest.verifyFile(receivedData) shouldBe true
                
            } finally {
                sourceFile.delete()
                outputDir.deleteRecursively()
            }
        }
    }
    
    "File transfer should handle various chunk sizes correctly" {
        checkAll<ByteArray, Int>(
            iterations = 20,
            Arb.byteArray(Arb.int(500..5000)),
            Arb.int(64..2048)
        ) { originalData, chunkSize ->
            val sourceFile = createTempFileWithData(originalData, "chunk_test")
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(sourceFile, transferId, chunkSize)
                
                // Verify chunk size calculations
                val expectedChunkCount = if (originalData.isEmpty()) 1 else 
                    ((originalData.size + chunkSize - 1) / chunkSize)
                manifest.chunkCount shouldBe expectedChunkCount
                
                // Test round-trip with this chunk size
                val senderChunkManager = ChunkManager(manifest)
                val receiverChunkManager = ChunkManager(manifest)
                
                // Transfer all chunks
                for (chunkIndex in 0 until manifest.chunkCount) {
                    val chunkData = senderChunkManager.readChunk(sourceFile, chunkIndex)
                    
                    // Verify chunk size is correct
                    val expectedSize = manifest.getChunkSize(chunkIndex)
                    chunkData.size shouldBe expectedSize
                    
                    // Transfer chunk
                    receiverChunkManager.storeChunk(chunkIndex, chunkData) shouldBe true
                }
                
                // Verify final result
                val receivedData = receiverChunkManager.assembleFile()
                receivedData shouldBe originalData
                
            } finally {
                sourceFile.delete()
            }
        }
    }
    
    "File transfer should maintain integrity across different file sizes" {
        val fileSizes = listOf(0, 1, 63, 64, 65, 255, 256, 257, 1023, 1024, 1025, 4095, 4096, 4097)
        
        fileSizes.forEach { fileSize ->
            val originalData = ByteArray(fileSize) { (it % 256).toByte() }
            val sourceFile = createTempFileWithData(originalData, "size_test_$fileSize")
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(sourceFile, transferId, 256)
                
                val senderChunkManager = ChunkManager(manifest)
                val receiverChunkManager = ChunkManager(manifest)
                
                // Transfer all chunks
                for (chunkIndex in 0 until manifest.chunkCount) {
                    val chunkData = senderChunkManager.readChunk(sourceFile, chunkIndex)
                    receiverChunkManager.storeChunk(chunkIndex, chunkData) shouldBe true
                }
                
                // Verify result
                val receivedData = receiverChunkManager.assembleFile()
                receivedData shouldBe originalData
                receivedData.size shouldBe fileSize
                
            } finally {
                sourceFile.delete()
            }
        }
    }
    
    "File transfer should handle out-of-order chunk delivery" {
        checkAll<ByteArray>(iterations = 20, Arb.byteArray(Arb.int(1000..5000))) { originalData ->
            val sourceFile = createTempFileWithData(originalData, "out_of_order")
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(sourceFile, transferId, 512)
                
                if (manifest.chunkCount > 1) {
                    val senderChunkManager = ChunkManager(manifest)
                    val receiverChunkManager = ChunkManager(manifest)
                    
                    // Read all chunks first
                    val allChunks = mutableMapOf<Int, ByteArray>()
                    for (chunkIndex in 0 until manifest.chunkCount) {
                        allChunks[chunkIndex] = senderChunkManager.readChunk(sourceFile, chunkIndex)
                    }
                    
                    // Deliver chunks in reverse order (out-of-order)
                    for (chunkIndex in (manifest.chunkCount - 1) downTo 0) {
                        val chunkData = allChunks[chunkIndex]!!
                        receiverChunkManager.storeChunk(chunkIndex, chunkData) shouldBe true
                    }
                    
                    // Verify transfer completion and integrity
                    receiverChunkManager.isTransferComplete() shouldBe true
                    val receivedData = receiverChunkManager.assembleFile()
                    receivedData shouldBe originalData
                }
                
            } finally {
                sourceFile.delete()
            }
        }
    }
    
    "File transfer should handle partial transfers and resume correctly" {
        checkAll<ByteArray>(iterations = 15, Arb.byteArray(Arb.int(2000..8000))) { originalData ->
            val sourceFile = createTempFileWithData(originalData, "partial_transfer")
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(sourceFile, transferId, 400)
                
                if (manifest.chunkCount > 3) {
                    val senderChunkManager = ChunkManager(manifest)
                    val receiverChunkManager = ChunkManager(manifest)
                    
                    // Transfer only first half of chunks
                    val halfChunks = manifest.chunkCount / 2
                    for (chunkIndex in 0 until halfChunks) {
                        val chunkData = senderChunkManager.readChunk(sourceFile, chunkIndex)
                        receiverChunkManager.storeChunk(chunkIndex, chunkData) shouldBe true
                    }
                    
                    // Verify partial state
                    receiverChunkManager.isTransferComplete() shouldBe false
                    receiverChunkManager.getReceivedChunkCount() shouldBe halfChunks
                    
                    val missingChunks = receiverChunkManager.getMissingChunks()
                    missingChunks.size shouldBe (manifest.chunkCount - halfChunks)
                    
                    // Resume transfer (send remaining chunks)
                    for (chunkIndex in missingChunks) {
                        val chunkData = senderChunkManager.readChunk(sourceFile, chunkIndex)
                        receiverChunkManager.storeChunk(chunkIndex, chunkData) shouldBe true
                    }
                    
                    // Verify completion after resume
                    receiverChunkManager.isTransferComplete() shouldBe true
                    val receivedData = receiverChunkManager.assembleFile()
                    receivedData shouldBe originalData
                }
                
            } finally {
                sourceFile.delete()
            }
        }
    }
    
    "File transfer should reject corrupted chunks and maintain integrity" {
        checkAll<ByteArray>(iterations = 15, Arb.byteArray(Arb.int(1000..4000))) { originalData ->
            val sourceFile = createTempFileWithData(originalData, "corruption_test")
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(sourceFile, transferId, 512)
                
                if (manifest.chunkCount > 0) {
                    val senderChunkManager = ChunkManager(manifest)
                    val receiverChunkManager = ChunkManager(manifest)
                    
                    // Transfer valid chunks
                    for (chunkIndex in 0 until manifest.chunkCount) {
                        val chunkData = senderChunkManager.readChunk(sourceFile, chunkIndex)
                        
                        if (chunkIndex == 0 && chunkData.isNotEmpty()) {
                            // Create corrupted version of first chunk
                            val corruptedChunk = chunkData.copyOf()
                            corruptedChunk[0] = (corruptedChunk[0].toInt() xor 1).toByte()
                            
                            // Corrupted chunk should be rejected
                            receiverChunkManager.storeChunk(chunkIndex, corruptedChunk) shouldBe false
                            receiverChunkManager.isChunkReceived(chunkIndex) shouldBe false
                            
                            // Valid chunk should be accepted
                            receiverChunkManager.storeChunk(chunkIndex, chunkData) shouldBe true
                            receiverChunkManager.isChunkReceived(chunkIndex) shouldBe true
                        } else {
                            // Transfer other chunks normally
                            receiverChunkManager.storeChunk(chunkIndex, chunkData) shouldBe true
                        }
                    }
                    
                    // Verify final integrity
                    receiverChunkManager.isTransferComplete() shouldBe true
                    val receivedData = receiverChunkManager.assembleFile()
                    receivedData shouldBe originalData
                }
                
            } finally {
                sourceFile.delete()
            }
        }
    }
    
    "Manifest round-trip should preserve all metadata" {
        checkAll<ByteArray>(iterations = 20, Arb.byteArray(Arb.int(100..3000))) { originalData ->
            val sourceFile = createTempFileWithData(originalData, "manifest_test")
            
            try {
                val transferId = UUID.randomUUID().toString()
                val originalManifest = FileManifest.fromFile(sourceFile, transferId, 333)
                
                // Serialize and deserialize manifest (simulating network transfer)
                val manifestJson = originalManifest.toJson()
                val deserializedManifest = FileManifest.fromJson(manifestJson)
                
                // Verify all fields are preserved
                deserializedManifest.fileName shouldBe originalManifest.fileName
                deserializedManifest.fileSize shouldBe originalManifest.fileSize
                deserializedManifest.chunkSize shouldBe originalManifest.chunkSize
                deserializedManifest.chunkCount shouldBe originalManifest.chunkCount
                deserializedManifest.chunkHashes shouldBe originalManifest.chunkHashes
                deserializedManifest.fileHash shouldBe originalManifest.fileHash
                deserializedManifest.transferId shouldBe originalManifest.transferId
                
                // Verify manifest validity
                deserializedManifest.isValid() shouldBe true
                
                // Verify chunk operations work with deserialized manifest
                val chunkManager = ChunkManager(deserializedManifest)
                for (chunkIndex in 0 until deserializedManifest.chunkCount) {
                    val chunkData = chunkManager.readChunk(sourceFile, chunkIndex)
                    deserializedManifest.verifyChunk(chunkIndex, chunkData) shouldBe true
                }
                
            } finally {
                sourceFile.delete()
            }
        }
    }
    
    /**
     * Helper function to create a temporary file with test data.
     */
    private fun createTempFileWithData(data: ByteArray, prefix: String): File {
        val tempFile = File.createTempFile("test_${prefix}_", ".tmp")
        tempFile.writeBytes(data)
        return tempFile
    }
    
    /**
     * Helper function to create a temporary directory.
     */
    private fun createTempDirectory(): File {
        val tempDir = File.createTempFile("test_dir_", "").apply {
            delete()
            mkdirs()
        }
        return tempDir
    }
})
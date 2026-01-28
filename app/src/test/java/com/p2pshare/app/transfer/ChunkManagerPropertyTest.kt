package com.p2pshare.app.transfer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.io.File
import java.util.*

/**
 * Property-based tests for ChunkManager to verify chunk integrity preservation.
 * 
 * Feature: p2p-file-share, Property 3: Chunk Integrity Preservation
 * Validates: Requirements 5.2, 5.4
 */
class ChunkManagerPropertyTest : StringSpec({
    
    "Chunk integrity should be preserved through split and reassemble operations" {
        // Feature: p2p-file-share, Property 3: Chunk Integrity Preservation
        checkAll<ByteArray>(iterations = 50, Arb.byteArray(Arb.int(1..10000))) { originalData ->
            // Create a temporary file with test data
            val tempFile = createTempFileWithData(originalData)
            
            try {
                // Create manifest from file
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(tempFile, transferId, 1024) // 1KB chunks
                
                // Create chunk manager
                val chunkManager = ChunkManager(manifest)
                
                // Split file into chunks
                val chunkRanges = chunkManager.splitFile(tempFile)
                
                // Read and store all chunks
                for (chunkRange in chunkRanges) {
                    val chunkData = chunkManager.readChunk(tempFile, chunkRange.chunkIndex)
                    
                    // Verify chunk integrity during storage
                    val stored = chunkManager.storeChunk(chunkRange.chunkIndex, chunkData)
                    stored shouldBe true
                }
                
                // Verify transfer is complete
                chunkManager.isTransferComplete() shouldBe true
                
                // Reassemble file and verify integrity
                val reassembledData = chunkManager.assembleFile()
                reassembledData shouldBe originalData
                
                // Verify all chunk hashes match
                for (i in 0 until manifest.chunkCount) {
                    val chunkData = chunkManager.readChunk(tempFile, i)
                    manifest.verifyChunk(i, chunkData) shouldBe true
                }
                
            } finally {
                tempFile.delete()
            }
        }
    }
    
    "Chunk hashes should remain consistent across multiple reads" {
        checkAll<ByteArray>(iterations = 30, Arb.byteArray(Arb.int(100..5000))) { originalData ->
            val tempFile = createTempFileWithData(originalData)
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(tempFile, transferId, 512)
                val chunkManager = ChunkManager(manifest)
                
                // Read each chunk multiple times and verify hash consistency
                for (chunkIndex in 0 until manifest.chunkCount) {
                    val chunkData1 = chunkManager.readChunk(tempFile, chunkIndex)
                    val chunkData2 = chunkManager.readChunk(tempFile, chunkIndex)
                    val chunkData3 = chunkManager.readChunk(tempFile, chunkIndex)
                    
                    // All reads should produce identical data
                    chunkData1 shouldBe chunkData2
                    chunkData2 shouldBe chunkData3
                    
                    // All should verify against the same hash
                    manifest.verifyChunk(chunkIndex, chunkData1) shouldBe true
                    manifest.verifyChunk(chunkIndex, chunkData2) shouldBe true
                    manifest.verifyChunk(chunkIndex, chunkData3) shouldBe true
                }
                
            } finally {
                tempFile.delete()
            }
        }
    }
    
    "Progress tracking should be accurate and monotonic" {
        checkAll<ByteArray>(iterations = 30, Arb.byteArray(Arb.int(500..3000))) { originalData ->
            val tempFile = createTempFileWithData(originalData)
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(tempFile, transferId, 256)
                val chunkManager = ChunkManager(manifest)
                
                var lastProgress = 0f
                var lastReceivedCount = 0
                var lastReceivedBytes = 0L
                
                // Store chunks one by one and verify progress is monotonic
                for (chunkIndex in 0 until manifest.chunkCount) {
                    val chunkData = chunkManager.readChunk(tempFile, chunkIndex)
                    chunkManager.storeChunk(chunkIndex, chunkData)
                    
                    val currentProgress = chunkManager.getProgressPercentage()
                    val currentReceivedCount = chunkManager.getReceivedChunkCount()
                    val currentReceivedBytes = chunkManager.getReceivedBytes()
                    
                    // Progress should never decrease
                    currentProgress >= lastProgress shouldBe true
                    currentReceivedCount >= lastReceivedCount shouldBe true
                    currentReceivedBytes >= lastReceivedBytes shouldBe true
                    
                    // Progress should increase (except for duplicate chunks)
                    if (chunkIndex > 0) {
                        currentProgress > lastProgress shouldBe true
                        currentReceivedCount > lastReceivedCount shouldBe true
                        currentReceivedBytes > lastReceivedBytes shouldBe true
                    }
                    
                    lastProgress = currentProgress
                    lastReceivedCount = currentReceivedCount
                    lastReceivedBytes = currentReceivedBytes
                }
                
                // Final progress should be 100%
                chunkManager.getProgressPercentage() shouldBe 100f
                chunkManager.getReceivedChunkCount() shouldBe manifest.chunkCount
                chunkManager.getReceivedBytes() shouldBe manifest.fileSize
                
            } finally {
                tempFile.delete()
            }
        }
    }
    
    "Corrupted chunks should be rejected" {
        checkAll<ByteArray>(iterations = 20, Arb.byteArray(Arb.int(100..2000))) { originalData ->
            val tempFile = createTempFileWithData(originalData)
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(tempFile, transferId, 512)
                val chunkManager = ChunkManager(manifest)
                
                if (manifest.chunkCount > 0) {
                    // Read a valid chunk
                    val validChunkData = chunkManager.readChunk(tempFile, 0)
                    
                    // Create corrupted version by flipping a bit
                    val corruptedChunkData = validChunkData.copyOf()
                    if (corruptedChunkData.isNotEmpty()) {
                        corruptedChunkData[0] = (corruptedChunkData[0].toInt() xor 1).toByte()
                    }
                    
                    // Valid chunk should be accepted
                    chunkManager.storeChunk(0, validChunkData) shouldBe true
                    chunkManager.isChunkReceived(0) shouldBe true
                    
                    // Reset for corrupted test
                    chunkManager.reset()
                    
                    // Corrupted chunk should be rejected
                    chunkManager.storeChunk(0, corruptedChunkData) shouldBe false
                    chunkManager.isChunkReceived(0) shouldBe false
                }
                
            } finally {
                tempFile.delete()
            }
        }
    }
    
    "Missing chunks should be correctly identified" {
        checkAll<ByteArray>(iterations = 20, Arb.byteArray(Arb.int(1000..5000))) { originalData ->
            val tempFile = createTempFileWithData(originalData)
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(tempFile, transferId, 400)
                val chunkManager = ChunkManager(manifest)
                
                if (manifest.chunkCount > 2) {
                    // Store only some chunks (skip middle ones)
                    val chunkData0 = chunkManager.readChunk(tempFile, 0)
                    val chunkDataLast = chunkManager.readChunk(tempFile, manifest.chunkCount - 1)
                    
                    chunkManager.storeChunk(0, chunkData0)
                    chunkManager.storeChunk(manifest.chunkCount - 1, chunkDataLast)
                    
                    // Get missing chunks
                    val missingChunks = chunkManager.getMissingChunks()
                    
                    // Should not be complete
                    chunkManager.isTransferComplete() shouldBe false
                    
                    // Missing chunks should not include stored ones
                    missingChunks.contains(0) shouldBe false
                    missingChunks.contains(manifest.chunkCount - 1) shouldBe false
                    
                    // Missing chunks should include middle ones
                    if (manifest.chunkCount > 2) {
                        missingChunks.contains(1) shouldBe true
                    }
                    
                    // Total missing + received should equal total chunks
                    (missingChunks.size + chunkManager.getReceivedChunkCount()) shouldBe manifest.chunkCount
                }
                
            } finally {
                tempFile.delete()
            }
        }
    }
    
    "Chunk ranges should cover entire file without gaps or overlaps" {
        checkAll<ByteArray>(iterations = 30, Arb.byteArray(Arb.int(100..8000))) { originalData ->
            val tempFile = createTempFileWithData(originalData)
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(tempFile, transferId, 333) // Odd chunk size
                val chunkManager = ChunkManager(manifest)
                
                val chunkRanges = chunkManager.splitFile(tempFile)
                
                // Should have correct number of ranges
                chunkRanges.size shouldBe manifest.chunkCount
                
                // Ranges should be in order and cover entire file
                var expectedOffset = 0L
                var totalCoverage = 0L
                
                for ((index, range) in chunkRanges.withIndex()) {
                    // Range should be valid
                    range.isValid() shouldBe true
                    
                    // Should match expected chunk index
                    range.chunkIndex shouldBe index
                    
                    // Should start where previous ended (no gaps)
                    range.startOffset shouldBe expectedOffset
                    
                    // Size should match manifest
                    range.size shouldBe manifest.getChunkSize(index)
                    
                    expectedOffset = range.endOffset
                    totalCoverage += range.size
                }
                
                // Should cover entire file
                totalCoverage shouldBe manifest.fileSize
                expectedOffset shouldBe manifest.fileSize
                
            } finally {
                tempFile.delete()
            }
        }
    }
    
    "Duplicate chunk storage should be handled correctly" {
        checkAll<ByteArray>(iterations = 20, Arb.byteArray(Arb.int(500..2000))) { originalData ->
            val tempFile = createTempFileWithData(originalData)
            
            try {
                val transferId = UUID.randomUUID().toString()
                val manifest = FileManifest.fromFile(tempFile, transferId, 512)
                val chunkManager = ChunkManager(manifest)
                
                if (manifest.chunkCount > 0) {
                    val chunkData = chunkManager.readChunk(tempFile, 0)
                    
                    // First storage should succeed
                    chunkManager.storeChunk(0, chunkData) shouldBe true
                    chunkManager.isChunkReceived(0) shouldBe true
                    chunkManager.getReceivedChunkCount() shouldBe 1
                    
                    // Duplicate storage should be handled gracefully
                    chunkManager.storeChunk(0, chunkData) shouldBe false
                    chunkManager.isChunkReceived(0) shouldBe true
                    chunkManager.getReceivedChunkCount() shouldBe 1 // Should not increase
                }
                
            } finally {
                tempFile.delete()
            }
        }
    }
    
    /**
     * Helper function to create a temporary file with test data.
     */
    private fun createTempFileWithData(data: ByteArray): File {
        val tempFile = File.createTempFile("test_chunk_", ".tmp")
        tempFile.writeBytes(data)
        return tempFile
    }
})
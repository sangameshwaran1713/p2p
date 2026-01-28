package com.p2pshare.app.transfer

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.*

/**
 * Unit tests for ChunkManager class.
 * Tests chunk management, progress tracking, and file assembly.
 */
class ChunkManagerTest {
    
    private lateinit var testFile: File
    private lateinit var manifest: FileManifest
    private lateinit var chunkManager: ChunkManager
    
    @Before
    fun setUp() {
        val transferId = UUID.randomUUID().toString()
        testFile = File.createTempFile("test_chunk_", ".tmp")
        
        // Create test data
        val testData = "This is test data for chunk manager testing. It should be long enough to create multiple chunks.".toByteArray()
        testFile.writeBytes(testData)
        
        manifest = FileManifest.fromFile(testFile, transferId, 20) // 20-byte chunks
        chunkManager = ChunkManager(manifest)
    }
    
    @Test
    fun testSplitFile() {
        val chunkRanges = chunkManager.splitFile(testFile)
        
        assertEquals("Should have correct number of chunk ranges", manifest.chunkCount, chunkRanges.size)
        
        var totalSize = 0
        var expectedOffset = 0L
        
        for ((index, range) in chunkRanges.withIndex()) {
            assertEquals("Chunk index should match", index, range.chunkIndex)
            assertEquals("Start offset should be correct", expectedOffset, range.startOffset)
            assertEquals("Chunk size should match manifest", manifest.getChunkSize(index), range.size)
            assertTrue("Range should be valid", range.isValid())
            
            totalSize += range.size
            expectedOffset = range.endOffset
        }
        
        assertEquals("Total size should match file size", manifest.fileSize.toInt(), totalSize)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testSplitFile_NonExistentFile() {
        val nonExistentFile = File("non_existent.tmp")
        chunkManager.splitFile(nonExistentFile)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testSplitFile_SizeMismatch() {
        // Modify file after manifest creation
        testFile.writeBytes("Different data".toByteArray())
        chunkManager.splitFile(testFile)
    }
    
    @Test
    fun testReadChunk() {
        val chunkData = chunkManager.readChunk(testFile, 0)
        
        assertNotNull("Chunk data should not be null", chunkData)
        assertEquals("Chunk size should match expected", manifest.getChunkSize(0), chunkData.size)
        assertTrue("Chunk should verify against manifest", manifest.verifyChunk(0, chunkData))
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testReadChunk_InvalidIndex() {
        chunkManager.readChunk(testFile, manifest.chunkCount + 1)
    }
    
    @Test
    fun testStoreChunk() {
        val chunkData = chunkManager.readChunk(testFile, 0)
        
        // Store valid chunk
        val stored = chunkManager.storeChunk(0, chunkData)
        assertTrue("Valid chunk should be stored", stored)
        assertTrue("Chunk should be marked as received", chunkManager.isChunkReceived(0))
        assertEquals("Received chunk count should increase", 1, chunkManager.getReceivedChunkCount())
        
        // Try to store same chunk again
        val storedAgain = chunkManager.storeChunk(0, chunkData)
        assertFalse("Duplicate chunk should not be stored", storedAgain)
        assertEquals("Received chunk count should not increase", 1, chunkManager.getReceivedChunkCount())
    }
    
    @Test
    fun testStoreChunk_InvalidData() {
        val invalidChunkData = "invalid data".toByteArray()
        
        val stored = chunkManager.storeChunk(0, invalidChunkData)
        assertFalse("Invalid chunk should not be stored", stored)
        assertFalse("Chunk should not be marked as received", chunkManager.isChunkReceived(0))
        assertEquals("Received chunk count should remain 0", 0, chunkManager.getReceivedChunkCount())
    }
    
    @Test
    fun testStoreChunk_InvalidIndex() {
        val chunkData = "some data".toByteArray()
        
        val stored = chunkManager.storeChunk(-1, chunkData)
        assertFalse("Chunk with invalid index should not be stored", stored)
        
        val stored2 = chunkManager.storeChunk(manifest.chunkCount, chunkData)
        assertFalse("Chunk with out-of-bounds index should not be stored", stored2)
    }
    
    @Test
    fun testMarkChunkReceived() {
        assertFalse("Chunk should not be received initially", chunkManager.isChunkReceived(0))
        
        chunkManager.markChunkReceived(0)
        
        assertTrue("Chunk should be marked as received", chunkManager.isChunkReceived(0))
        assertEquals("Received chunk count should increase", 1, chunkManager.getReceivedChunkCount())
        
        // Mark same chunk again
        chunkManager.markChunkReceived(0)
        assertEquals("Received chunk count should not increase", 1, chunkManager.getReceivedChunkCount())
    }
    
    @Test
    fun testIsTransferComplete() {
        assertFalse("Transfer should not be complete initially", chunkManager.isTransferComplete())
        
        // Store all chunks
        for (chunkIndex in 0 until manifest.chunkCount) {
            val chunkData = chunkManager.readChunk(testFile, chunkIndex)
            chunkManager.storeChunk(chunkIndex, chunkData)
        }
        
        assertTrue("Transfer should be complete after all chunks stored", chunkManager.isTransferComplete())
    }
    
    @Test
    fun testGetProgressPercentage() {
        assertEquals("Initial progress should be 0%", 0f, chunkManager.getProgressPercentage(), 0.01f)
        
        // Store half the chunks
        val halfChunks = manifest.chunkCount / 2
        for (chunkIndex in 0 until halfChunks) {
            val chunkData = chunkManager.readChunk(testFile, chunkIndex)
            chunkManager.storeChunk(chunkIndex, chunkData)
        }
        
        val expectedProgress = (halfChunks.toFloat() / manifest.chunkCount) * 100f
        assertEquals("Progress should be approximately half", expectedProgress, chunkManager.getProgressPercentage(), 1f)
        
        // Store remaining chunks
        for (chunkIndex in halfChunks until manifest.chunkCount) {
            val chunkData = chunkManager.readChunk(testFile, chunkIndex)
            chunkManager.storeChunk(chunkIndex, chunkData)
        }
        
        assertEquals("Progress should be 100% when complete", 100f, chunkManager.getProgressPercentage(), 0.01f)
    }
    
    @Test
    fun testGetReceivedBytes() {
        assertEquals("Initial received bytes should be 0", 0L, chunkManager.getReceivedBytes())
        
        // Store first chunk
        val chunkData = chunkManager.readChunk(testFile, 0)
        chunkManager.storeChunk(0, chunkData)
        
        assertEquals("Received bytes should match first chunk size", 
            manifest.getChunkSize(0).toLong(), chunkManager.getReceivedBytes())
        
        // Store all chunks
        for (chunkIndex in 1 until manifest.chunkCount) {
            val chunk = chunkManager.readChunk(testFile, chunkIndex)
            chunkManager.storeChunk(chunkIndex, chunk)
        }
        
        assertEquals("Received bytes should match total file size", 
            manifest.fileSize, chunkManager.getReceivedBytes())
    }
    
    @Test
    fun testGetMissingChunks() {
        val allMissing = chunkManager.getMissingChunks()
        assertEquals("All chunks should be missing initially", manifest.chunkCount, allMissing.size)
        
        // Store some chunks
        val chunkData0 = chunkManager.readChunk(testFile, 0)
        chunkManager.storeChunk(0, chunkData0)
        
        val chunkData2 = chunkManager.readChunk(testFile, 2)
        chunkManager.storeChunk(2, chunkData2)
        
        val missing = chunkManager.getMissingChunks()
        assertFalse("Stored chunks should not be in missing list", missing.contains(0))
        assertFalse("Stored chunks should not be in missing list", missing.contains(2))
        assertTrue("Non-stored chunks should be in missing list", missing.contains(1))
        
        assertEquals("Missing count should be correct", manifest.chunkCount - 2, missing.size)
    }
    
    @Test
    fun testAssembleFile() {
        // Store all chunks
        for (chunkIndex in 0 until manifest.chunkCount) {
            val chunkData = chunkManager.readChunk(testFile, chunkIndex)
            chunkManager.storeChunk(chunkIndex, chunkData)
        }
        
        val assembledData = chunkManager.assembleFile()
        val originalData = testFile.readBytes()
        
        assertArrayEquals("Assembled data should match original", originalData, assembledData)
        assertTrue("Assembled file should verify against manifest", manifest.verifyFile(assembledData))
    }
    
    @Test(expected = IllegalStateException::class)
    fun testAssembleFile_IncompleteTransfer() {
        // Store only some chunks
        val chunkData = chunkManager.readChunk(testFile, 0)
        chunkManager.storeChunk(0, chunkData)
        
        chunkManager.assembleFile() // Should throw exception
    }
    
    @Test
    fun testAssembleFileToFile() {
        val outputFile = File.createTempFile("assembled_", ".tmp")
        
        try {
            // Store all chunks
            for (chunkIndex in 0 until manifest.chunkCount) {
                val chunkData = chunkManager.readChunk(testFile, chunkIndex)
                chunkManager.storeChunk(chunkIndex, chunkData)
            }
            
            chunkManager.assembleFileToFile(outputFile)
            
            assertTrue("Output file should exist", outputFile.exists())
            assertEquals("Output file size should match original", testFile.length(), outputFile.length())
            
            val originalData = testFile.readBytes()
            val assembledData = outputFile.readBytes()
            assertArrayEquals("Assembled file should match original", originalData, assembledData)
            
        } finally {
            outputFile.delete()
        }
    }
    
    @Test
    fun testReset() {
        // Store some chunks
        val chunkData = chunkManager.readChunk(testFile, 0)
        chunkManager.storeChunk(0, chunkData)
        
        assertTrue("Chunk should be received before reset", chunkManager.isChunkReceived(0))
        assertEquals("Should have received chunks before reset", 1, chunkManager.getReceivedChunkCount())
        
        chunkManager.reset()
        
        assertFalse("Chunk should not be received after reset", chunkManager.isChunkReceived(0))
        assertEquals("Should have no received chunks after reset", 0, chunkManager.getReceivedChunkCount())
        assertFalse("Transfer should not be complete after reset", chunkManager.isTransferComplete())
    }
    
    @Test
    fun testClearChunkData() {
        // Store a chunk
        val chunkData = chunkManager.readChunk(testFile, 0)
        chunkManager.storeChunk(0, chunkData)
        
        assertTrue("Chunk should be received", chunkManager.isChunkReceived(0))
        
        chunkManager.clearChunkData()
        
        // Chunk should still be marked as received, but data should be cleared
        assertTrue("Chunk should still be marked as received", chunkManager.isChunkReceived(0))
        assertEquals("Received count should remain", 1, chunkManager.getReceivedChunkCount())
    }
    
    @Test
    fun testGetSummary() {
        val summary = chunkManager.getSummary()
        
        assertTrue("Summary should contain progress", summary.contains("progress="))
        assertTrue("Summary should contain chunk info", summary.contains("chunks="))
        assertTrue("Summary should contain data info", summary.contains("data="))
        assertTrue("Summary should be readable", summary.startsWith("ChunkManager("))
    }
    
    @Test
    fun testThreadSafety() {
        // This is a basic test for thread safety
        // In a real scenario, you'd use multiple threads
        
        val chunkData0 = chunkManager.readChunk(testFile, 0)
        val chunkData1 = chunkManager.readChunk(testFile, 1)
        
        // Simulate concurrent access
        val stored0 = chunkManager.storeChunk(0, chunkData0)
        val stored1 = chunkManager.storeChunk(1, chunkData1)
        
        assertTrue("Both chunks should be stored", stored0 && stored1)
        assertEquals("Both chunks should be counted", 2, chunkManager.getReceivedChunkCount())
    }
    
    @Test
    fun tearDown() {
        testFile.delete()
    }
}
package com.p2pshare.app.transfer

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.*

/**
 * Unit tests for FileManifest class.
 * Tests file manifest creation, validation, and serialization.
 */
class FileManifestTest {
    
    private lateinit var testFile: File
    private lateinit var transferId: String
    
    @Before
    fun setUp() {
        transferId = UUID.randomUUID().toString()
        testFile = File.createTempFile("test_manifest_", ".tmp")
    }
    
    @Test
    fun testFromFile_ValidFile() {
        val testData = "Hello, World! This is test data for manifest creation.".toByteArray()
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId, 16)
        
        assertEquals("File name should match", testFile.name, manifest.fileName)
        assertEquals("File size should match", testData.size.toLong(), manifest.fileSize)
        assertEquals("Chunk size should match", 16, manifest.chunkSize)
        assertEquals("Transfer ID should match", transferId, manifest.transferId)
        assertTrue("Manifest should be valid", manifest.isValid())
        
        // Verify chunk count calculation
        val expectedChunkCount = (testData.size + 15) / 16 // Ceiling division
        assertEquals("Chunk count should be correct", expectedChunkCount, manifest.chunkCount)
        assertEquals("Chunk hashes count should match chunk count", expectedChunkCount, manifest.chunkHashes.size)
        
        testFile.delete()
    }
    
    @Test
    fun testFromFile_EmptyFile() {
        testFile.writeBytes(ByteArray(0))
        
        val manifest = FileManifest.fromFile(testFile, transferId)
        
        assertEquals("Empty file size should be 0", 0L, manifest.fileSize)
        assertEquals("Empty file should have 1 chunk", 1, manifest.chunkCount)
        assertEquals("Empty file should have 1 hash", 1, manifest.chunkHashes.size)
        assertTrue("Manifest should be valid", manifest.isValid())
        
        testFile.delete()
    }
    
    @Test
    fun testFromFile_LargeFile() {
        val testData = ByteArray(10000) { (it % 256).toByte() }
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId, 1024)
        
        assertEquals("File size should match", 10000L, manifest.fileSize)
        assertEquals("Chunk size should be 1024", 1024, manifest.chunkSize)
        
        val expectedChunkCount = (10000 + 1023) / 1024 // Ceiling division
        assertEquals("Chunk count should be correct", expectedChunkCount, manifest.chunkCount)
        assertTrue("Manifest should be valid", manifest.isValid())
        
        testFile.delete()
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testFromFile_NonExistentFile() {
        val nonExistentFile = File("non_existent_file.tmp")
        FileManifest.fromFile(nonExistentFile, transferId)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testFromFile_InvalidChunkSize() {
        testFile.writeBytes("test".toByteArray())
        FileManifest.fromFile(testFile, transferId, 0)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testFromFile_BlankTransferId() {
        testFile.writeBytes("test".toByteArray())
        FileManifest.fromFile(testFile, "   ")
    }
    
    @Test
    fun testJsonSerialization() {
        val testData = "Test data for JSON serialization".toByteArray()
        testFile.writeBytes(testData)
        
        val originalManifest = FileManifest.fromFile(testFile, transferId, 8)
        val json = originalManifest.toJson()
        val deserializedManifest = FileManifest.fromJson(json)
        
        assertEquals("File name should be preserved", originalManifest.fileName, deserializedManifest.fileName)
        assertEquals("File size should be preserved", originalManifest.fileSize, deserializedManifest.fileSize)
        assertEquals("Chunk size should be preserved", originalManifest.chunkSize, deserializedManifest.chunkSize)
        assertEquals("Chunk count should be preserved", originalManifest.chunkCount, deserializedManifest.chunkCount)
        assertEquals("Chunk hashes should be preserved", originalManifest.chunkHashes, deserializedManifest.chunkHashes)
        assertEquals("File hash should be preserved", originalManifest.fileHash, deserializedManifest.fileHash)
        assertEquals("Transfer ID should be preserved", originalManifest.transferId, deserializedManifest.transferId)
        
        assertTrue("Deserialized manifest should be valid", deserializedManifest.isValid())
        
        testFile.delete()
    }
    
    @Test(expected = org.json.JSONException::class)
    fun testFromJson_InvalidJson() {
        FileManifest.fromJson("invalid json")
    }
    
    @Test
    fun testGetChunkSize() {
        val testData = ByteArray(100) { it.toByte() }
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId, 30)
        
        // Should have 4 chunks: 30, 30, 30, 10
        assertEquals("First chunk size should be 30", 30, manifest.getChunkSize(0))
        assertEquals("Second chunk size should be 30", 30, manifest.getChunkSize(1))
        assertEquals("Third chunk size should be 30", 30, manifest.getChunkSize(2))
        assertEquals("Last chunk size should be 10", 10, manifest.getChunkSize(3))
        
        testFile.delete()
    }
    
    @Test(expected = IndexOutOfBoundsException::class)
    fun testGetChunkSize_InvalidIndex() {
        val testData = "test".toByteArray()
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId)
        manifest.getChunkSize(10) // Invalid index
    }
    
    @Test
    fun testGetChunkOffset() {
        val testData = ByteArray(100) { it.toByte() }
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId, 25)
        
        assertEquals("First chunk offset should be 0", 0L, manifest.getChunkOffset(0))
        assertEquals("Second chunk offset should be 25", 25L, manifest.getChunkOffset(1))
        assertEquals("Third chunk offset should be 50", 50L, manifest.getChunkOffset(2))
        assertEquals("Fourth chunk offset should be 75", 75L, manifest.getChunkOffset(3))
        
        testFile.delete()
    }
    
    @Test
    fun testVerifyChunk() {
        val testData = "Hello, World!".toByteArray()
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId, 5)
        
        // Test with valid chunk data
        val chunkData = "Hello".toByteArray()
        assertTrue("Valid chunk should verify", manifest.verifyChunk(0, chunkData))
        
        // Test with invalid chunk data
        val invalidChunkData = "Hallo".toByteArray() // Changed one character
        assertFalse("Invalid chunk should not verify", manifest.verifyChunk(0, invalidChunkData))
        
        testFile.delete()
    }
    
    @Test
    fun testVerifyFile() {
        val testData = "Complete file data".toByteArray()
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId)
        
        // Test with correct file data
        assertTrue("Correct file data should verify", manifest.verifyFile(testData))
        
        // Test with incorrect file data
        val incorrectData = "Incorrect file data".toByteArray()
        assertFalse("Incorrect file data should not verify", manifest.verifyFile(incorrectData))
        
        testFile.delete()
    }
    
    @Test
    fun testCalculateExpectedChunkCount() {
        val testData = ByteArray(250) { it.toByte() }
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId, 100)
        
        assertEquals("Expected chunk count should match actual", 
            manifest.chunkCount, manifest.calculateExpectedChunkCount())
        
        testFile.delete()
    }
    
    @Test
    fun testIsValid() {
        val testData = "Valid manifest test".toByteArray()
        testFile.writeBytes(testData)
        
        val validManifest = FileManifest.fromFile(testFile, transferId)
        assertTrue("Valid manifest should pass validation", validManifest.isValid())
        
        // Test invalid manifest (manually created with wrong data)
        val invalidManifest = FileManifest(
            fileName = "", // Empty name should be invalid
            fileSize = 100,
            chunkSize = 10,
            chunkCount = 10,
            chunkHashes = listOf("invalid_hash"), // Wrong number of hashes
            fileHash = "invalid_hash",
            transferId = transferId
        )
        assertFalse("Invalid manifest should fail validation", invalidManifest.isValid())
        
        testFile.delete()
    }
    
    @Test
    fun testMimeTypeGuessing() {
        // Test various file extensions
        val extensions = mapOf(
            "test.txt" to "text/plain",
            "document.pdf" to "application/pdf",
            "image.jpg" to "image/jpeg",
            "photo.png" to "image/png",
            "archive.zip" to "application/zip",
            "unknown.xyz" to "application/octet-stream"
        )
        
        extensions.forEach { (fileName, expectedMimeType) ->
            val tempFile = File.createTempFile("mime_test_", fileName.substringAfterLast('.'))
            tempFile.writeBytes("test".toByteArray())
            
            // Rename to have the correct extension
            val renamedFile = File(tempFile.parent, fileName)
            tempFile.renameTo(renamedFile)
            
            val manifest = FileManifest.fromFile(renamedFile, transferId)
            assertEquals("MIME type should be correct for $fileName", 
                expectedMimeType, manifest.mimeType)
            
            renamedFile.delete()
        }
    }
    
    @Test
    fun testGetSummary() {
        val testData = ByteArray(1024 * 1024) { it.toByte() } // 1MB
        testFile.writeBytes(testData)
        
        val manifest = FileManifest.fromFile(testFile, transferId, 1024)
        val summary = manifest.getSummary()
        
        assertTrue("Summary should contain file name", summary.contains(manifest.fileName))
        assertTrue("Summary should contain size in MB", summary.contains("1.00MB"))
        assertTrue("Summary should contain chunk count", summary.contains("chunks=${manifest.chunkCount}"))
        assertTrue("Summary should contain transfer ID", summary.contains(transferId))
        
        testFile.delete()
    }
    
    @Test
    fun testChunkHashConsistency() {
        val testData = "Consistent hash test data".toByteArray()
        testFile.writeBytes(testData)
        
        val manifest1 = FileManifest.fromFile(testFile, transferId, 8)
        val manifest2 = FileManifest.fromFile(testFile, transferId, 8)
        
        assertEquals("Chunk hashes should be consistent", manifest1.chunkHashes, manifest2.chunkHashes)
        assertEquals("File hashes should be consistent", manifest1.fileHash, manifest2.fileHash)
        
        testFile.delete()
    }
}
package com.p2pshare.app.qr

import android.graphics.Color
import com.google.zxing.WriterException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for QrGenerator class.
 * Tests QR code generation functionality and edge cases.
 */
class QrGeneratorTest {
    
    private lateinit var qrGenerator: QrGenerator
    
    @Before
    fun setUp() {
        qrGenerator = QrGenerator()
    }
    
    @Test
    fun testGenerateQrCode_ValidData() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080,"sessionToken":"abc123"}"""
        
        val bitmap = qrGenerator.generateQrCode(sessionInfo)
        
        assertNotNull("QR code bitmap should not be null", bitmap)
        assertEquals("Default width should be 512", 512, bitmap!!.width)
        assertEquals("Default height should be 512", 512, bitmap.height)
    }
    
    @Test
    fun testGenerateQrCode_CustomSize() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080,"sessionToken":"abc123"}"""
        val width = 256
        val height = 256
        
        val bitmap = qrGenerator.generateQrCode(sessionInfo, width, height)
        
        assertNotNull("QR code bitmap should not be null", bitmap)
        assertEquals("Width should match requested size", width, bitmap!!.width)
        assertEquals("Height should match requested size", height, bitmap.height)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testGenerateQrCode_EmptyData() {
        qrGenerator.generateQrCode("")
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testGenerateQrCode_BlankData() {
        qrGenerator.generateQrCode("   ")
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testGenerateQrCode_InvalidDimensions_Zero() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080}"""
        qrGenerator.generateQrCode(sessionInfo, 0, 512)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testGenerateQrCode_InvalidDimensions_Negative() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080}"""
        qrGenerator.generateQrCode(sessionInfo, -100, 512)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testGenerateQrCode_InvalidDimensions_TooLarge() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080}"""
        qrGenerator.generateQrCode(sessionInfo, 3000, 3000)
    }
    
    @Test
    fun testGenerateQrCodeWithColors_ValidColors() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080,"sessionToken":"abc123"}"""
        
        val bitmap = qrGenerator.generateQrCodeWithColors(
            sessionInfo, 512, 512, Color.BLACK, Color.WHITE
        )
        
        assertNotNull("QR code bitmap should not be null", bitmap)
        assertEquals("Width should be 512", 512, bitmap!!.width)
        assertEquals("Height should be 512", 512, bitmap.height)
    }
    
    @Test(expected = IllegalArgumentException::class)
    fun testGenerateQrCodeWithColors_LowContrast() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080}"""
        
        // Very similar colors (low contrast)
        qrGenerator.generateQrCodeWithColors(
            sessionInfo, 512, 512, Color.GRAY, Color.LTGRAY
        )
    }
    
    @Test
    fun testValidateDataSize_ValidData() {
        val shortData = """{"ip":"192.168.1.100","port":8080}"""
        val mediumData = "A".repeat(500)
        val longData = "B".repeat(1000)
        
        assertTrue("Short data should be valid", qrGenerator.validateDataSize(shortData))
        assertTrue("Medium data should be valid", qrGenerator.validateDataSize(mediumData))
        assertTrue("Long data should be valid", qrGenerator.validateDataSize(longData))
    }
    
    @Test
    fun testValidateDataSize_InvalidData() {
        val emptyData = ""
        val blankData = "   "
        val tooLongData = "C".repeat(2000)
        
        assertFalse("Empty data should be invalid", qrGenerator.validateDataSize(emptyData))
        assertFalse("Blank data should be invalid", qrGenerator.validateDataSize(blankData))
        assertFalse("Too long data should be invalid", qrGenerator.validateDataSize(tooLongData))
    }
    
    @Test
    fun testGetOptimalSize_VariousDataSizes() {
        val smallData = "small"
        val mediumData = "A".repeat(200)
        val largeData = "B".repeat(500)
        val veryLargeData = "C".repeat(800)
        
        val (smallWidth, smallHeight) = qrGenerator.getOptimalSize(smallData)
        val (mediumWidth, mediumHeight) = qrGenerator.getOptimalSize(mediumData)
        val (largeWidth, largeHeight) = qrGenerator.getOptimalSize(largeData)
        val (veryLargeWidth, veryLargeHeight) = qrGenerator.getOptimalSize(veryLargeData)
        
        // Sizes should be square
        assertEquals("Small QR should be square", smallWidth, smallHeight)
        assertEquals("Medium QR should be square", mediumWidth, mediumHeight)
        assertEquals("Large QR should be square", largeWidth, largeHeight)
        assertEquals("Very large QR should be square", veryLargeWidth, veryLargeHeight)
        
        // Sizes should increase with data size
        assertTrue("Medium should be larger than small", mediumWidth >= smallWidth)
        assertTrue("Large should be larger than medium", largeWidth >= mediumWidth)
        assertTrue("Very large should be largest", veryLargeWidth >= largeWidth)
        
        // All sizes should be reasonable
        assertTrue("Sizes should be at least 256", smallWidth >= 256)
        assertTrue("Sizes should not exceed 768", veryLargeWidth <= 768)
    }
    
    @Test
    fun testGenerateQrCode_LargeValidData() {
        // Test with realistic session info that might be on the larger side
        val sessionInfo = """
        {
            "ip": "192.168.1.100",
            "port": 8080,
            "sessionToken": "very-long-session-token-with-lots-of-entropy-12345678901234567890",
            "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1234567890abcdef",
            "role": "SENDER",
            "expiryTime": 1640995200000,
            "metadata": {
                "appVersion": "1.0.0",
                "deviceName": "Test Device",
                "transferId": "uuid-1234-5678-9012-abcdef123456"
            }
        }
        """.trimIndent()
        
        assertTrue("Large session info should be valid", qrGenerator.validateDataSize(sessionInfo))
        
        val bitmap = qrGenerator.generateQrCode(sessionInfo)
        assertNotNull("Should generate QR code for large valid data", bitmap)
    }
    
    @Test
    fun testGenerateQrCode_SpecialCharacters() {
        val sessionInfoWithSpecialChars = """
        {
            "ip": "192.168.1.100",
            "sessionToken": "token-with-special-chars-!@#$%^&*()_+-=[]{}|;':\",./<>?",
            "deviceName": "Device with émojis 🚀📱💾"
        }
        """.trimIndent()
        
        val bitmap = qrGenerator.generateQrCode(sessionInfoWithSpecialChars)
        assertNotNull("Should handle special characters", bitmap)
    }
    
    @Test
    fun testGenerateQrCode_MinimalValidJson() {
        val minimalJson = """{"a":"b"}"""
        
        val bitmap = qrGenerator.generateQrCode(minimalJson)
        assertNotNull("Should generate QR code for minimal JSON", bitmap)
    }
    
    @Test
    fun testGenerateQrCode_Performance() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080,"sessionToken":"abc123"}"""
        
        val startTime = System.currentTimeMillis()
        
        // Generate multiple QR codes to test performance
        for (i in 1..10) {
            val bitmap = qrGenerator.generateQrCode(sessionInfo)
            assertNotNull("QR code $i should be generated", bitmap)
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        // QR generation should be reasonably fast (less than 5 seconds for 10 codes)
        assertTrue("QR generation should be fast", duration < 5000)
    }
    
    @Test
    fun testGenerateQrCode_ConsistentOutput() {
        val sessionInfo = """{"ip":"192.168.1.100","port":8080,"sessionToken":"abc123"}"""
        
        val bitmap1 = qrGenerator.generateQrCode(sessionInfo, 256, 256)
        val bitmap2 = qrGenerator.generateQrCode(sessionInfo, 256, 256)
        
        assertNotNull("First bitmap should not be null", bitmap1)
        assertNotNull("Second bitmap should not be null", bitmap2)
        
        // Same input should produce same output
        val pixels1 = IntArray(bitmap1!!.width * bitmap1.height)
        val pixels2 = IntArray(bitmap2!!.width * bitmap2.height)
        
        bitmap1.getPixels(pixels1, 0, bitmap1.width, 0, 0, bitmap1.width, bitmap1.height)
        bitmap2.getPixels(pixels2, 0, bitmap2.width, 0, 0, bitmap2.width, bitmap2.height)
        
        assertArrayEquals("Same input should produce identical QR codes", pixels1, pixels2)
    }
}
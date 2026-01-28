package com.p2pshare.app.qr

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.json.JSONObject

/**
 * Property-based tests for QrGenerator to verify QR session info round-trip consistency.
 * 
 * Feature: p2p-file-share, Property 4: QR Session Info Round Trip
 * Validates: Requirements 2.3, 3.2
 */
class QrGeneratorPropertyTest : StringSpec({
    
    val qrGenerator = QrGenerator()
    val qrReader = MultiFormatReader()
    
    "QR code generation and scanning should preserve session information" {
        // Feature: p2p-file-share, Property 4: QR Session Info Round Trip
        checkAll<String, String, Int, String>(
            iterations = 50,
            Arb.string(5..15), // IP address
            Arb.string(5..20), // session token
            Arb.int(1024..65535), // port
            Arb.string(10..50) // public key
        ) { ip, sessionToken, port, publicKey ->
            // Create valid session info JSON
            val sessionInfo = createSessionInfoJson(ip, sessionToken, port, publicKey)
            
            // Generate QR code
            val qrBitmap = qrGenerator.generateQrCode(sessionInfo)
            qrBitmap shouldNotBe null
            
            // Decode QR code back to string
            val decodedInfo = decodeQrCode(qrBitmap!!)
            
            // Verify round-trip consistency
            decodedInfo shouldBe sessionInfo
            
            // Verify JSON structure is preserved
            val originalJson = JSONObject(sessionInfo)
            val decodedJson = JSONObject(decodedInfo)
            
            originalJson.getString("ip") shouldBe decodedJson.getString("ip")
            originalJson.getString("sessionToken") shouldBe decodedJson.getString("sessionToken")
            originalJson.getInt("port") shouldBe decodedJson.getInt("port")
            originalJson.getString("publicKey") shouldBe decodedJson.getString("publicKey")
        }
    }
    
    "QR code generation should handle various data sizes consistently" {
        checkAll<String>(iterations = 30, Arb.string(10..500)) { data ->
            if (qrGenerator.validateDataSize(data)) {
                val qrBitmap = qrGenerator.generateQrCode(data)
                qrBitmap shouldNotBe null
                
                val decodedData = decodeQrCode(qrBitmap!!)
                decodedData shouldBe data
            }
        }
    }
    
    "QR code optimal size calculation should be consistent" {
        checkAll<String>(iterations = 50, Arb.string(1..1000)) { sessionInfo ->
            val (width, height) = qrGenerator.getOptimalSize(sessionInfo)
            
            // Optimal size should be reasonable
            width shouldBe height // Should be square
            width >= 256 shouldBe true // Minimum reasonable size
            width <= 768 shouldBe true // Maximum reasonable size
            
            // Should be able to generate QR code with optimal size
            if (qrGenerator.validateDataSize(sessionInfo)) {
                val qrBitmap = qrGenerator.generateQrCode(sessionInfo, width, height)
                qrBitmap shouldNotBe null
                qrBitmap!!.width shouldBe width
                qrBitmap.height shouldBe height
            }
        }
    }
    
    "QR code generation with custom colors should preserve data" {
        checkAll<String>(iterations = 30, Arb.string(10..200)) { data ->
            if (qrGenerator.validateDataSize(data)) {
                // Test with high contrast colors
                val blackOnWhite = qrGenerator.generateQrCodeWithColors(
                    data, 512, 512, 
                    android.graphics.Color.BLACK, 
                    android.graphics.Color.WHITE
                )
                
                blackOnWhite shouldNotBe null
                val decodedData = decodeQrCode(blackOnWhite!!)
                decodedData shouldBe data
            }
        }
    }
    
    "Data size validation should be consistent with generation capability" {
        checkAll<String>(iterations = 50, Arb.string(1..2000)) { data ->
            val isValidSize = qrGenerator.validateDataSize(data)
            
            if (isValidSize) {
                // If validation passes, generation should succeed
                val qrBitmap = qrGenerator.generateQrCode(data)
                qrBitmap shouldNotBe null
                
                // And round-trip should work
                val decodedData = decodeQrCode(qrBitmap!!)
                decodedData shouldBe data
            }
        }
    }
    
    "QR code dimensions should match requested size" {
        checkAll<Int, Int>(
            iterations = 30,
            Arb.int(256..1024),
            Arb.int(256..1024)
        ) { width, height ->
            val testData = """{"ip":"192.168.1.100","port":8080,"sessionToken":"test123"}"""
            
            val qrBitmap = qrGenerator.generateQrCode(testData, width, height)
            qrBitmap shouldNotBe null
            qrBitmap!!.width shouldBe width
            qrBitmap.height shouldBe height
        }
    }
    
    "Generated QR codes should be unique for different data" {
        checkAll<String, String>(
            iterations = 30,
            Arb.string(10..100),
            Arb.string(10..100)
        ) { data1, data2 ->
            if (data1 != data2 && qrGenerator.validateDataSize(data1) && qrGenerator.validateDataSize(data2)) {
                val qr1 = qrGenerator.generateQrCode(data1)
                val qr2 = qrGenerator.generateQrCode(data2)
                
                qr1 shouldNotBe null
                qr2 shouldNotBe null
                
                // QR codes should be different (compare pixel data)
                val pixels1 = IntArray(qr1!!.width * qr1.height)
                val pixels2 = IntArray(qr2!!.width * qr2.height)
                
                qr1.getPixels(pixels1, 0, qr1.width, 0, 0, qr1.width, qr1.height)
                qr2.getPixels(pixels2, 0, qr2.width, 0, 0, qr2.width, qr2.height)
                
                pixels1.contentEquals(pixels2) shouldBe false
            }
        }
    }
    
    /**
     * Creates a valid session info JSON string for testing.
     */
    private fun createSessionInfoJson(ip: String, sessionToken: String, port: Int, publicKey: String): String {
        return JSONObject().apply {
            put("ip", ip)
            put("port", port)
            put("sessionToken", sessionToken)
            put("publicKey", publicKey)
            put("role", "SENDER")
            put("expiryTime", System.currentTimeMillis() + 300000) // 5 minutes from now
        }.toString()
    }
    
    /**
     * Decodes a QR code bitmap back to string data.
     */
    private fun decodeQrCode(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        
        val result = qrReader.decode(binaryBitmap)
        return result.text
    }
})
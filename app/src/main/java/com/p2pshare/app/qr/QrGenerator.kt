package com.p2pshare.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR code generator for P2P file sharing session information.
 * 
 * This class uses ZXing library to generate QR codes containing session
 * information that allows receiver devices to automatically connect to
 * sender devices for file transfer.
 * 
 * The QR codes contain JSON-formatted session information including:
 * - Wi-Fi Direct connection details (IP, port)
 * - Session authentication token
 * - Ephemeral public key for encryption
 * - Transfer role and expiry information
 */
class QrGenerator {
    
    companion object {
        // Default QR code dimensions
        private const val DEFAULT_WIDTH = 512
        private const val DEFAULT_HEIGHT = 512
        
        // QR code colors
        private const val BLACK = Color.BLACK
        private const val WHITE = Color.WHITE
        
        // Error correction level for reliable scanning
        private val ERROR_CORRECTION_LEVEL = ErrorCorrectionLevel.M // ~15% error correction
    }
    
    /**
     * Generates a QR code bitmap from session information JSON.
     * 
     * This method creates a QR code containing the provided session information
     * using optimal settings for mobile device scanning. The QR code uses
     * medium error correction to ensure reliable scanning even in suboptimal
     * lighting conditions.
     * 
     * @param sessionInfo JSON string containing session information
     * @param width Width of the generated QR code bitmap (default: 512px)
     * @param height Height of the generated QR code bitmap (default: 512px)
     * @return Bitmap containing the QR code, or null if generation fails
     * @throws IllegalArgumentException if sessionInfo is empty or dimensions are invalid
     * @throws WriterException if QR code generation fails
     */
    @Throws(IllegalArgumentException::class, WriterException::class)
    fun generateQrCode(
        sessionInfo: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT
    ): Bitmap? {
        require(sessionInfo.isNotBlank()) { 
            "Session info cannot be empty or blank" 
        }
        require(width > 0 && height > 0) { 
            "Width and height must be positive values" 
        }
        require(width <= 2048 && height <= 2048) { 
            "Width and height must not exceed 2048 pixels" 
        }
        
        return try {
            val writer = QRCodeWriter()
            val hints = createEncodingHints()
            
            // Generate the QR code bit matrix
            val bitMatrix = writer.encode(
                sessionInfo,
                BarcodeFormat.QR_CODE,
                width,
                height,
                hints
            )
            
            // Convert bit matrix to bitmap
            createBitmapFromBitMatrix(bitMatrix)
            
        } catch (e: WriterException) {
            throw WriterException("Failed to generate QR code: ${e.message}")
        } catch (e: Exception) {
            throw RuntimeException("Unexpected error during QR code generation: ${e.message}", e)
        }
    }
    
    /**
     * Generates a QR code with custom colors.
     * 
     * This method allows customization of QR code colors while maintaining
     * sufficient contrast for reliable scanning.
     * 
     * @param sessionInfo JSON string containing session information
     * @param width Width of the generated QR code bitmap
     * @param height Height of the generated QR code bitmap
     * @param foregroundColor Color for QR code modules (dark areas)
     * @param backgroundColor Color for QR code background (light areas)
     * @return Bitmap containing the QR code with custom colors
     * @throws IllegalArgumentException if parameters are invalid
     * @throws WriterException if QR code generation fails
     */
    @Throws(IllegalArgumentException::class, WriterException::class)
    fun generateQrCodeWithColors(
        sessionInfo: String,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        foregroundColor: Int = BLACK,
        backgroundColor: Int = WHITE
    ): Bitmap? {
        require(sessionInfo.isNotBlank()) { 
            "Session info cannot be empty or blank" 
        }
        require(width > 0 && height > 0) { 
            "Width and height must be positive values" 
        }
        
        // Validate color contrast for scanning reliability
        validateColorContrast(foregroundColor, backgroundColor)
        
        return try {
            val writer = QRCodeWriter()
            val hints = createEncodingHints()
            
            val bitMatrix = writer.encode(
                sessionInfo,
                BarcodeFormat.QR_CODE,
                width,
                height,
                hints
            )
            
            createBitmapFromBitMatrix(bitMatrix, foregroundColor, backgroundColor)
            
        } catch (e: WriterException) {
            throw WriterException("Failed to generate QR code with custom colors: ${e.message}")
        }
    }
    
    /**
     * Validates the data size for QR code generation.
     * 
     * This method checks if the session information can fit within QR code
     * capacity limits, considering the error correction level.
     * 
     * @param sessionInfo The session information to validate
     * @return true if the data can fit in a QR code, false otherwise
     */
    fun validateDataSize(sessionInfo: String): Boolean {
        if (sessionInfo.isBlank()) return false
        
        // QR code capacity varies by version and error correction level
        // For medium error correction (Level M), approximate limits:
        // - Alphanumeric: ~1,852 characters
        // - Binary: ~1,273 bytes
        // Session info is typically much smaller, but we validate anyway
        
        val dataBytes = sessionInfo.toByteArray(Charsets.UTF_8)
        return dataBytes.size <= 1000 // Conservative limit for reliable encoding
    }
    
    /**
     * Estimates the optimal QR code size for the given data.
     * 
     * This method suggests appropriate dimensions based on the amount of
     * data to be encoded, ensuring good scanning performance.
     * 
     * @param sessionInfo The session information to encode
     * @return Pair of (width, height) for optimal QR code size
     */
    fun getOptimalSize(sessionInfo: String): Pair<Int, Int> {
        val dataLength = sessionInfo.toByteArray(Charsets.UTF_8).size
        
        return when {
            dataLength <= 100 -> Pair(256, 256)   // Small data
            dataLength <= 300 -> Pair(384, 384)   // Medium data
            dataLength <= 600 -> Pair(512, 512)   // Large data
            else -> Pair(768, 768)                 // Very large data
        }
    }
    
    /**
     * Creates encoding hints for QR code generation.
     * 
     * @return Map of encoding hints optimized for P2P file sharing
     */
    private fun createEncodingHints(): Map<EncodeHintType, Any> {
        return mapOf(
            EncodeHintType.ERROR_CORRECTION to ERROR_CORRECTION_LEVEL,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2 // Quiet zone margin
        )
    }
    
    /**
     * Converts a BitMatrix to a Bitmap.
     * 
     * @param bitMatrix The QR code bit matrix
     * @param foregroundColor Color for QR code modules (default: black)
     * @param backgroundColor Color for QR code background (default: white)
     * @return Bitmap representation of the QR code
     */
    private fun createBitmapFromBitMatrix(
        bitMatrix: BitMatrix,
        foregroundColor: Int = BLACK,
        backgroundColor: Int = WHITE
    ): Bitmap {
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix[x, y]) foregroundColor else backgroundColor
            }
        }
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
    
    /**
     * Validates color contrast for QR code scanning reliability.
     * 
     * @param foregroundColor The foreground color
     * @param backgroundColor The background color
     * @throws IllegalArgumentException if contrast is insufficient
     */
    private fun validateColorContrast(foregroundColor: Int, backgroundColor: Int) {
        // Calculate luminance for both colors
        val foregroundLuminance = calculateLuminance(foregroundColor)
        val backgroundLuminance = calculateLuminance(backgroundColor)
        
        // Calculate contrast ratio
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        val contrastRatio = (lighter + 0.05) / (darker + 0.05)
        
        // Require minimum contrast ratio for reliable scanning
        require(contrastRatio >= 3.0) {
            "Insufficient color contrast for reliable QR code scanning. " +
            "Contrast ratio: $contrastRatio, minimum required: 3.0"
        }
    }
    
    /**
     * Calculates the relative luminance of a color.
     * 
     * @param color The color value
     * @return Relative luminance (0.0 to 1.0)
     */
    private fun calculateLuminance(color: Int): Double {
        val red = Color.red(color) / 255.0
        val green = Color.green(color) / 255.0
        val blue = Color.blue(color) / 255.0
        
        // Apply gamma correction
        val r = if (red <= 0.03928) red / 12.92 else Math.pow((red + 0.055) / 1.055, 2.4)
        val g = if (green <= 0.03928) green / 12.92 else Math.pow((green + 0.055) / 1.055, 2.4)
        val b = if (blue <= 0.03928) blue / 12.92 else Math.pow((blue + 0.055) / 1.055, 2.4)
        
        // Calculate luminance using ITU-R BT.709 coefficients
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
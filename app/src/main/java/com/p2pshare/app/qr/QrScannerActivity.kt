package com.p2pshare.app.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.p2pshare.app.R
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Activity for scanning QR codes using CameraX and ZXing.
 * 
 * This activity provides a camera preview interface for scanning QR codes
 * containing P2P file sharing session information. It uses CameraX for
 * camera management and ZXing for QR code detection and decoding.
 * 
 * The activity handles:
 * - Camera permission requests
 * - Camera lifecycle management
 * - Real-time QR code detection
 * - Session info extraction and validation
 * - Result delivery to calling activity
 */
class QrScannerActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "QrScannerActivity"
        const val EXTRA_SCANNED_DATA = "scanned_data"
        const val RESULT_QR_SCANNED = 1001
        const val RESULT_SCAN_CANCELLED = 1002
        const val RESULT_PERMISSION_DENIED = 1003
    }
    
    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    
    private val multiFormatReader = MultiFormatReader().apply {
        setHints(mapOf(
            com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            com.google.zxing.DecodeHintType.TRY_HARDER to true
        ))
    }
    
    private var isScanning = true
    
    /**
     * Camera permission request launcher.
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            handlePermissionDenied()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)
        
        initializeViews()
        setupCamera()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
    
    override fun onResume() {
        super.onResume()
        isScanning = true
    }
    
    override fun onPause() {
        super.onPause()
        isScanning = false
    }
    
    /**
     * Initializes UI components.
     */
    private fun initializeViews() {
        previewView = findViewById(R.id.previewView)
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Set up UI controls if needed (e.g., cancel button, flashlight toggle)
        setupUIControls()
    }
    
    /**
     * Sets up camera functionality.
     */
    private fun setupCamera() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    /**
     * Starts the camera and sets up use cases.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                showError("Failed to initialize camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    /**
     * Binds camera use cases (preview and image analysis).
     */
    private fun bindCameraUseCases() {
        val cameraProvider = this.cameraProvider ?: run {
            Log.e(TAG, "Camera provider is null")
            return
        }
        
        // Preview use case
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        
        // Image analysis use case for QR code detection
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { qrCode ->
                    handleQrCodeDetected(qrCode)
                })
            }
        
        // Image capture use case (optional, for manual capture)
        imageCapture = ImageCapture.Builder().build()
        
        // Select camera (prefer back camera)
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll()
            
            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer,
                imageCapture
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            showError("Failed to start camera: ${e.message}")
        }
    }
    
    /**
     * Handles QR code detection result.
     * 
     * @param qrCodeData The decoded QR code data
     */
    private fun handleQrCodeDetected(qrCodeData: String) {
        if (!isScanning) return
        
        runOnUiThread {
            try {
                // Validate that this is session info (basic JSON validation)
                if (isValidSessionInfo(qrCodeData)) {
                    isScanning = false // Stop further scanning
                    
                    // Return the scanned data to the calling activity
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_SCANNED_DATA, qrCodeData)
                    }
                    setResult(RESULT_QR_SCANNED, resultIntent)
                    finish()
                } else {
                    // Invalid QR code format
                    showError("Invalid QR code format. Please scan a valid P2P file sharing QR code.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing QR code", e)
                showError("Error processing QR code: ${e.message}")
            }
        }
    }
    
    /**
     * Validates that the scanned data appears to be valid session info.
     * 
     * @param data The scanned QR code data
     * @return true if the data appears to be valid session info
     */
    private fun isValidSessionInfo(data: String): Boolean {
        return try {
            // Basic validation - check if it's JSON-like and contains expected fields
            data.trim().startsWith("{") && 
            data.trim().endsWith("}") &&
            data.contains("\"ip\"") &&
            data.contains("\"port\"") &&
            data.contains("\"sessionToken\"")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Sets up UI controls (cancel button, flashlight, etc.).
     */
    private fun setupUIControls() {
        // Cancel button
        findViewById<android.widget.Button>(R.id.cancelButton)?.setOnClickListener {
            setResult(RESULT_SCAN_CANCELLED)
            finish()
        }
        
        // Flashlight toggle (if available)
        findViewById<android.widget.ImageButton>(R.id.flashlightButton)?.setOnClickListener {
            toggleFlashlight()
        }
    }
    
    /**
     * Toggles the camera flashlight.
     */
    private fun toggleFlashlight() {
        camera?.let { camera ->
            val currentTorchState = camera.cameraInfo.torchState.value ?: TorchState.OFF
            val newTorchState = currentTorchState == TorchState.OFF
            
            camera.cameraControl.enableTorch(newTorchState)
        }
    }
    
    /**
     * Checks if all required permissions are granted.
     * 
     * @return true if camera permission is granted
     */
    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Handles camera permission denial.
     */
    private fun handlePermissionDenied() {
        showError("Camera permission is required to scan QR codes")
        setResult(RESULT_PERMISSION_DENIED)
        finish()
    }
    
    /**
     * Shows an error message to the user.
     * 
     * @param message The error message to display
     */
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e(TAG, message)
    }
    
    /**
     * Image analyzer for QR code detection.
     */
    private inner class QrCodeAnalyzer(
        private val onQrCodeDetected: (String) -> Unit
    ) : ImageAnalysis.Analyzer {
        
        override fun analyze(image: ImageProxy) {
            if (!isScanning) {
                image.close()
                return
            }
            
            try {
                val buffer = image.planes[0].buffer
                val data = buffer.toByteArray()
                val pixels = IntArray(image.width * image.height)
                
                // Convert YUV to RGB
                convertYuvToRgb(data, pixels, image.width, image.height)
                
                // Create luminance source
                val source = RGBLuminanceSource(image.width, image.height, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                
                // Try to decode QR code
                val result = multiFormatReader.decode(binaryBitmap)
                onQrCodeDetected(result.text)
                
            } catch (e: Exception) {
                // No QR code found or decoding failed - this is normal
                Log.d(TAG, "QR code detection: ${e.message}")
            } finally {
                image.close()
            }
        }
        
        /**
         * Converts YUV image data to RGB pixels.
         */
        private fun convertYuvToRgb(yuvData: ByteArray, rgbPixels: IntArray, width: Int, height: Int) {
            // Simplified YUV to RGB conversion
            // In a production app, you might want to use a more efficient conversion
            for (i in rgbPixels.indices) {
                val y = yuvData[i].toInt() and 0xFF
                val gray = if (y < 0) 0 else if (y > 255) 255 else y
                rgbPixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
        
        /**
         * Converts ByteBuffer to ByteArray.
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()
            val data = ByteArray(remaining())
            get(data)
            return data
        }
    }
}
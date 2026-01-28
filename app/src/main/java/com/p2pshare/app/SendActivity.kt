package com.p2pshare.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.p2pshare.app.qr.QrGenerator
import com.p2pshare.app.service.TransferServiceHelper
import com.p2pshare.app.connection.ConnectionMethod
import com.p2pshare.app.connection.ConnectionMethodInfo
import com.p2pshare.app.connection.UnifiedConnectionManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.os.Handler
import android.os.Looper

/**
 * Activity for sending files to other devices.
 * 
 * This activity handles:
 * - File selection using document picker
 * - Connection establishment using selected method
 * - QR code generation with session information
 * - Transfer initiation through TransferService
 */
class SendActivity : AppCompatActivity(), UnifiedConnectionManager.UnifiedConnectionCallback {

    companion object {
        private const val TAG = "SendActivity"
    }

    // UI Components
    private lateinit var btnSelectFile: MaterialButton
    private lateinit var btnStartTransfer: MaterialButton
    private lateinit var cvFileInfo: MaterialCardView
    private lateinit var cvQrCode: MaterialCardView
    private lateinit var tvFileName: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var tvFileType: TextView
    private lateinit var tvInstructions: TextView
    private lateinit var ivQrCode: ImageView
    private lateinit var progressBar: ProgressBar

    // Components
    private lateinit var connectionManager: UnifiedConnectionManager
    private lateinit var qrGenerator: QrGenerator

    // State
    private var selectedFile: File? = null
    private var selectedFileUri: Uri? = null
    private var isConnectionReady = false
    private var selectedConnectionMethod: ConnectionMethod = ConnectionMethod.AUTO
    private var connectionTimeout: Runnable? = null

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { handleSelectedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send)
        
        // Get selected connection method from intent
        val methodName = intent.getStringExtra("connection_method") ?: ConnectionMethod.AUTO.name
        selectedConnectionMethod = try {
            ConnectionMethod.valueOf(methodName)
        } catch (e: Exception) {
            ConnectionMethod.AUTO
        }
        
        initializeViews()
        initializeComponents()
        setupClickListeners()
        updateUI()
        
        // Show selected method info
        showSelectedMethodInfo()
    }
    
    /**
     * Shows information about the selected connection method.
     */
    private fun showSelectedMethodInfo() {
        val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedConnectionMethod)
        val message = "Using ${methodInfo.displayName}\n${methodInfo.description}\nSpeed: ${methodInfo.estimatedSpeed}"
        
        tvInstructions.text = message
        
        // Show a toast with method info
        Toast.makeText(this, "Connection Method: ${methodInfo.displayName}", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel any pending timeouts
        connectionTimeout?.let { timeout ->
            Handler(Looper.getMainLooper()).removeCallbacks(timeout)
        }
        connectionManager.cleanup()
    }

    /**
     * Initializes all view references.
     */
    private fun initializeViews() {
        btnSelectFile = findViewById(R.id.btn_select_file)
        btnStartTransfer = findViewById(R.id.btn_start_transfer)
        cvFileInfo = findViewById(R.id.cv_file_info)
        cvQrCode = findViewById(R.id.cv_qr_code)
        tvFileName = findViewById(R.id.tv_file_name)
        tvFileSize = findViewById(R.id.tv_file_size)
        tvFileType = findViewById(R.id.tv_file_type)
        tvInstructions = findViewById(R.id.tv_instructions)
        ivQrCode = findViewById(R.id.iv_qr_code)
        progressBar = findViewById(R.id.progress_bar)
    }

    /**
     * Initializes connection manager and QR generator.
     */
    private fun initializeComponents() {
        connectionManager = UnifiedConnectionManager(this, this)
        qrGenerator = QrGenerator()
    }

    /**
     * Sets up click listeners for buttons.
     */
    private fun setupClickListeners() {
        btnSelectFile.setOnClickListener {
            openFilePicker()
        }

        btnStartTransfer.setOnClickListener {
            startFileTransfer()
        }
        
        // Long press for troubleshooting
        btnStartTransfer.setOnLongClickListener {
            showTroubleshootingDialog()
            true
        }

        // Back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    /**
     * Opens the file picker to select a file to send.
     */
    private fun openFilePicker() {
        try {
            filePickerLauncher.launch("*/*")
        } catch (e: Exception) {
            showError("Failed to open file picker: ${e.message}")
        }
    }

    /**
     * Handles the selected file from the picker.
     */
    private fun handleSelectedFile(uri: Uri) {
        try {
            selectedFileUri = uri
            
            // Get file information
            val fileInfo = getFileInfo(uri)
            val fileName = fileInfo.first
            val fileSize = fileInfo.second

            // Copy file to internal storage for transfer
            val internalFile = copyFileToInternal(uri, fileName)
            selectedFile = internalFile

            // Update UI with file information
            tvFileName.text = fileName
            tvFileSize.text = TransferServiceHelper.formatFileSize(fileSize)
            tvFileType.text = getFileType(fileName)

            cvFileInfo.visibility = View.VISIBLE
            updateUI()

        } catch (e: Exception) {
            showError("Failed to process selected file: ${e.message}")
        }
    }

    /**
     * Gets file information from URI.
     */
    private fun getFileInfo(uri: Uri): Pair<String, Long> {
        var fileName = "Unknown"
        var fileSize = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex) ?: "Unknown"
                }
                if (sizeIndex != -1) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }
        }

        return Pair(fileName, fileSize)
    }

    /**
     * Copies the selected file to internal storage.
     */
    private fun copyFileToInternal(uri: Uri, fileName: String): File {
        val internalFile = File(filesDir, "temp_$fileName")
        
        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(internalFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        
        return internalFile
    }

    /**
     * Gets file type from file name.
     */
    private fun getFileType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "pdf" -> "PDF Document"
            "doc", "docx" -> "Word Document"
            "txt" -> "Text File"
            "jpg", "jpeg", "png", "gif" -> "Image"
            "mp4", "avi", "mov" -> "Video"
            "mp3", "wav", "flac" -> "Audio"
            "zip", "rar", "7z" -> "Archive"
            else -> "File"
        }
    }

    /**
     * Starts the file transfer process.
     */
    private fun startFileTransfer() {
        val file = selectedFile
        if (file == null || !file.exists()) {
            showError("Please select a file first")
            return
        }

        showProgress(true)
        val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedConnectionMethod)
        tvInstructions.text = "Preparing ${methodInfo.displayName}..."

        // Initialize and start connection
        Handler(Looper.getMainLooper()).postDelayed({
            initializeAndStartConnection()
        }, 500)
    }
    
    /**
     * Initializes connection manager and starts connection.
     */
    private fun initializeAndStartConnection() {
        val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedConnectionMethod)
        tvInstructions.text = "Initializing ${methodInfo.displayName}..."
        
        try {
            // Initialize connection manager
            if (!connectionManager.initialize()) {
                showError("Failed to initialize connection manager for ${methodInfo.displayName}")
                showProgress(false)
                return
            }

            tvInstructions.text = "Starting ${methodInfo.displayName} connection..."

            // Start connection with selected method
            val success = connectionManager.startConnection(selectedConnectionMethod)
            if (!success) {
                showError("Failed to start ${methodInfo.displayName} connection")
                showProgress(false)
            } else {
                // Set a timeout for connection
                connectionTimeout = Runnable {
                    if (!isConnectionReady) {
                        showError("${methodInfo.displayName} connection timed out. Long-press 'Start Transfer' for troubleshooting.")
                        showProgress(false)
                        tvInstructions.text = "Connection failed. Long-press button for help."
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed(connectionTimeout!!, 30000) // 30 second timeout
            }
        } catch (e: Exception) {
            showError("Error initializing ${methodInfo.displayName}: ${e.message}")
            showProgress(false)
            tvInstructions.text = "Initialization failed. Long-press button for help."
        }
    }

    /**
     * Generates and displays QR code with connection information.
     */
    private fun generateQrCode(connectionInfo: UnifiedConnectionManager.ConnectionInfo) {
        val file = selectedFile ?: return

        lifecycleScope.launch {
            try {
                // Start transfer service to get server port
                TransferServiceHelper.startSendTransfer(this@SendActivity, file)
                
                // Wait a moment for service to start
                kotlinx.coroutines.delay(1000)
                
                // Use the connection info from the unified manager
                val qrBitmap = qrGenerator.generateQrCode(connectionInfo.qrCodeData, 512, 512)
                
                // Display QR code
                runOnUiThread {
                    ivQrCode.setImageBitmap(qrBitmap)
                    cvQrCode.visibility = View.VISIBLE
                    showProgress(false)
                    
                    val methodInfo = ConnectionMethodInfo.getMethodInfo(connectionInfo.method)
                    tvInstructions.text = "Show this QR code to the receiver\nUsing: ${methodInfo.displayName}"
                    btnStartTransfer.text = "Transfer Ready"
                    btnStartTransfer.isEnabled = false
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showError("Failed to generate QR code: ${e.message}")
                    showProgress(false)
                }
            }
        }
    }

    /**
     * Updates UI based on current state.
     */
    private fun updateUI() {
        val hasFile = selectedFile != null
        val canStartTransfer = hasFile && !isConnectionReady

        btnStartTransfer.isEnabled = canStartTransfer
        btnStartTransfer.text = when {
            !hasFile -> "Select a file first"
            isConnectionReady -> "Connection Ready"
            else -> "Start Transfer"
        }
    }

    /**
     * Shows or hides progress indicator.
     */
    private fun showProgress(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Shows error message to user.
     */
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    /**
     * Shows troubleshooting dialog with common solutions.
     */
    private fun showTroubleshootingDialog() {
        val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedConnectionMethod)
        val compatibilityReport = connectionManager.getCompatibilityReport()
        
        val message = buildString {
            append("Connection Troubleshooting:\n\n")
            append("Current Method: ${methodInfo.displayName}\n")
            append("Your Device: OnePlus Nord CE 2 Lite 5G\n\n")
            
            when (selectedConnectionMethod) {
                ConnectionMethod.WIFI_DIRECT -> {
                    append("🔧 WI-FI DIRECT ISSUES (OnePlus):\n")
                    append("Your OnePlus device has known Wi-Fi Direct issues.\n\n")
                    append("RECOMMENDED SOLUTIONS:\n")
                    append("1. Use Hotspot Mode instead (button below)\n")
                    append("2. Use Bluetooth (most reliable)\n")
                    append("3. Try restarting Wi-Fi completely\n\n")
                }
                ConnectionMethod.HOTSPOT -> {
                    append("🔧 HOTSPOT TROUBLESHOOTING:\n")
                    append("1. Check if hotspot is enabled in Settings\n")
                    append("2. Ensure no other apps are using hotspot\n")
                    append("3. Try turning hotspot OFF and ON\n\n")
                }
                ConnectionMethod.BLUETOOTH -> {
                    append("🔧 BLUETOOTH TROUBLESHOOTING:\n")
                    append("1. Enable Bluetooth in Settings\n")
                    append("2. Make device discoverable\n")
                    append("3. Clear Bluetooth cache if needed\n\n")
                }
                ConnectionMethod.AUTO -> {
                    append("🔧 AUTO-SELECT TROUBLESHOOTING:\n")
                    append("The app will try the best method for your device.\n\n")
                }
            }
            
            append("📱 GENERAL FIXES:\n")
            append("• Enable ALL app permissions\n")
            append("• Turn Location ON (required)\n")
            append("• Restart the app\n")
            append("• Restart your device\n\n")
            
            append("💡 TIP: Hotspot Mode works best on OnePlus devices!")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Connection Troubleshooting")
            .setMessage(message)
            .setPositiveButton("Run Diagnostics") { _, _ ->
                runConnectionDiagnostics()
            }
            .setNeutralButton("Try Hotspot") { _, _ ->
                tryHotspotMethod()
            }
            .setNegativeButton("Try Bluetooth") { _, _ ->
                tryBluetoothMethod()
            }
            .show()
    }
    
    /**
     * Tries Hotspot method as alternative.
     */
    private fun tryHotspotMethod() {
        val file = selectedFile
        if (file == null || !file.exists()) {
            showError("Please select a file first")
            return
        }

        showProgress(true)
        tvInstructions.text = "Switching to Hotspot Mode..."
        
        // Cleanup current connection
        connectionManager.disconnect()
        
        // Switch to hotspot method
        selectedConnectionMethod = ConnectionMethod.HOTSPOT
        
        Handler(Looper.getMainLooper()).postDelayed({
            initializeAndStartConnection()
        }, 2000)
    }
    
    /**
     * Tries Bluetooth method as alternative.
     */
    private fun tryBluetoothMethod() {
        val file = selectedFile
        if (file == null || !file.exists()) {
            showError("Please select a file first")
            return
        }

        showProgress(true)
        tvInstructions.text = "Switching to Bluetooth..."
        
        // Cleanup current connection
        connectionManager.disconnect()
        
        // Switch to bluetooth method
        selectedConnectionMethod = ConnectionMethod.BLUETOOTH
        
        Handler(Looper.getMainLooper()).postDelayed({
            initializeAndStartConnection()
        }, 2000)
    }
    
    /**
     * Runs comprehensive connection diagnostics.
     */
    private fun runConnectionDiagnostics() {
        showProgress(true)
        tvInstructions.text = "Running connection diagnostics..."
        
        Thread {
            val diagnostics = StringBuilder()
            
            try {
                // Test device compatibility
                val compatibilityChecker = com.p2pshare.app.connection.DeviceCompatibilityChecker(this)
                val report = compatibilityChecker.checkCompatibility()
                
                diagnostics.append("=== DEVICE DIAGNOSTICS ===\n")
                diagnostics.append("Device: ${report.deviceInfo.manufacturer} ${report.deviceInfo.model}\n")
                diagnostics.append("Android: ${report.deviceInfo.androidVersion} (API ${report.deviceInfo.apiLevel})\n")
                diagnostics.append("Recommended Method: ${com.p2pshare.app.connection.ConnectionMethodInfo.getMethodInfo(report.recommendedMethod).displayName}\n\n")
                
                diagnostics.append("=== CONNECTION METHODS ===\n")
                report.results.forEach { (method, result) ->
                    val methodInfo = com.p2pshare.app.connection.ConnectionMethodInfo.getMethodInfo(method)
                    diagnostics.append("${methodInfo.displayName}:\n")
                    diagnostics.append("  Supported: ${if (result.isSupported) "✓" else "✗"}\n")
                    diagnostics.append("  Recommended: ${if (result.isRecommended) "✓" else "✗"}\n")
                    if (result.issues.isNotEmpty()) {
                        diagnostics.append("  Issues: ${result.issues.joinToString(", ")}\n")
                    }
                    if (result.requirements.isNotEmpty()) {
                        diagnostics.append("  Requirements: ${result.requirements.joinToString(", ")}\n")
                    }
                    diagnostics.append("\n")
                }
                
                // Test permissions
                diagnostics.append("=== PERMISSIONS ===\n")
                val requiredPermissions = getRequiredPermissions()
                requiredPermissions.forEach { permission ->
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val permName = permission.substringAfterLast('.')
                    diagnostics.append("$permName: ${if (granted) "✓" else "✗"}\n")
                }
                
            } catch (e: Exception) {
                diagnostics.append("Diagnostic error: ${e.message}\n")
            }
            
            runOnUiThread {
                showProgress(false)
                tvInstructions.text = "Diagnostics complete. Check results."
                
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Connection Diagnostics")
                    .setMessage(diagnostics.toString())
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy to Clipboard") { _, _ ->
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Diagnostics", diagnostics.toString())
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }.start()
    }
    
    /**
     * Gets required permissions for diagnostics.
     */
    private fun getRequiredPermissions(): Array<String> {
        val basePermissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE
        )
        
        // Add Bluetooth permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            basePermissions.addAll(arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            basePermissions.addAll(arrayOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN
            ))
        }
        
        return basePermissions.toTypedArray()
    }

    // UnifiedConnectionManager.UnifiedConnectionCallback implementation
    override fun onConnectionMethodSelected(method: ConnectionMethod, info: ConnectionMethodInfo) {
        runOnUiThread {
            tvInstructions.text = "Using ${info.displayName} - ${info.description}"
        }
    }

    override fun onConnectionReady(connectionInfo: UnifiedConnectionManager.ConnectionInfo) {
        runOnUiThread {
            // Cancel timeout since connection was established successfully
            connectionTimeout?.let { timeout ->
                Handler(Looper.getMainLooper()).removeCallbacks(timeout)
                connectionTimeout = null
            }
            
            isConnectionReady = true
            val methodInfo = ConnectionMethodInfo.getMethodInfo(connectionInfo.method)
            tvInstructions.text = "${methodInfo.displayName} ready. Generating QR code..."
            generateQrCode(connectionInfo)
        }
    }

    override fun onConnectionEstablished(peerInfo: UnifiedConnectionManager.PeerInfo) {
        runOnUiThread {
            val methodInfo = ConnectionMethodInfo.getMethodInfo(peerInfo.method)
            tvInstructions.text = "Connected to ${peerInfo.deviceName} via ${methodInfo.displayName}!\nTransfer will begin automatically."
        }
    }

    override fun onConnectionFailed(method: ConnectionMethod, error: String, fallbackAvailable: Boolean) {
        runOnUiThread {
            // Cancel timeout if there was an error
            connectionTimeout?.let { timeout ->
                Handler(Looper.getMainLooper()).removeCallbacks(timeout)
                connectionTimeout = null
            }
            
            val methodInfo = ConnectionMethodInfo.getMethodInfo(method)
            
            if (fallbackAvailable) {
                tvInstructions.text = "${methodInfo.displayName} failed. Trying fallback method..."
                // Don't show error or stop progress - fallback will be attempted
            } else {
                // Provide specific guidance based on error type and method
                val userMessage = when {
                    error.contains("busy", ignoreCase = true) && method == ConnectionMethod.WIFI_DIRECT -> {
                        "Wi-Fi Direct is busy on your OnePlus device.\nThis is a known issue. Try:\n1. Restart Wi-Fi\n2. Use Hotspot or Bluetooth instead"
                    }
                    error.contains("not supported", ignoreCase = true) -> {
                        "${methodInfo.displayName} is not supported on this device"
                    }
                    error.contains("permissions", ignoreCase = true) -> {
                        "Please enable all permissions in Settings"
                    }
                    else -> "${methodInfo.displayName} error: $error"
                }
                
                showError(userMessage)
                showProgress(false)
                isConnectionReady = false
                
                tvInstructions.text = "Connection failed. Long-press button for help."
                updateUI()
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            tvInstructions.text = "Device disconnected. Show QR code to reconnect."
            isConnectionReady = false
            updateUI()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            // Cancel timeout if there was an error
            connectionTimeout?.let { timeout ->
                Handler(Looper.getMainLooper()).removeCallbacks(timeout)
                connectionTimeout = null
            }
            
            showError("Connection error: $error")
            showProgress(false)
            isConnectionReady = false
            tvInstructions.text = "Error occurred. Please try again."
            updateUI()
        }
    }
}
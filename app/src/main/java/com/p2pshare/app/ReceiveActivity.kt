package com.p2pshare.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.p2pshare.app.qr.QrScannerActivity
import com.p2pshare.app.service.TransferServiceHelper
import com.p2pshare.app.connection.ConnectionMethod
import com.p2pshare.app.connection.ConnectionMethodInfo
import com.p2pshare.app.connection.UnifiedConnectionManager
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Activity for receiving files from other devices.
 * 
 * This activity handles:
 * - QR code scanning to get session information
 * - Connection to sender using selected method
 * - Transfer initiation through TransferService
 * - Navigation to TransferActivity for progress monitoring
 */
class ReceiveActivity : AppCompatActivity(), UnifiedConnectionManager.UnifiedConnectionCallback {

    companion object {
        private const val TAG = "ReceiveActivity"
        private const val QR_SCAN_REQUEST_CODE = 1001
    }

    // UI Components
    private lateinit var btnScanQr: MaterialButton
    private lateinit var btnStartReceive: MaterialButton
    private lateinit var cvSessionInfo: MaterialCardView
    private lateinit var tvInstructions: TextView
    private lateinit var tvFileName: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var tvSenderInfo: TextView
    private lateinit var progressBar: ProgressBar

    // Components
    private lateinit var connectionManager: UnifiedConnectionManager

    // State
    private var sessionInfo: JSONObject? = null
    private var isConnected = false
    private var selectedConnectionMethod: ConnectionMethod = ConnectionMethod.AUTO

    // QR Scanner launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedData = result.data?.getStringExtra("scanned_data")
            scannedData?.let { handleScannedQr(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)
        
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

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.cleanup()
    }
    
    /**
     * Shows information about the selected connection method.
     */
    private fun showSelectedMethodInfo() {
        val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedConnectionMethod)
        tvInstructions.text = "Ready to receive using ${methodInfo.displayName}\nScan QR code from sender to begin"
        
        Toast.makeText(this, "Receive Method: ${methodInfo.displayName}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Initializes all view references.
     */
    private fun initializeViews() {
        btnScanQr = findViewById(R.id.btn_scan_qr)
        btnStartReceive = findViewById(R.id.btn_start_receive)
        cvSessionInfo = findViewById(R.id.cv_session_info)
        tvInstructions = findViewById(R.id.tv_instructions)
        tvFileName = findViewById(R.id.tv_file_name)
        tvFileSize = findViewById(R.id.tv_file_size)
        tvSenderInfo = findViewById(R.id.tv_sender_info)
        progressBar = findViewById(R.id.progress_bar)
    }

    /**
     * Initializes connection manager.
     */
    private fun initializeComponents() {
        connectionManager = UnifiedConnectionManager(this, this)
    }

    /**
     * Sets up click listeners for buttons.
     */
    private fun setupClickListeners() {
        btnScanQr.setOnClickListener {
            startQrScanner()
        }

        btnStartReceive.setOnClickListener {
            startFileReceive()
        }

        // Back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    /**
     * Starts the QR scanner activity.
     */
    private fun startQrScanner() {
        try {
            val intent = Intent(this, QrScannerActivity::class.java)
            qrScannerLauncher.launch(intent)
        } catch (e: Exception) {
            showError("Failed to start QR scanner: ${e.message}")
        }
    }

    /**
     * Handles scanned QR code data.
     */
    private fun handleScannedQr(scannedData: String) {
        try {
            // Parse QR code data (could be old format or new unified format)
            val json = JSONObject(scannedData)
            
            // Check if it's the new unified format
            if (json.has("method")) {
                // New format with connection method info
                val connectionMethod = ConnectionMethod.valueOf(json.getString("method"))
                selectedConnectionMethod = connectionMethod
                
                // Extract connection info and create session info
                sessionInfo = createSessionInfoFromConnectionData(json)
            } else {
                // Legacy format - assume Wi-Fi Direct
                if (!json.has("type") || json.getString("type") != "file_transfer") {
                    showError("Invalid QR code format")
                    return
                }
                
                // Validate required fields for legacy format
                val requiredFields = arrayOf("fileName", "fileSize", "senderIp", "senderPort", "transferId")
                for (field in requiredFields) {
                    if (!json.has(field)) {
                        showError("Invalid QR code: missing $field")
                        return
                    }
                }
                
                sessionInfo = json
                selectedConnectionMethod = ConnectionMethod.WIFI_DIRECT
            }
            
            displaySessionInfo()
            updateUI()

        } catch (e: Exception) {
            showError("Failed to parse QR code: ${e.message}")
        }
    }
    
    /**
     * Creates session info from new unified connection data format.
     */
    private fun createSessionInfoFromConnectionData(connectionData: JSONObject): JSONObject {
        val sessionInfo = JSONObject()
        
        // Extract file info if available
        if (connectionData.has("fileName")) {
            sessionInfo.put("fileName", connectionData.getString("fileName"))
        } else {
            sessionInfo.put("fileName", "Unknown File")
        }
        
        if (connectionData.has("fileSize")) {
            sessionInfo.put("fileSize", connectionData.getLong("fileSize"))
        } else {
            sessionInfo.put("fileSize", 0)
        }
        
        // Extract connection info based on method
        val method = ConnectionMethod.valueOf(connectionData.getString("method"))
        when (method) {
            ConnectionMethod.WIFI_DIRECT -> {
                sessionInfo.put("senderIp", "192.168.49.1") // Default Wi-Fi Direct IP
                sessionInfo.put("senderPort", 8080)
            }
            ConnectionMethod.HOTSPOT -> {
                sessionInfo.put("senderIp", connectionData.optString("ipAddress", "192.168.43.1"))
                sessionInfo.put("senderPort", 8080)
            }
            ConnectionMethod.BLUETOOTH -> {
                sessionInfo.put("senderIp", "bluetooth")
                sessionInfo.put("senderPort", 0)
            }
            ConnectionMethod.AUTO -> {
                sessionInfo.put("senderIp", "auto")
                sessionInfo.put("senderPort", 8080)
            }
        }
        
        sessionInfo.put("transferId", java.util.UUID.randomUUID().toString())
        sessionInfo.put("connectionData", connectionData.toString())
        
        return sessionInfo
    }

    /**
     * Displays session information from QR code.
     */
    private fun displaySessionInfo() {
        try {
            val session = sessionInfo ?: return
            
            val fileName = session.optString("fileName", "Unknown File")
            val fileSize = session.optLong("fileSize", 0)
            val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedConnectionMethod)

            tvFileName.text = fileName
            tvFileSize.text = TransferServiceHelper.formatFileSize(fileSize)
            tvSenderInfo.text = "Method: ${methodInfo.displayName}"

            cvSessionInfo.visibility = View.VISIBLE
            tvInstructions.text = "File information received. Tap 'Start Receive' to connect using ${methodInfo.displayName}."

        } catch (e: Exception) {
            showError("Failed to display session info: ${e.message}")
        }
    }

    /**
     * Starts the file receive process.
     */
    private fun startFileReceive() {
        val session = sessionInfo
        if (session == null) {
            showError("Please scan a QR code first")
            return
        }

        try {
            showProgress(true)
            val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedConnectionMethod)
            tvInstructions.text = "Connecting using ${methodInfo.displayName}..."

            // Initialize connection manager
            if (!connectionManager.initialize()) {
                showError("Failed to initialize connection manager")
                showProgress(false)
                return
            }

            // Connect to peer using QR code data
            val connectionData = session.optString("connectionData", "{}")
            if (connectionData != "{}") {
                // Use new unified connection method
                connectionManager.connectToPeer(connectionData)
            } else {
                // Legacy connection - start as client
                startLegacyConnection()
            }

        } catch (e: Exception) {
            showError("Failed to start connection: ${e.message}")
            showProgress(false)
        }
    }
    
    /**
     * Handles legacy Wi-Fi Direct connection.
     */
    private fun startLegacyConnection() {
        // For legacy connections, we need to discover and connect to the sender's group
        // This is a simplified implementation
        tvInstructions.text = "Searching for sender..."
        
        // Simulate connection for legacy format
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startTransfer()
        }, 3000)
    }

    /**
     * Starts the actual file transfer after Wi-Fi Direct connection.
     */
    private fun startTransfer() {
        val session = sessionInfo ?: return

        lifecycleScope.launch {
            try {
                val senderIp = session.getString("senderIp")
                val senderPort = session.getInt("senderPort")
                val transferId = session.getString("transferId")
                val outputDirectory = TransferServiceHelper.getDefaultDownloadDirectory(this@ReceiveActivity)

                // Start transfer service
                TransferServiceHelper.startReceiveTransfer(
                    this@ReceiveActivity,
                    senderIp,
                    senderPort,
                    outputDirectory,
                    transferId
                )

                // Navigate to transfer activity
                val intent = Intent(this@ReceiveActivity, TransferActivity::class.java).apply {
                    putExtra("transfer_type", "receive")
                    putExtra("file_name", session.getString("fileName"))
                    putExtra("file_size", session.getLong("fileSize"))
                }
                startActivity(intent)
                finish()

            } catch (e: Exception) {
                runOnUiThread {
                    showError("Failed to start transfer: ${e.message}")
                    showProgress(false)
                }
            }
        }
    }

    /**
     * Updates UI based on current state.
     */
    private fun updateUI() {
        val hasSessionInfo = sessionInfo != null
        val canStartReceive = hasSessionInfo && !isConnected

        btnStartReceive.isEnabled = canStartReceive
        btnStartReceive.text = when {
            !hasSessionInfo -> "Scan QR code first"
            isConnected -> "Connecting..."
            else -> "Start Receive"
        }

        btnScanQr.text = if (hasSessionInfo) "Scan Another QR Code" else "Scan QR Code"
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

    // UnifiedConnectionManager.UnifiedConnectionCallback implementation
    override fun onConnectionMethodSelected(method: ConnectionMethod, info: ConnectionMethodInfo) {
        runOnUiThread {
            tvInstructions.text = "Using ${info.displayName} to connect to sender..."
        }
    }

    override fun onConnectionReady(connectionInfo: UnifiedConnectionManager.ConnectionInfo) {
        runOnUiThread {
            tvInstructions.text = "Connection ready. Waiting for sender..."
        }
    }

    override fun onConnectionEstablished(peerInfo: UnifiedConnectionManager.PeerInfo) {
        runOnUiThread {
            isConnected = true
            val methodInfo = ConnectionMethodInfo.getMethodInfo(peerInfo.method)
            tvInstructions.text = "Connected to ${peerInfo.deviceName} via ${methodInfo.displayName}! Starting transfer..."
            showProgress(false)
            
            // Start the actual transfer
            startTransfer()
        }
    }

    override fun onConnectionFailed(method: ConnectionMethod, error: String, fallbackAvailable: Boolean) {
        runOnUiThread {
            val methodInfo = ConnectionMethodInfo.getMethodInfo(method)
            
            if (fallbackAvailable) {
                tvInstructions.text = "${methodInfo.displayName} failed. Trying alternative method..."
            } else {
                showError("Connection failed: $error")
                showProgress(false)
                isConnected = false
                tvInstructions.text = "Connection failed. Please try scanning the QR code again."
                updateUI()
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            isConnected = false
            tvInstructions.text = "Disconnected from sender. Please try again."
            showProgress(false)
            updateUI()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            showError("Connection error: $error")
            showProgress(false)
            isConnected = false
            updateUI()
        }
    }
}
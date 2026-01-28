package com.p2pshare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.p2pshare.app.database.AppDatabase
import com.p2pshare.app.connection.ConnectionMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main entry point for the P2P File Share application.
 * 
 * Provides options to send or receive files and access transfer history.
 * Handles runtime permissions and Wi-Fi Direct availability checks.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // UI Components
    private lateinit var btnSendFile: MaterialButton
    private lateinit var btnReceiveFile: MaterialButton
    private lateinit var btnHistory: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnGrantPermissions: MaterialButton
    private lateinit var cvPermissionInfo: MaterialCardView
    private lateinit var cvStatus: MaterialCardView

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResults(permissions)
    }

    // Connection method selection launcher
    private val connectionMethodLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedMethodName = result.data?.getStringExtra(ConnectionMethodActivity.EXTRA_SELECTED_METHOD)
            val selectedMethod = selectedMethodName?.let { ConnectionMethod.valueOf(it) } ?: ConnectionMethod.AUTO
            val action = result.data?.getStringExtra("action") ?: "send"
            
            // Launch appropriate activity based on selected method
            if (action == "send") {
                startSendActivityWithMethod(selectedMethod)
            } else {
                startReceiveActivityWithMethod(selectedMethod)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        initializeViews()
        setupClickListeners()
        checkPermissionsAndWifiDirect()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    /**
     * Initializes all view references.
     */
    private fun initializeViews() {
        btnSendFile = findViewById(R.id.btn_send_file)
        btnReceiveFile = findViewById(R.id.btn_receive_file)
        btnHistory = findViewById(R.id.btn_history)
        btnSettings = findViewById(R.id.btn_settings)
        btnGrantPermissions = findViewById(R.id.btn_grant_permissions)
        cvPermissionInfo = findViewById(R.id.cv_permission_info)
        cvStatus = findViewById(R.id.cv_status)
    }

    /**
     * Sets up click listeners for all buttons.
     */
    private fun setupClickListeners() {
        btnSendFile.setOnClickListener {
            if (checkAllPermissions()) {
                startConnectionMethodSelection("send")
            } else {
                requestPermissions()
            }
        }

        btnReceiveFile.setOnClickListener {
            if (checkAllPermissions()) {
                startConnectionMethodSelection("receive")
            } else {
                requestPermissions()
            }
        }

        btnHistory.setOnClickListener {
            startHistoryActivity()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnGrantPermissions.setOnClickListener {
            requestPermissions()
        }
    }

    /**
     * Checks permissions and connection method availability.
     */
    private fun checkPermissionsAndWifiDirect() {
        // Note: We no longer require Wi-Fi Direct since we have multiple connection methods
        // The app will work on ALL devices using Wi-Fi Direct, Hotspot, or Bluetooth
        updateUI()
    }

    /**
     * Updates the UI based on current permissions and connectivity.
     */
    private fun updateUI() {
        val hasAllPermissions = checkAllPermissions()
        val isWifiEnabled = isWifiEnabled()

        // Update main buttons - now works even without Wi-Fi (Bluetooth fallback)
        btnSendFile.isEnabled = hasAllPermissions
        btnReceiveFile.isEnabled = hasAllPermissions

        // Update permission info card
        cvPermissionInfo.visibility = if (hasAllPermissions) View.GONE else View.VISIBLE

        // Update status card
        cvStatus.visibility = View.VISIBLE
        updateStatusMessage(hasAllPermissions, isWifiEnabled)
    }

    /**
     * Updates the status message based on current state.
     */
    private fun updateStatusMessage(hasPermissions: Boolean, wifiEnabled: Boolean) {
        val statusMessage = when {
            !hasPermissions -> "Grant permissions to start sharing files"
            !wifiEnabled -> "Enable Wi-Fi for best performance (Bluetooth also available)"
            else -> "Ready to share files on ANY Android device!"
        }
        
        findViewById<android.widget.TextView>(R.id.tv_status_message).text = statusMessage
    }

    /**
     * Checks if all required permissions are granted.
     */
    private fun checkAllPermissions(): Boolean {
        val requiredPermissions = getRequiredPermissions()
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Gets the list of required permissions based on Android version.
     */
    private fun getRequiredPermissions(): Array<String> {
        val basePermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        
        // Add Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ Bluetooth permissions
            basePermissions.addAll(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            // Legacy Bluetooth permissions
            basePermissions.addAll(arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }
        
        // Add storage permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            basePermissions.addAll(arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            ))
        } else {
            basePermissions.addAll(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
        
        return basePermissions.toTypedArray()
    }

    /**
     * Requests all required permissions.
     */
    private fun requestPermissions() {
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Handles permission request results.
     */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filterValues { !it }.keys

        if (deniedPermissions.isEmpty()) {
            // All permissions granted
            updateUI()
            Snackbar.make(
                findViewById(R.id.main),
                "Permissions granted! You can now share files.",
                Snackbar.LENGTH_SHORT
            ).show()
        } else {
            // Some permissions denied
            val criticalPermissions = deniedPermissions.filter { permission ->
                permission in arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CAMERA
                )
            }

            if (criticalPermissions.isNotEmpty()) {
                showPermissionExplanationDialog(criticalPermissions)
            } else {
                updateUI()
            }
        }
    }

    /**
     * Shows explanation dialog for denied permissions.
     */
    private fun showPermissionExplanationDialog(deniedPermissions: List<String>) {
        val message = buildString {
            append("The following permissions are required for file sharing:\n\n")
            deniedPermissions.forEach { permission ->
                when (permission) {
                    Manifest.permission.ACCESS_FINE_LOCATION -> 
                        append("• Location: Required for Wi-Fi Direct functionality\n")
                    Manifest.permission.CAMERA -> 
                        append("• Camera: Required to scan QR codes\n")
                }
            }
            append("\nPlease grant these permissions in Settings.")
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Opens app settings for manual permission granting.
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    /**
     * Checks if Wi-Fi is enabled.
     */
    private fun isWifiEnabled(): Boolean {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }

    /**
     * Starts connection method selection.
     */
    private fun startConnectionMethodSelection(action: String) {
        val intent = Intent(this, ConnectionMethodActivity::class.java).apply {
            putExtra("action", action)
        }
        connectionMethodLauncher.launch(intent)
    }

    /**
     * Starts the SendActivity with selected connection method.
     */
    private fun startSendActivityWithMethod(method: ConnectionMethod) {
        val intent = Intent(this, SendActivity::class.java).apply {
            putExtra("connection_method", method.name)
        }
        startActivity(intent)
    }

    /**
     * Starts the ReceiveActivity with selected connection method.
     */
    private fun startReceiveActivityWithMethod(method: ConnectionMethod) {
        val intent = Intent(this, ReceiveActivity::class.java).apply {
            putExtra("connection_method", method.name)
        }
        startActivity(intent)
    }

    /**
     * Starts the HistoryActivity.
     */
    private fun startHistoryActivity() {
        val intent = Intent(this, HistoryActivity::class.java)
        startActivity(intent)
    }

    /**
     * Shows settings dialog with app information and options.
     */
    private fun showSettingsDialog() {
        val options = arrayOf(
            "About",
            "Clear Transfer History",
            "App Settings"
        )

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAboutDialog()
                    1 -> showClearHistoryDialog()
                    2 -> openAppSettings()
                }
            }
            .show()
    }

    /**
     * Shows about dialog with app information.
     */
    private fun showAboutDialog() {
        val message = """
            P2P File Share
            
            Universal file sharing app that works on ALL Android devices.
            
            Connection Methods:
            • Wi-Fi Direct (fastest)
            • Wi-Fi Hotspot (reliable alternative)
            • Bluetooth (universal fallback)
            
            Features:
            • Works on ANY Android device
            • Automatic method selection
            • Secure file transfer with encryption
            • No internet required
            • QR code pairing
            • Transfer history
            
            Version: 1.0.0
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Shows confirmation dialog for clearing transfer history.
     */
    private fun showClearHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Transfer History")
            .setMessage("Are you sure you want to clear all transfer history? This action cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val database = AppDatabase.getDatabase(this@MainActivity)
                        database.transferDao().deleteAllTransfers()
                        
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Transfer history cleared", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Failed to clear history: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows error message to user.
     */
    private fun showError(message: String) {
        Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_LONG).show()
    }
}
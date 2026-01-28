package com.p2pshare.app.connection

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import com.p2pshare.app.wifi.WifiDirectCallback
import com.p2pshare.app.wifi.WifiDirectManager
import org.json.JSONObject
import java.io.File

/**
 * Unified connection manager that coordinates Wi-Fi Direct, Hotspot, and Bluetooth methods.
 * 
 * This class provides a single interface for all connection methods and automatically
 * falls back to alternative methods when the primary method fails.
 */
class UnifiedConnectionManager(
    private val context: Context,
    private val callback: UnifiedConnectionCallback
) {
    
    companion object {
        private const val TAG = "UnifiedConnectionManager"
    }
    
    private val compatibilityChecker = DeviceCompatibilityChecker(context)
    private val handler = Handler(Looper.getMainLooper())
    
    // Connection managers
    private var wifiDirectManager: WifiDirectManager? = null
    private var hotspotManager: HotspotManager? = null
    private var bluetoothManager: BluetoothManager? = null
    
    // Current state
    private var currentMethod: ConnectionMethod = ConnectionMethod.AUTO
    private var isInitialized = false
    private var compatibilityReport: DeviceCompatibilityChecker.CompatibilityReport? = null
    
    /**
     * Unified callback interface for all connection methods.
     */
    interface UnifiedConnectionCallback {
        fun onConnectionMethodSelected(method: ConnectionMethod, info: ConnectionMethodInfo)
        fun onConnectionReady(connectionInfo: ConnectionInfo)
        fun onConnectionEstablished(peerInfo: PeerInfo)
        fun onConnectionFailed(method: ConnectionMethod, error: String, fallbackAvailable: Boolean)
        fun onDisconnected()
        fun onError(error: String)
    }
    
    /**
     * Connection information for QR code generation.
     */
    data class ConnectionInfo(
        val method: ConnectionMethod,
        val connectionData: Map<String, String>,
        val qrCodeData: String
    )
    
    /**
     * Peer information when connection is established.
     */
    data class PeerInfo(
        val method: ConnectionMethod,
        val deviceName: String,
        val address: String,
        val isServer: Boolean
    )
    
    /**
     * Initializes the unified connection manager.
     */
    fun initialize(): Boolean {
        return try {
            if (isInitialized) {
                return true
            }
            
            // Check device compatibility
            compatibilityReport = compatibilityChecker.checkCompatibility()
            
            // Initialize managers based on compatibility (with error handling)
            try {
                initializeManagers()
            } catch (e: Exception) {
                // Log but don't fail - some managers may not be available
                android.util.Log.w(TAG, "Some connection managers failed to initialize: ${e.message}")
            }
            
            isInitialized = true
            true
        } catch (e: Exception) {
            callback.onError("Initialization failed: ${e.message}")
            false
        }
    }
    
    /**
     * Starts connection using the specified method or auto-select.
     */
    fun startConnection(method: ConnectionMethod = ConnectionMethod.AUTO): Boolean {
        return try {
            if (!isInitialized) {
                callback.onError("Manager not initialized")
                return false
            }
            
            val selectedMethod = if (method == ConnectionMethod.AUTO) {
                compatibilityReport?.recommendedMethod ?: ConnectionMethod.BLUETOOTH
            } else {
                method
            }
            
            currentMethod = selectedMethod
            
            // Notify callback about selected method
            val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedMethod)
            callback.onConnectionMethodSelected(selectedMethod, methodInfo)
            
            // Start connection with selected method
            when (selectedMethod) {
                ConnectionMethod.WIFI_DIRECT -> startWifiDirectConnection()
                ConnectionMethod.HOTSPOT -> startHotspotConnection()
                ConnectionMethod.BLUETOOTH -> startBluetoothConnection()
                ConnectionMethod.AUTO -> {
                    // This shouldn't happen as AUTO is resolved above
                    callback.onError("Invalid connection method")
                    false
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to start connection: ${e.message}")
            false
        }
    }
    
    /**
     * Connects to a peer using QR code data.
     */
    fun connectToPeer(qrCodeData: String): Boolean {
        return try {
            val connectionData = parseQrCodeData(qrCodeData)
            val method = ConnectionMethod.valueOf(connectionData["method"] ?: "WIFI_DIRECT")
            
            currentMethod = method
            
            when (method) {
                ConnectionMethod.WIFI_DIRECT -> connectWifiDirect(connectionData)
                ConnectionMethod.HOTSPOT -> connectHotspot(connectionData)
                ConnectionMethod.BLUETOOTH -> connectBluetooth(connectionData)
                ConnectionMethod.AUTO -> {
                    callback.onError("Invalid QR code data")
                    false
                }
            }
        } catch (e: Exception) {
            callback.onError("Failed to connect: ${e.message}")
            false
        }
    }
    
    /**
     * Gets device compatibility report.
     */
    fun getCompatibilityReport(): DeviceCompatibilityChecker.CompatibilityReport? {
        return compatibilityReport
    }
    
    /**
     * Checks if a specific method is supported.
     */
    fun isMethodSupported(method: ConnectionMethod): Boolean {
        return compatibilityChecker.isMethodSupported(method)
    }
    
    /**
     * Gets the recommended connection method for this device.
     */
    fun getRecommendedMethod(): ConnectionMethod {
        return compatibilityReport?.recommendedMethod ?: ConnectionMethod.BLUETOOTH
    }
    
    /**
     * Disconnects current connection.
     */
    fun disconnect() {
        try {
            when (currentMethod) {
                ConnectionMethod.WIFI_DIRECT -> wifiDirectManager?.disconnect()
                ConnectionMethod.HOTSPOT -> hotspotManager?.disableHotspot()
                ConnectionMethod.BLUETOOTH -> bluetoothManager?.disconnect()
                ConnectionMethod.AUTO -> {} // No active connection
            }
        } catch (e: Exception) {
            callback.onError("Disconnect failed: ${e.message}")
        }
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        try {
            wifiDirectManager?.cleanup()
            hotspotManager?.cleanup()
            bluetoothManager?.cleanup()
            
            isInitialized = false
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
    
    // Private methods for initializing managers
    
    private fun initializeManagers() {
        val report = compatibilityReport ?: return
        
        // Initialize Wi-Fi Direct if supported
        if (report.results[ConnectionMethod.WIFI_DIRECT]?.isSupported == true) {
            try {
                wifiDirectManager = WifiDirectManager(context, wifiDirectCallback)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to initialize Wi-Fi Direct manager: ${e.message}")
            }
        }
        
        // Initialize Hotspot if supported
        if (report.results[ConnectionMethod.HOTSPOT]?.isSupported == true) {
            try {
                hotspotManager = HotspotManager(context, hotspotCallback)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to initialize Hotspot manager: ${e.message}")
            }
        }
        
        // Initialize Bluetooth if supported
        if (report.results[ConnectionMethod.BLUETOOTH]?.isSupported == true) {
            try {
                bluetoothManager = BluetoothManager(context, bluetoothCallback)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to initialize Bluetooth manager: ${e.message}")
            }
        }
    }
    
    // Wi-Fi Direct implementation
    
    private fun startWifiDirectConnection(): Boolean {
        val manager = wifiDirectManager
        if (manager == null) {
            tryFallbackMethod("Wi-Fi Direct manager not available")
            return false
        }
        
        return try {
            if (manager.initialize()) {
                manager.createGroup()
            } else {
                tryFallbackMethod("Wi-Fi Direct initialization failed")
                false
            }
        } catch (e: Exception) {
            tryFallbackMethod("Wi-Fi Direct error: ${e.message}")
            false
        }
    }
    
    private fun connectWifiDirect(connectionData: Map<String, String>): Boolean {
        val manager = wifiDirectManager
        if (manager == null) {
            callback.onConnectionFailed(ConnectionMethod.WIFI_DIRECT, "Wi-Fi Direct not supported", false)
            return false
        }
        
        // Wi-Fi Direct connection logic would be implemented here
        // This is a simplified version
        return manager.initialize()
    }
    
    private val wifiDirectCallback = object : WifiDirectCallback {
        override fun onGroupCreated(groupInfo: WifiP2pGroup) {
            val connectionData = mapOf(
                "method" to ConnectionMethod.WIFI_DIRECT.name,
                "groupName" to (groupInfo.networkName ?: ""),
                "passphrase" to (groupInfo.passphrase ?: "")
            )
            
            val qrData = createQrCodeData(connectionData)
            val connectionInfo = ConnectionInfo(
                method = ConnectionMethod.WIFI_DIRECT,
                connectionData = connectionData,
                qrCodeData = qrData
            )
            
            callback.onConnectionReady(connectionInfo)
        }
        
        override fun onPeerAvailable(peer: WifiP2pDevice) {
            // Handle peer discovery
        }
        
        override fun onConnected(connectionInfo: WifiP2pInfo) {
            val peerInfo = PeerInfo(
                method = ConnectionMethod.WIFI_DIRECT,
                deviceName = "Wi-Fi Direct Peer",
                address = connectionInfo.groupOwnerAddress?.hostAddress ?: "",
                isServer = connectionInfo.isGroupOwner
            )
            callback.onConnectionEstablished(peerInfo)
        }
        
        override fun onDisconnected() {
            callback.onDisconnected()
        }
        
        override fun onWifiDirectStateChanged(isEnabled: Boolean) {
            if (!isEnabled) {
                tryFallbackMethod("Wi-Fi Direct disabled")
            }
        }
        
        override fun onError(error: String) {
            tryFallbackMethod("Wi-Fi Direct error: $error")
        }
    }
    
    // Hotspot implementation
    
    private fun startHotspotConnection(): Boolean {
        val manager = hotspotManager
        if (manager == null) {
            tryFallbackMethod("Hotspot manager not available")
            return false
        }
        
        return try {
            manager.createHotspot()
        } catch (e: Exception) {
            tryFallbackMethod("Hotspot error: ${e.message}")
            false
        }
    }
    
    private fun connectHotspot(connectionData: Map<String, String>): Boolean {
        val manager = hotspotManager
        if (manager == null) {
            callback.onConnectionFailed(ConnectionMethod.HOTSPOT, "Hotspot not supported", false)
            return false
        }
        
        val ssid = connectionData["ssid"] ?: return false
        val password = connectionData["password"] ?: return false
        
        return manager.connectToHotspot(ssid, password)
    }
    
    private val hotspotCallback = object : HotspotManager.HotspotCallback {
        override fun onHotspotCreated(ssid: String, password: String, ipAddress: String) {
            val connectionData = mapOf(
                "method" to ConnectionMethod.HOTSPOT.name,
                "ssid" to ssid,
                "password" to password,
                "ipAddress" to ipAddress
            )
            
            val qrData = createQrCodeData(connectionData)
            val connectionInfo = ConnectionInfo(
                method = ConnectionMethod.HOTSPOT,
                connectionData = connectionData,
                qrCodeData = qrData
            )
            
            callback.onConnectionReady(connectionInfo)
        }
        
        override fun onHotspotConnected(clientInfo: HotspotManager.ClientInfo) {
            val peerInfo = PeerInfo(
                method = ConnectionMethod.HOTSPOT,
                deviceName = clientInfo.deviceName ?: "Hotspot Client",
                address = clientInfo.ipAddress,
                isServer = false
            )
            callback.onConnectionEstablished(peerInfo)
        }
        
        override fun onHotspotDisconnected() {
            callback.onDisconnected()
        }
        
        override fun onHotspotError(error: String) {
            tryFallbackMethod("Hotspot error: $error")
        }
    }
    
    // Bluetooth implementation
    
    private fun startBluetoothConnection(): Boolean {
        val manager = bluetoothManager
        if (manager == null) {
            callback.onError("Bluetooth manager not available")
            return false
        }
        
        return try {
            if (manager.initialize()) {
                manager.startAsServer()
            } else {
                callback.onError("Bluetooth initialization failed")
                false
            }
        } catch (e: Exception) {
            callback.onError("Bluetooth error: ${e.message}")
            false
        }
    }
    
    private fun connectBluetooth(connectionData: Map<String, String>): Boolean {
        val manager = bluetoothManager
        if (manager == null) {
            callback.onConnectionFailed(ConnectionMethod.BLUETOOTH, "Bluetooth not supported", false)
            return false
        }
        
        // Bluetooth connection logic would be implemented here
        return manager.initialize()
    }
    
    private val bluetoothCallback = object : BluetoothManager.BluetoothCallback {
        override fun onBluetoothReady(deviceName: String, isDiscoverable: Boolean) {
            val connectionData = mapOf(
                "method" to ConnectionMethod.BLUETOOTH.name,
                "deviceName" to deviceName,
                "discoverable" to isDiscoverable.toString()
            )
            
            val qrData = createQrCodeData(connectionData)
            val connectionInfo = ConnectionInfo(
                method = ConnectionMethod.BLUETOOTH,
                connectionData = connectionData,
                qrCodeData = qrData
            )
            
            callback.onConnectionReady(connectionInfo)
        }
        
        override fun onDeviceDiscovered(device: BluetoothDevice) {
            // Handle device discovery
        }
        
        override fun onConnectionEstablished(device: BluetoothDevice, isServer: Boolean) {
            val peerInfo = PeerInfo(
                method = ConnectionMethod.BLUETOOTH,
                deviceName = device.name ?: "Bluetooth Device",
                address = device.address,
                isServer = isServer
            )
            callback.onConnectionEstablished(peerInfo)
        }
        
        override fun onConnectionFailed(error: String) {
            callback.onConnectionFailed(ConnectionMethod.BLUETOOTH, error, false)
        }
        
        override fun onDisconnected() {
            callback.onDisconnected()
        }
        
        override fun onDataReceived(data: ByteArray) {
            // Handle received data
        }
        
        override fun onDataSent(bytesCount: Int) {
            // Handle sent data
        }
        
        override fun onError(error: String) {
            callback.onError("Bluetooth error: $error")
        }
    }
    
    // Utility methods
    
    private fun tryFallbackMethod(reason: String) {
        val report = compatibilityReport ?: return
        val fallbackMethods = report.fallbackMethods
        
        if (fallbackMethods.isNotEmpty()) {
            val fallbackMethod = fallbackMethods.first()
            callback.onConnectionFailed(currentMethod, reason, true)
            
            // Try fallback method after a short delay
            handler.postDelayed({
                startConnection(fallbackMethod)
            }, 2000)
        } else {
            callback.onConnectionFailed(currentMethod, reason, false)
        }
    }
    
    private fun createQrCodeData(connectionData: Map<String, String>): String {
        val jsonObject = JSONObject()
        connectionData.forEach { (key, value) ->
            jsonObject.put(key, value)
        }
        return jsonObject.toString()
    }
    
    private fun parseQrCodeData(qrData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val jsonObject = JSONObject(qrData)
            jsonObject.keys().forEach { key ->
                result[key] = jsonObject.getString(key)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid QR code data")
        }
        return result
    }
}
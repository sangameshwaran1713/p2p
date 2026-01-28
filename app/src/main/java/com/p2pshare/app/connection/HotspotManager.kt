package com.p2pshare.app.connection

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*

/**
 * Manager for Wi-Fi Hotspot connections as an alternative to Wi-Fi Direct.
 * 
 * This class provides hotspot functionality for devices that don't support
 * Wi-Fi Direct or when Wi-Fi Direct fails to work properly.
 */
class HotspotManager(
    private val context: Context,
    private val callback: HotspotCallback
) {
    
    companion object {
        private const val TAG = "HotspotManager"
        private const val HOTSPOT_SSID_PREFIX = "P2PShare_"
        private const val HOTSPOT_PASSWORD = "p2pshare123"
        private const val HOTSPOT_TIMEOUT_MS = 30000L // 30 seconds
    }
    
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var hotspotTimeoutRunnable: Runnable? = null
    private var isHotspotActive = false
    private var hotspotSSID: String? = null
    
    /**
     * Callback interface for hotspot events.
     */
    interface HotspotCallback {
        fun onHotspotCreated(ssid: String, password: String, ipAddress: String)
        fun onHotspotConnected(clientInfo: ClientInfo)
        fun onHotspotDisconnected()
        fun onHotspotError(error: String)
    }
    
    /**
     * Information about connected client.
     */
    data class ClientInfo(
        val ipAddress: String,
        val macAddress: String,
        val deviceName: String?
    )
    
    /**
     * Creates a Wi-Fi hotspot for file sharing.
     */
    fun createHotspot(): Boolean {
        return try {
            if (isHotspotActive) {
                callback.onHotspotError("Hotspot is already active")
                return false
            }
            
            // Generate unique SSID
            hotspotSSID = "$HOTSPOT_SSID_PREFIX${generateRandomId()}"
            
            // Create hotspot configuration
            val hotspotConfig = createHotspotConfiguration(hotspotSSID!!, HOTSPOT_PASSWORD)
            
            // Enable hotspot
            val success = enableHotspot(hotspotConfig)
            
            if (success) {
                isHotspotActive = true
                startHotspotTimeout()
                
                // Get hotspot IP address
                handler.postDelayed({
                    val ipAddress = getHotspotIpAddress()
                    callback.onHotspotCreated(hotspotSSID!!, HOTSPOT_PASSWORD, ipAddress)
                }, 2000) // Wait for hotspot to fully initialize
                
                true
            } else {
                callback.onHotspotError("Failed to enable Wi-Fi hotspot")
                false
            }
            
        } catch (e: Exception) {
            callback.onHotspotError("Hotspot creation failed: ${e.message}")
            false
        }
    }
    
    /**
     * Connects to an existing hotspot.
     */
    fun connectToHotspot(ssid: String, password: String): Boolean {
        return try {
            // Disable current Wi-Fi connection
            wifiManager.disconnect()
            
            // Create Wi-Fi configuration
            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            
            // Add network and connect
            val networkId = wifiManager.addNetwork(wifiConfig)
            if (networkId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(networkId, true)
                wifiManager.reconnect()
                
                // Monitor connection status
                handler.postDelayed({
                    if (isConnectedToNetwork(ssid)) {
                        val serverIp = getGatewayIpAddress()
                        callback.onHotspotConnected(ClientInfo(serverIp, "", null))
                    } else {
                        callback.onHotspotError("Failed to connect to hotspot")
                    }
                }, 5000)
                
                true
            } else {
                callback.onHotspotError("Failed to add Wi-Fi network")
                false
            }
            
        } catch (e: Exception) {
            callback.onHotspotError("Connection failed: ${e.message}")
            false
        }
    }
    
    /**
     * Disables the hotspot.
     */
    fun disableHotspot() {
        try {
            if (isHotspotActive) {
                disableHotspotInternal()
                isHotspotActive = false
                hotspotSSID = null
                cancelHotspotTimeout()
                callback.onHotspotDisconnected()
            }
        } catch (e: Exception) {
            callback.onHotspotError("Failed to disable hotspot: ${e.message}")
        }
    }
    
    /**
     * Checks if hotspot is currently active.
     */
    fun isHotspotActive(): Boolean = isHotspotActive
    
    /**
     * Gets the current hotspot SSID.
     */
    fun getHotspotSSID(): String? = hotspotSSID
    
    /**
     * Creates hotspot configuration.
     */
    private fun createHotspotConfiguration(ssid: String, password: String): WifiConfiguration {
        return WifiConfiguration().apply {
            SSID = ssid
            preSharedKey = password
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
        }
    }
    
    /**
     * Enables Wi-Fi hotspot using reflection (for older Android versions).
     */
    private fun enableHotspot(config: WifiConfiguration): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For Android 8.0+, use WifiManager.LocalOnlyHotspotReservation
                enableHotspotModern()
            } else {
                // For older versions, use reflection
                enableHotspotLegacy(config)
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Enables hotspot on Android 8.0+ using modern API.
     */
    private fun enableHotspotModern(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Note: This requires CHANGE_WIFI_STATE permission and may not work on all devices
                // due to system restrictions on hotspot control
                val method = wifiManager.javaClass.getMethod("startLocalOnlyHotspot", 
                    WifiManager.LocalOnlyHotspotCallback::class.java, Handler::class.java)
                
                val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        // Hotspot started successfully
                    }
                    
                    override fun onStopped() {
                        // Hotspot stopped
                    }
                    
                    override fun onFailed(reason: Int) {
                        // Hotspot failed to start
                    }
                }
                
                method.invoke(wifiManager, callback, handler)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Enables hotspot on older Android versions using reflection.
     */
    private fun enableHotspotLegacy(config: WifiConfiguration): Boolean {
        return try {
            val method: Method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled", 
                WifiConfiguration::class.java, 
                Boolean::class.javaPrimitiveType
            )
            method.invoke(wifiManager, config, true) as Boolean
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Disables hotspot using reflection.
     */
    private fun disableHotspotInternal() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Modern API doesn't provide direct disable method
                // The LocalOnlyHotspot is automatically managed by the system
            } else {
                val method: Method = wifiManager.javaClass.getMethod(
                    "setWifiApEnabled",
                    WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.invoke(wifiManager, null, false)
            }
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }
    
    /**
     * Gets the hotspot IP address.
     */
    private fun getHotspotIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.contains("ap") || networkInterface.name.contains("wlan")) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            return address.hostAddress ?: "192.168.43.1"
                        }
                    }
                }
            }
            "192.168.43.1" // Default hotspot IP
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }
    
    /**
     * Gets the gateway IP address when connected to a hotspot.
     */
    private fun getGatewayIpAddress(): String {
        return try {
            val dhcpInfo = wifiManager.dhcpInfo
            val gateway = dhcpInfo.gateway
            String.format(
                Locale.getDefault(),
                "%d.%d.%d.%d",
                gateway and 0xff,
                gateway shr 8 and 0xff,
                gateway shr 16 and 0xff,
                gateway shr 24 and 0xff
            )
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }
    
    /**
     * Checks if connected to a specific network.
     */
    private fun isConnectedToNetwork(ssid: String): Boolean {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val currentSSID = wifiInfo?.ssid?.replace("\"", "")
            currentSSID == ssid
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Generates a random ID for hotspot SSID.
     */
    private fun generateRandomId(): String {
        return (1000..9999).random().toString()
    }
    
    /**
     * Starts hotspot timeout timer.
     */
    private fun startHotspotTimeout() {
        cancelHotspotTimeout()
        hotspotTimeoutRunnable = Runnable {
            if (isHotspotActive) {
                callback.onHotspotError("Hotspot creation timed out")
                disableHotspot()
            }
        }
        handler.postDelayed(hotspotTimeoutRunnable!!, HOTSPOT_TIMEOUT_MS)
    }
    
    /**
     * Cancels hotspot timeout timer.
     */
    private fun cancelHotspotTimeout() {
        hotspotTimeoutRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            hotspotTimeoutRunnable = null
        }
    }
    
    /**
     * Cleanup resources.
     */
    fun cleanup() {
        disableHotspot()
        cancelHotspotTimeout()
    }
}
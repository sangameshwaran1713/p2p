package com.p2pshare.app.connection

/**
 * Enumeration of available connection methods for P2P file sharing.
 * 
 * This allows the app to support multiple connection types based on
 * device capabilities and user preferences.
 */
enum class ConnectionMethod {
    /**
     * Wi-Fi Direct - Direct device-to-device Wi-Fi connection.
     * Fastest and most secure, but not supported on all devices.
     */
    WIFI_DIRECT,
    
    /**
     * Hotspot Mode - One device creates Wi-Fi hotspot, other connects.
     * Good fallback when Wi-Fi Direct doesn't work.
     */
    HOTSPOT,
    
    /**
     * Bluetooth - Traditional Bluetooth file transfer.
     * Slowest but most compatible across all Android devices.
     */
    BLUETOOTH,
    
    /**
     * Auto - Automatically select best available method.
     * Tests methods in order: Wi-Fi Direct → Hotspot → Bluetooth
     */
    AUTO
}

/**
 * Connection method capabilities and characteristics.
 */
data class ConnectionMethodInfo(
    val method: ConnectionMethod,
    val displayName: String,
    val description: String,
    val speedRating: Int, // 1-5 stars
    val compatibilityRating: Int, // 1-5 stars
    val requiresInternet: Boolean,
    val maxRange: String,
    val estimatedSpeed: String
) {
    companion object {
        fun getMethodInfo(method: ConnectionMethod): ConnectionMethodInfo {
            return when (method) {
                ConnectionMethod.WIFI_DIRECT -> ConnectionMethodInfo(
                    method = ConnectionMethod.WIFI_DIRECT,
                    displayName = "Wi-Fi Direct",
                    description = "Direct device-to-device connection (fastest)",
                    speedRating = 5,
                    compatibilityRating = 3,
                    requiresInternet = false,
                    maxRange = "50-200 meters",
                    estimatedSpeed = "10-50 MB/s"
                )
                
                ConnectionMethod.HOTSPOT -> ConnectionMethodInfo(
                    method = ConnectionMethod.HOTSPOT,
                    displayName = "Wi-Fi Hotspot",
                    description = "One device creates hotspot, other connects",
                    speedRating = 4,
                    compatibilityRating = 4,
                    requiresInternet = false,
                    maxRange = "30-100 meters",
                    estimatedSpeed = "5-25 MB/s"
                )
                
                ConnectionMethod.BLUETOOTH -> ConnectionMethodInfo(
                    method = ConnectionMethod.BLUETOOTH,
                    displayName = "Bluetooth",
                    description = "Traditional Bluetooth transfer (most compatible)",
                    speedRating = 2,
                    compatibilityRating = 5,
                    requiresInternet = false,
                    maxRange = "10-30 meters",
                    estimatedSpeed = "1-3 MB/s"
                )
                
                ConnectionMethod.AUTO -> ConnectionMethodInfo(
                    method = ConnectionMethod.AUTO,
                    displayName = "Auto-Select",
                    description = "Automatically choose best available method",
                    speedRating = 4,
                    compatibilityRating = 5,
                    requiresInternet = false,
                    maxRange = "Varies",
                    estimatedSpeed = "Varies"
                )
            }
        }
        
        fun getAllMethods(): List<ConnectionMethodInfo> {
            return listOf(
                getMethodInfo(ConnectionMethod.AUTO),
                getMethodInfo(ConnectionMethod.WIFI_DIRECT),
                getMethodInfo(ConnectionMethod.HOTSPOT),
                getMethodInfo(ConnectionMethod.BLUETOOTH)
            )
        }
    }
}
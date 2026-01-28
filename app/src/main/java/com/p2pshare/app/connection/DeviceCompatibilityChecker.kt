package com.p2pshare.app.connection

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Device compatibility checker for P2P file sharing methods.
 * 
 * This class tests device capabilities and recommends the best
 * connection method based on hardware support and system configuration.
 */
class DeviceCompatibilityChecker(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceCompatibilityChecker"
    }
    
    /**
     * Compatibility test results for a connection method.
     */
    data class CompatibilityResult(
        val method: ConnectionMethod,
        val isSupported: Boolean,
        val isRecommended: Boolean,
        val issues: List<String> = emptyList(),
        val requirements: List<String> = emptyList()
    )
    
    /**
     * Complete device compatibility report.
     */
    data class CompatibilityReport(
        val deviceInfo: DeviceInfo,
        val results: Map<ConnectionMethod, CompatibilityResult>,
        val recommendedMethod: ConnectionMethod,
        val fallbackMethods: List<ConnectionMethod>
    )
    
    /**
     * Device information for compatibility analysis.
     */
    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val hasWifiDirect: Boolean,
        val hasWifi: Boolean,
        val hasBluetooth: Boolean,
        val hasHotspotCapability: Boolean
    )
    
    /**
     * Performs comprehensive compatibility check for all connection methods.
     */
    fun checkCompatibility(): CompatibilityReport {
        val deviceInfo = getDeviceInfo()
        val results = mutableMapOf<ConnectionMethod, CompatibilityResult>()
        
        // Test each connection method
        results[ConnectionMethod.WIFI_DIRECT] = checkWifiDirectCompatibility()
        results[ConnectionMethod.HOTSPOT] = checkHotspotCompatibility()
        results[ConnectionMethod.BLUETOOTH] = checkBluetoothCompatibility()
        
        // Determine recommended method and fallbacks
        val recommendedMethod = determineRecommendedMethod(results)
        val fallbackMethods = determineFallbackMethods(results, recommendedMethod)
        
        return CompatibilityReport(
            deviceInfo = deviceInfo,
            results = results,
            recommendedMethod = recommendedMethod,
            fallbackMethods = fallbackMethods
        )
    }
    
    /**
     * Quick compatibility check for a specific method.
     */
    fun isMethodSupported(method: ConnectionMethod): Boolean {
        return when (method) {
            ConnectionMethod.WIFI_DIRECT -> checkWifiDirectSupport()
            ConnectionMethod.HOTSPOT -> checkHotspotSupport()
            ConnectionMethod.BLUETOOTH -> checkBluetoothSupport()
            ConnectionMethod.AUTO -> true // Auto is always "supported"
        }
    }
    
    /**
     * Gets the best available connection method for this device.
     */
    fun getBestMethod(): ConnectionMethod {
        return when {
            checkWifiDirectSupport() -> ConnectionMethod.WIFI_DIRECT
            checkHotspotSupport() -> ConnectionMethod.HOTSPOT
            checkBluetoothSupport() -> ConnectionMethod.BLUETOOTH
            else -> ConnectionMethod.BLUETOOTH // Fallback to Bluetooth
        }
    }
    
    /**
     * Checks Wi-Fi Direct compatibility.
     */
    private fun checkWifiDirectCompatibility(): CompatibilityResult {
        val issues = mutableListOf<String>()
        val requirements = mutableListOf<String>()
        
        // Check hardware support
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            issues.add("Wi-Fi Direct not supported by hardware")
        }
        
        // Check Wi-Fi Direct manager availability
        val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            issues.add("Wi-Fi Direct service not available")
        }
        
        // Check permissions
        val requiredPermissions = arrayOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        requiredPermissions.forEach { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                requirements.add("Permission required: ${permission.substringAfterLast('.')}")
            }
        }
        
        // Check location services
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) &&
            !locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
            requirements.add("Location services must be enabled")
        }
        
        // Check Wi-Fi state
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            requirements.add("Wi-Fi must be enabled")
        }
        
        val isSupported = issues.isEmpty()
        val isRecommended = isSupported && requirements.isEmpty()
        
        return CompatibilityResult(
            method = ConnectionMethod.WIFI_DIRECT,
            isSupported = isSupported,
            isRecommended = isRecommended,
            issues = issues,
            requirements = requirements
        )
    }
    
    /**
     * Checks Wi-Fi Hotspot compatibility.
     */
    private fun checkHotspotCompatibility(): CompatibilityResult {
        val issues = mutableListOf<String>()
        val requirements = mutableListOf<String>()
        
        // Check Wi-Fi availability
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            issues.add("Wi-Fi not supported")
        }
        
        // Check permissions
        val requiredPermissions = arrayOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requiredPermissions.plus(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        requiredPermissions.forEach { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                requirements.add("Permission required: ${permission.substringAfterLast('.')}")
            }
        }
        
        // Check Wi-Fi state
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            requirements.add("Wi-Fi must be enabled")
        }
        
        val isSupported = issues.isEmpty()
        val isRecommended = isSupported && requirements.isEmpty()
        
        return CompatibilityResult(
            method = ConnectionMethod.HOTSPOT,
            isSupported = isSupported,
            isRecommended = isRecommended,
            issues = issues,
            requirements = requirements
        )
    }
    
    /**
     * Checks Bluetooth compatibility.
     */
    private fun checkBluetoothCompatibility(): CompatibilityResult {
        val issues = mutableListOf<String>()
        val requirements = mutableListOf<String>()
        
        // Check Bluetooth hardware support
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            issues.add("Bluetooth not supported by hardware")
        }
        
        // Check Bluetooth adapter
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            issues.add("Bluetooth adapter not available")
        }
        
        // Check permissions
        val requiredPermissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.addAll(arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            requiredPermissions.addAll(arrayOf(
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN
            ))
        }
        
        requiredPermissions.forEach { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                requirements.add("Permission required: ${permission.substringAfterLast('.')}")
            }
        }
        
        // Check Bluetooth state
        if (bluetoothAdapter?.isEnabled != true) {
            requirements.add("Bluetooth must be enabled")
        }
        
        val isSupported = issues.isEmpty()
        val isRecommended = isSupported && requirements.isEmpty()
        
        return CompatibilityResult(
            method = ConnectionMethod.BLUETOOTH,
            isSupported = isSupported,
            isRecommended = isRecommended,
            issues = issues,
            requirements = requirements
        )
    }
    
    /**
     * Quick Wi-Fi Direct support check.
     */
    private fun checkWifiDirectSupport(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
                context.getSystemService(Context.WIFI_P2P_SERVICE) != null
    }
    
    /**
     * Quick hotspot support check.
     */
    private fun checkHotspotSupport(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
    }
    
    /**
     * Quick Bluetooth support check.
     */
    private fun checkBluetoothSupport(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
                BluetoothAdapter.getDefaultAdapter() != null
    }
    
    /**
     * Gets device information for compatibility analysis.
     */
    private fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            hasWifiDirect = checkWifiDirectSupport(),
            hasWifi = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
            hasBluetooth = checkBluetoothSupport(),
            hasHotspotCapability = checkHotspotSupport()
        )
    }
    
    /**
     * Determines the recommended connection method based on compatibility results.
     */
    private fun determineRecommendedMethod(results: Map<ConnectionMethod, CompatibilityResult>): ConnectionMethod {
        // Prefer Wi-Fi Direct if fully supported and recommended
        results[ConnectionMethod.WIFI_DIRECT]?.let { wifiDirectResult ->
            if (wifiDirectResult.isRecommended) {
                return ConnectionMethod.WIFI_DIRECT
            }
        }
        
        // Fall back to Hotspot if recommended
        results[ConnectionMethod.HOTSPOT]?.let { hotspotResult ->
            if (hotspotResult.isRecommended) {
                return ConnectionMethod.HOTSPOT
            }
        }
        
        // Fall back to Bluetooth if recommended
        results[ConnectionMethod.BLUETOOTH]?.let { bluetoothResult ->
            if (bluetoothResult.isRecommended) {
                return ConnectionMethod.BLUETOOTH
            }
        }
        
        // If nothing is fully recommended, prefer supported methods in order
        return when {
            results[ConnectionMethod.WIFI_DIRECT]?.isSupported == true -> ConnectionMethod.WIFI_DIRECT
            results[ConnectionMethod.HOTSPOT]?.isSupported == true -> ConnectionMethod.HOTSPOT
            results[ConnectionMethod.BLUETOOTH]?.isSupported == true -> ConnectionMethod.BLUETOOTH
            else -> ConnectionMethod.BLUETOOTH // Always fall back to Bluetooth
        }
    }
    
    /**
     * Determines fallback methods in order of preference.
     */
    private fun determineFallbackMethods(
        results: Map<ConnectionMethod, CompatibilityResult>,
        recommendedMethod: ConnectionMethod
    ): List<ConnectionMethod> {
        val allMethods = listOf(ConnectionMethod.WIFI_DIRECT, ConnectionMethod.HOTSPOT, ConnectionMethod.BLUETOOTH)
        
        return allMethods
            .filter { it != recommendedMethod }
            .filter { results[it]?.isSupported == true }
            .sortedByDescending { method ->
                // Sort by preference: Wi-Fi Direct > Hotspot > Bluetooth
                when (method) {
                    ConnectionMethod.WIFI_DIRECT -> 3
                    ConnectionMethod.HOTSPOT -> 2
                    ConnectionMethod.BLUETOOTH -> 1
                    ConnectionMethod.AUTO -> 0
                }
            }
    }
}
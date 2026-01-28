package com.p2pshare.app.wifi

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.net.InetAddress

/**
 * Manager class for Wi-Fi Direct operations in P2P file sharing.
 * 
 * This class handles all Wi-Fi Direct functionality including:
 * - Peer discovery and management
 * - Group creation and ownership
 * - Connection establishment and management
 * - State monitoring and error handling
 * 
 * The class follows Android's Wi-Fi Direct API patterns and provides
 * a simplified interface for P2P file sharing operations.
 */
class WifiDirectManager(
    private val context: Context,
    private val callback: WifiDirectCallback
) {
    
    companion object {
        private const val TAG = "WifiDirectManager"
        private const val DISCOVERY_TIMEOUT_MS = 30000L // 30 seconds
        private const val CONNECTION_TIMEOUT_MS = 30000L // 30 seconds
    }
    
    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    
    private var channel: WifiP2pManager.Channel? = null
    private var wifiDirectReceiver: WifiDirectReceiver? = null
    private var isDiscovering = false
    private var isGroupOwner = false
    private var groupInfo: WifiP2pGroup? = null
    private var connectionInfo: WifiP2pInfo? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var discoveryTimeoutRunnable: Runnable? = null
    
    /**
     * Initializes the Wi-Fi Direct manager.
     * 
     * This method sets up the Wi-Fi Direct channel and registers
     * the broadcast receiver for Wi-Fi Direct events.
     * 
     * @return true if initialization was successful, false otherwise
     */
    fun initialize(): Boolean {
        return try {
            val manager = wifiP2pManager ?: run {
                callback.onError("Wi-Fi Direct is not supported on this device")
                return false
            }
            
            channel = manager.initialize(context, Looper.getMainLooper()) { 
                callback.onError("Wi-Fi Direct channel disconnected")
            }
            
            if (channel == null) {
                callback.onError("Failed to initialize Wi-Fi Direct channel")
                return false
            }
            
            // Create and register the broadcast receiver
            wifiDirectReceiver = WifiDirectReceiver(this)
            val intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            
            context.registerReceiver(wifiDirectReceiver, intentFilter)
            true
        } catch (e: Exception) {
            callback.onError("Failed to initialize Wi-Fi Direct: ${e.message}")
            false
        }
    }
    
    /**
     * Starts peer discovery to find nearby devices.
     * 
     * This method initiates the discovery process to find other devices
     * running the same P2P file sharing application.
     * 
     * @return true if discovery was started successfully, false otherwise
     */
    fun startPeerDiscovery(): Boolean {
        if (!checkPermissions()) {
            callback.onError("Required permissions not granted")
            return false
        }
        
        val manager = wifiP2pManager ?: return false
        val ch = channel ?: return false
        
        if (isDiscovering) {
            return true // Already discovering
        }
        
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = true
                startDiscoveryTimeout()
            }
            
            override fun onFailure(reason: Int) {
                val errorMsg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
                    WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
                    WifiP2pManager.ERROR -> "Wi-Fi Direct error occurred"
                    else -> "Unknown error: $reason"
                }
                callback.onError("Failed to start peer discovery: $errorMsg")
            }
        })
        
        return true
    }
    
    /**
     * Stops peer discovery.
     */
    fun stopPeerDiscovery() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return
        
        if (isDiscovering) {
            manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isDiscovering = false
                    cancelDiscoveryTimeout()
                }
                
                override fun onFailure(reason: Int) {
                    // Log but don't callback error for stop operations
                }
            })
        }
    }
    
    /**
     * Creates a Wi-Fi Direct group with this device as the group owner.
     * 
     * This method is typically called by the sender device to create
     * a group that receiver devices can connect to.
     * 
     * @return true if group creation was initiated successfully, false otherwise
     */
    fun createGroup(): Boolean {
        if (!checkPermissions()) {
            callback.onError("Required permissions not granted")
            return false
        }
        
        val manager = wifiP2pManager ?: run {
            callback.onError("Wi-Fi Direct manager not available")
            return false
        }
        
        val ch = channel ?: run {
            callback.onError("Wi-Fi Direct channel not initialized")
            return false
        }
        
        // Try direct group creation first (some devices work better this way)
        createGroupInternal(manager, ch)
        
        return true
    }
    
    /**
     * Creates a group with cleanup first (alternative approach).
     */
    fun createGroupWithCleanup(): Boolean {
        if (!checkPermissions()) {
            callback.onError("Required permissions not granted")
            return false
        }
        
        val manager = wifiP2pManager ?: run {
            callback.onError("Wi-Fi Direct manager not available")
            return false
        }
        
        val ch = channel ?: run {
            callback.onError("Wi-Fi Direct channel not initialized")
            return false
        }
        
        // First, try to clean up any existing groups
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Successfully removed existing group, now create new one
                handler.postDelayed({
                    createGroupInternal(manager, ch)
                }, 1000)
            }
            
            override fun onFailure(reason: Int) {
                // No existing group to remove, proceed with creation
                createGroupInternal(manager, ch)
            }
        })
        
        return true
    }
    
    /**
     * Internal method to create the Wi-Fi Direct group.
     */
    private fun createGroupInternal(manager: WifiP2pManager, ch: WifiP2pManager.Channel) {
        manager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isGroupOwner = true
                // Request group info immediately after successful creation
                handler.postDelayed({
                    requestGroupInfo()
                }, 1000) // Wait 1 second for group to be fully established
            }
            
            override fun onFailure(reason: Int) {
                when (reason) {
                    WifiP2pManager.BUSY -> {
                        // If still busy, try again after a short delay
                        handler.postDelayed({
                            retryCreateGroup(manager, ch, 1)
                        }, 2000)
                    }
                    WifiP2pManager.P2P_UNSUPPORTED -> {
                        callback.onError("Wi-Fi Direct is not supported on this device")
                    }
                    WifiP2pManager.ERROR -> {
                        callback.onError("Wi-Fi Direct error occurred. Please try again.")
                    }
                    else -> {
                        callback.onError("Failed to create group: Unknown error ($reason)")
                    }
                }
            }
        })
    }
    
    /**
     * Retries group creation with different strategies.
     */
    private fun retryCreateGroup(manager: WifiP2pManager, ch: WifiP2pManager.Channel, attempt: Int) {
        if (attempt > 5) {
            callback.onError("Failed to create group after multiple attempts. Please restart Wi-Fi and try again.")
            return
        }
        
        when (attempt) {
            1, 2 -> {
                // First two attempts: try direct creation with delays
                handler.postDelayed({
                    createGroupInternal(manager, ch)
                }, (2000 * attempt).toLong())
            }
            3 -> {
                // Third attempt: try aggressive reset then create
                handler.postDelayed({
                    if (aggressiveReset()) {
                        val newCh = channel
                        if (newCh != null) {
                            createGroupInternal(manager, newCh)
                        } else {
                            callback.onError("Failed to reset Wi-Fi Direct channel")
                        }
                    }
                }, 3000)
            }
            4 -> {
                // Fourth attempt: try with cleanup first
                handler.postDelayed({
                    manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            handler.postDelayed({
                                createGroupInternal(manager, ch)
                            }, 2000)
                        }
                        override fun onFailure(reason: Int) {
                            createGroupInternal(manager, ch)
                        }
                    })
                }, 4000)
            }
            5 -> {
                // Final attempt: suggest user action
                callback.onError("Wi-Fi Direct is persistently busy. Please:\n1. Turn Wi-Fi OFF for 30 seconds\n2. Turn Wi-Fi ON\n3. Try again")
            }
        }
    }
    
    /**
     * Connects to a specific peer device.
     * 
     * This method is typically called by the receiver device to connect
     * to the sender's group.
     * 
     * @param device The peer device to connect to
     * @return true if connection was initiated successfully, false otherwise
     */
    fun connectToPeer(device: WifiP2pDevice): Boolean {
        if (!checkPermissions()) {
            callback.onError("Required permissions not granted")
            return false
        }
        
        val manager = wifiP2pManager ?: return false
        val ch = channel ?: return false
        
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC // Push Button Configuration
        }
        
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Connection info will be received via broadcast receiver
            }
            
            override fun onFailure(reason: Int) {
                val errorMsg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
                    WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
                    WifiP2pManager.ERROR -> "Wi-Fi Direct error occurred"
                    else -> "Unknown error: $reason"
                }
                callback.onError("Failed to connect to peer: $errorMsg")
            }
        })
        
        return true
    }
    
    /**
     * Disconnects from the current Wi-Fi Direct group or peer.
     */
    fun disconnect() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return
        
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                cleanup()
            }
            
            override fun onFailure(reason: Int) {
                // Still cleanup even if removal failed
                cleanup()
            }
        })
    }
    
    /**
     * Requests connection information for the current connection.
     * 
     * This method is called internally when a connection is established
     * to get details about the connection (IP addresses, group owner, etc.).
     */
    internal fun requestConnectionInfo() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return
        
        manager.requestConnectionInfo(ch) { info ->
            connectionInfo = info
            if (info != null) {
                callback.onConnected(info)
            }
        }
    }
    
    /**
     * Requests group information for the current group.
     * 
     * This method is called internally when a group is created or joined
     * to get details about the group.
     */
    internal fun requestGroupInfo() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return
        
        manager.requestGroupInfo(ch) { group ->
            groupInfo = group
            if (group != null) {
                callback.onGroupCreated(group)
            }
        }
    }
    
    /**
     * Requests the list of discovered peers.
     * 
     * This method is called internally when peers are discovered
     * to get the updated list of available devices.
     */
    internal fun requestPeers() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return
        
        manager.requestPeers(ch) { peerList ->
            peerList?.deviceList?.forEach { peer ->
                callback.onPeerAvailable(peer)
            }
        }
    }
    
    /**
     * Handles Wi-Fi Direct state changes.
     * 
     * @param isEnabled true if Wi-Fi Direct is enabled, false otherwise
     */
    internal fun onWifiDirectStateChanged(isEnabled: Boolean) {
        callback.onWifiDirectStateChanged(isEnabled)
        if (!isEnabled) {
            cleanup()
        }
    }
    
    /**
     * Gets the current connection information.
     * 
     * @return Current connection info, or null if not connected
     */
    fun getConnectionInfo(): WifiP2pInfo? = connectionInfo
    
    /**
     * Gets the current group information.
     * 
     * @return Current group info, or null if not in a group
     */
    fun getGroupInfo(): WifiP2pGroup? = groupInfo
    
    /**
     * Checks if this device is the group owner.
     * 
     * @return true if this device is the group owner, false otherwise
     */
    fun isGroupOwner(): Boolean = connectionInfo?.isGroupOwner ?: isGroupOwner
    
    /**
     * Gets the group owner's IP address.
     * 
     * @return Group owner's IP address, or null if not connected
     */
    fun getGroupOwnerAddress(): InetAddress? = connectionInfo?.groupOwnerAddress
    
    /**
     * Checks if currently discovering peers.
     * 
     * @return true if peer discovery is active, false otherwise
     */
    fun isDiscovering(): Boolean = isDiscovering
    
    /**
     * Forces cleanup of Wi-Fi Direct state and removes any existing groups.
     */
    fun forceCleanup() {
        val manager = wifiP2pManager
        val ch = channel
        
        if (manager != null && ch != null) {
            // Stop any ongoing discovery first
            manager.stopPeerDiscovery(ch, null)
            
            // Cancel any ongoing connections
            manager.cancelConnect(ch, null)
            
            // Remove any existing groups
            manager.removeGroup(ch, null)
            
            // Clear any device info requests
            try {
                manager.requestGroupInfo(ch) { group ->
                    if (group != null) {
                        manager.removeGroup(ch, null)
                    }
                }
            } catch (e: Exception) {
                // Ignore errors during cleanup
            }
        }
        
        cleanup()
    }
    
    /**
     * Performs aggressive Wi-Fi Direct reset by reinitializing the channel.
     */
    fun aggressiveReset(): Boolean {
        return try {
            // First cleanup everything
            forceCleanup()
            
            // Wait a moment
            Thread.sleep(2000)
            
            // Reinitialize the channel
            val manager = wifiP2pManager ?: return false
            
            channel = manager.initialize(context, Looper.getMainLooper()) { 
                callback.onError("Wi-Fi Direct channel disconnected during reset")
            }
            
            if (channel == null) {
                callback.onError("Failed to reinitialize Wi-Fi Direct channel")
                return false
            }
            
            // Re-register the broadcast receiver
            wifiDirectReceiver?.let { receiver ->
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    // Receiver was not registered
                }
            }
            
            wifiDirectReceiver = WifiDirectReceiver(this)
            val intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            
            context.registerReceiver(wifiDirectReceiver, intentFilter)
            
            true
        } catch (e: Exception) {
            callback.onError("Failed to reset Wi-Fi Direct: ${e.message}")
            false
        }
    }
    
    /**
     * Cleans up resources and unregisters receivers.
     * 
     * This method should be called when the Wi-Fi Direct functionality
     * is no longer needed (e.g., when the activity is destroyed).
     */
    fun cleanup() {
        stopPeerDiscovery()
        cancelDiscoveryTimeout()
        
        wifiDirectReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered
            }
            wifiDirectReceiver = null
        }
        
        isGroupOwner = false
        groupInfo = null
        connectionInfo = null
        
        callback.onDisconnected()
    }
    
    /**
     * Checks if required permissions are granted.
     * 
     * @return true if all required permissions are granted, false otherwise
     */
    private fun checkPermissions(): Boolean {
        val requiredPermissions = arrayOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Starts the discovery timeout timer.
     */
    private fun startDiscoveryTimeout() {
        cancelDiscoveryTimeout()
        discoveryTimeoutRunnable = Runnable {
            if (isDiscovering) {
                stopPeerDiscovery()
                callback.onError("Peer discovery timed out")
            }
        }
        handler.postDelayed(discoveryTimeoutRunnable!!, DISCOVERY_TIMEOUT_MS)
    }
    
    /**
     * Cancels the discovery timeout timer.
     */
    private fun cancelDiscoveryTimeout() {
        discoveryTimeoutRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            discoveryTimeoutRunnable = null
        }
    }
}
package com.p2pshare.app.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

/**
 * Broadcast receiver for Wi-Fi Direct system events.
 * 
 * This receiver handles all Wi-Fi Direct related system broadcasts and
 * delegates them to the WifiDirectManager for processing. It monitors:
 * - Wi-Fi Direct state changes (enabled/disabled)
 * - Peer discovery results
 * - Connection state changes
 * - Device information updates
 * 
 * The receiver acts as a bridge between Android's Wi-Fi Direct system
 * and our application's Wi-Fi Direct management logic.
 */
class WifiDirectReceiver(
    private val wifiDirectManager: WifiDirectManager
) : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "WifiDirectReceiver"
    }
    
    /**
     * Receives and processes Wi-Fi Direct system broadcasts.
     * 
     * This method is called by the Android system when Wi-Fi Direct
     * related events occur. It filters the events and delegates
     * appropriate actions to the WifiDirectManager.
     * 
     * @param context The context in which the receiver is running
     * @param intent The intent containing the broadcast information
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                handleWifiP2pStateChanged(intent)
            }
            
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                handlePeersChanged()
            }
            
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                handleConnectionChanged(intent)
            }
            
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                handleThisDeviceChanged(intent)
            }
            
            else -> {
                Log.d(TAG, "Received unknown Wi-Fi Direct action: ${intent.action}")
            }
        }
    }
    
    /**
     * Handles Wi-Fi Direct state change events.
     * 
     * This method is called when Wi-Fi Direct is enabled or disabled
     * on the device. It notifies the manager about the state change.
     * 
     * @param intent The intent containing state information
     */
    private fun handleWifiP2pStateChanged(intent: Intent) {
        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
        
        when (state) {
            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                Log.d(TAG, "Wi-Fi Direct enabled")
                wifiDirectManager.onWifiDirectStateChanged(true)
            }
            
            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
                Log.d(TAG, "Wi-Fi Direct disabled")
                wifiDirectManager.onWifiDirectStateChanged(false)
            }
            
            else -> {
                Log.w(TAG, "Unknown Wi-Fi Direct state: $state")
            }
        }
    }
    
    /**
     * Handles peer discovery result events.
     * 
     * This method is called when the list of available peers changes
     * during peer discovery. It requests the updated peer list from
     * the Wi-Fi Direct manager.
     */
    private fun handlePeersChanged() {
        Log.d(TAG, "Peers changed - requesting peer list")
        wifiDirectManager.requestPeers()
    }
    
    /**
     * Handles connection state change events.
     * 
     * This method is called when a Wi-Fi Direct connection is established,
     * lost, or when connection parameters change. It processes both
     * connection establishment and disconnection events.
     * 
     * @param intent The intent containing connection information
     */
    private fun handleConnectionChanged(intent: Intent) {
        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
        
        if (networkInfo == null) {
            Log.w(TAG, "Connection changed but no network info available")
            return
        }
        
        Log.d(TAG, "Connection changed - Network state: ${networkInfo.state}, Connected: ${networkInfo.isConnected}")
        
        when {
            networkInfo.isConnected -> {
                Log.d(TAG, "Wi-Fi Direct connection established")
                // Request connection info to get details about the connection
                wifiDirectManager.requestConnectionInfo()
                // Also request group info if we're in a group
                wifiDirectManager.requestGroupInfo()
            }
            
            !networkInfo.isConnected && networkInfo.isConnectedOrConnecting -> {
                Log.d(TAG, "Wi-Fi Direct connection lost")
                // Connection was lost - this will be handled by the manager
            }
            
            else -> {
                Log.d(TAG, "Wi-Fi Direct connection state: ${networkInfo.state}")
                // For group creation, we should also request group info even if not fully connected yet
                wifiDirectManager.requestGroupInfo()
            }
        }
    }
    
    /**
     * Handles device information change events.
     * 
     * This method is called when information about this device changes,
     * such as device name, status, or capabilities. Currently, this is
     * primarily used for logging and debugging purposes.
     * 
     * @param intent The intent containing device information
     */
    private fun handleThisDeviceChanged(intent: Intent) {
        val device = intent.getParcelableExtra<android.net.wifi.p2p.WifiP2pDevice>(
            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
        )
        
        if (device != null) {
            Log.d(TAG, "This device changed: ${device.deviceName} (${device.deviceAddress})")
            Log.d(TAG, "Device status: ${getDeviceStatusString(device.status)}")
        } else {
            Log.w(TAG, "Device changed but no device info available")
        }
    }
    
    /**
     * Converts device status code to human-readable string.
     * 
     * This helper method converts the numeric device status codes
     * used by Wi-Fi Direct into readable strings for logging and debugging.
     * 
     * @param status The numeric device status code
     * @return Human-readable status string
     */
    private fun getDeviceStatusString(status: Int): String {
        return when (status) {
            android.net.wifi.p2p.WifiP2pDevice.AVAILABLE -> "Available"
            android.net.wifi.p2p.WifiP2pDevice.INVITED -> "Invited"
            android.net.wifi.p2p.WifiP2pDevice.CONNECTED -> "Connected"
            android.net.wifi.p2p.WifiP2pDevice.FAILED -> "Failed"
            android.net.wifi.p2p.WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown ($status)"
        }
    }
}
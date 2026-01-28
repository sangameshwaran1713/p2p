package com.p2pshare.app.wifi

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo

/**
 * Callback interface for Wi-Fi Direct events.
 * 
 * This interface defines the callbacks that will be invoked when various
 * Wi-Fi Direct events occur during the P2P file sharing process.
 */
interface WifiDirectCallback {
    
    /**
     * Called when a Wi-Fi Direct group is successfully created.
     * 
     * This callback is invoked when the device becomes a group owner
     * and is ready to accept connections from other devices.
     * 
     * @param groupInfo Information about the created group
     */
    fun onGroupCreated(groupInfo: WifiP2pGroup)
    
    /**
     * Called when a new peer device is discovered.
     * 
     * This callback is invoked during peer discovery when a nearby
     * device running the same application is found.
     * 
     * @param peer Information about the discovered peer device
     */
    fun onPeerAvailable(peer: WifiP2pDevice)
    
    /**
     * Called when a Wi-Fi Direct connection is established.
     * 
     * This callback is invoked when two devices have successfully
     * connected and are ready to exchange data.
     * 
     * @param connectionInfo Information about the established connection
     */
    fun onConnected(connectionInfo: WifiP2pInfo)
    
    /**
     * Called when a Wi-Fi Direct connection is lost or terminated.
     * 
     * This callback is invoked when the connection between devices
     * is broken, either intentionally or due to an error.
     */
    fun onDisconnected()
    
    /**
     * Called when Wi-Fi Direct state changes.
     * 
     * This callback is invoked when Wi-Fi Direct is enabled/disabled
     * or when the device's Wi-Fi Direct capabilities change.
     * 
     * @param isEnabled true if Wi-Fi Direct is enabled, false otherwise
     */
    fun onWifiDirectStateChanged(isEnabled: Boolean)
    
    /**
     * Called when an error occurs during Wi-Fi Direct operations.
     * 
     * This callback is invoked when an error occurs during peer discovery,
     * group creation, or connection establishment.
     * 
     * @param error Description of the error that occurred
     */
    fun onError(error: String)
}
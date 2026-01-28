package com.p2pshare.app.wifi

import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

/**
 * Unit tests for WifiDirectReceiver class.
 * Tests broadcast handling and delegation to WifiDirectManager.
 */
class WifiDirectReceiverTest {
    
    @Mock
    private lateinit var mockContext: Context
    
    @Mock
    private lateinit var mockWifiDirectManager: WifiDirectManager
    
    private lateinit var wifiDirectReceiver: WifiDirectReceiver
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        wifiDirectReceiver = WifiDirectReceiver(mockWifiDirectManager)
    }
    
    @Test
    fun testWifiP2pStateChanged_Enabled() {
        val intent = Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).apply {
            putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        }
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        verify(mockWifiDirectManager).onWifiDirectStateChanged(true)
    }
    
    @Test
    fun testWifiP2pStateChanged_Disabled() {
        val intent = Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).apply {
            putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED)
        }
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        verify(mockWifiDirectManager).onWifiDirectStateChanged(false)
    }
    
    @Test
    fun testWifiP2pStateChanged_UnknownState() {
        val intent = Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).apply {
            putExtra(WifiP2pManager.EXTRA_WIFI_STATE, -999) // Unknown state
        }
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        // Should not call manager for unknown states
        verify(mockWifiDirectManager, never()).onWifiDirectStateChanged(anyBoolean())
    }
    
    @Test
    fun testWifiP2pStateChanged_MissingExtra() {
        val intent = Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        // No EXTRA_WIFI_STATE added
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        // Should not call manager when extra is missing (defaults to -1)
        verify(mockWifiDirectManager, never()).onWifiDirectStateChanged(anyBoolean())
    }
    
    @Test
    fun testPeersChanged() {
        val intent = Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        verify(mockWifiDirectManager).requestPeers()
    }
    
    @Test
    fun testConnectionChanged_Connected() {
        val mockNetworkInfo = mock(NetworkInfo::class.java)
        `when`(mockNetworkInfo.isConnected).thenReturn(true)
        
        val intent = Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
            putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, mockNetworkInfo)
        }
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        verify(mockWifiDirectManager).requestConnectionInfo()
        verify(mockWifiDirectManager).requestGroupInfo()
    }
    
    @Test
    fun testConnectionChanged_Disconnected() {
        val mockNetworkInfo = mock(NetworkInfo::class.java)
        `when`(mockNetworkInfo.isConnected).thenReturn(false)
        `when`(mockNetworkInfo.isConnectedOrConnecting).thenReturn(true)
        
        val intent = Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
            putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, mockNetworkInfo)
        }
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        // Should not request connection info for disconnected state
        verify(mockWifiDirectManager, never()).requestConnectionInfo()
        verify(mockWifiDirectManager, never()).requestGroupInfo()
    }
    
    @Test
    fun testConnectionChanged_NoNetworkInfo() {
        val intent = Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        // No EXTRA_NETWORK_INFO added
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        // Should not call manager methods when network info is missing
        verify(mockWifiDirectManager, never()).requestConnectionInfo()
        verify(mockWifiDirectManager, never()).requestGroupInfo()
    }
    
    @Test
    fun testThisDeviceChanged_WithDevice() {
        val mockDevice = mock(WifiP2pDevice::class.java)
        `when`(mockDevice.deviceName).thenReturn("Test Device")
        `when`(mockDevice.deviceAddress).thenReturn("aa:bb:cc:dd:ee:ff")
        `when`(mockDevice.status).thenReturn(WifiP2pDevice.AVAILABLE)
        
        val intent = Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION).apply {
            putExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, mockDevice)
        }
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        // This action is primarily for logging, so no manager methods should be called
        verifyNoInteractions(mockWifiDirectManager)
    }
    
    @Test
    fun testThisDeviceChanged_NoDevice() {
        val intent = Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        // No EXTRA_WIFI_P2P_DEVICE added
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        // Should handle missing device gracefully
        verifyNoInteractions(mockWifiDirectManager)
    }
    
    @Test
    fun testUnknownAction() {
        val intent = Intent("com.unknown.action")
        
        wifiDirectReceiver.onReceive(mockContext, intent)
        
        // Should not call any manager methods for unknown actions
        verifyNoInteractions(mockWifiDirectManager)
    }
    
    @Test
    fun testMultipleActions() {
        // Test handling multiple different actions in sequence
        
        // State change
        val stateIntent = Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).apply {
            putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        }
        wifiDirectReceiver.onReceive(mockContext, stateIntent)
        
        // Peers changed
        val peersIntent = Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        wifiDirectReceiver.onReceive(mockContext, peersIntent)
        
        // Connection changed
        val mockNetworkInfo = mock(NetworkInfo::class.java)
        `when`(mockNetworkInfo.isConnected).thenReturn(true)
        val connectionIntent = Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
            putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, mockNetworkInfo)
        }
        wifiDirectReceiver.onReceive(mockContext, connectionIntent)
        
        // Verify all appropriate methods were called
        verify(mockWifiDirectManager).onWifiDirectStateChanged(true)
        verify(mockWifiDirectManager).requestPeers()
        verify(mockWifiDirectManager).requestConnectionInfo()
        verify(mockWifiDirectManager).requestGroupInfo()
    }
    
    @Test
    fun testDeviceStatusMapping() {
        // Test that all known device status codes can be handled
        val statusCodes = listOf(
            WifiP2pDevice.AVAILABLE,
            WifiP2pDevice.INVITED,
            WifiP2pDevice.CONNECTED,
            WifiP2pDevice.FAILED,
            WifiP2pDevice.UNAVAILABLE
        )
        
        statusCodes.forEach { status ->
            val mockDevice = mock(WifiP2pDevice::class.java)
            `when`(mockDevice.deviceName).thenReturn("Test Device")
            `when`(mockDevice.deviceAddress).thenReturn("aa:bb:cc:dd:ee:ff")
            `when`(mockDevice.status).thenReturn(status)
            
            val intent = Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION).apply {
                putExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, mockDevice)
            }
            
            // Should handle all status codes without exception
            wifiDirectReceiver.onReceive(mockContext, intent)
        }
        
        // No manager interactions expected for device changed events
        verifyNoInteractions(mockWifiDirectManager)
    }
}
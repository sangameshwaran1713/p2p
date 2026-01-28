package com.p2pshare.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralized permission management for the P2P File Share application.
 * 
 * This class handles:
 * - Runtime permission checking for all required permissions
 * - Permission request coordination
 * - Permission status tracking
 * - Android version-specific permission handling
 */
class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionManager"
    }

    /**
     * Permission categories for organized handling.
     */
    enum class PermissionCategory {
        CAMERA,
        STORAGE,
        LOCATION,
        WIFI,
        NOTIFICATIONS
    }

    /**
     * Permission request result.
     */
    data class PermissionResult(
        val granted: List<String>,
        val denied: List<String>,
        val permanentlyDenied: List<String>
    ) {
        val allGranted: Boolean get() = denied.isEmpty() && permanentlyDenied.isEmpty()
        val hasPermissionsDenied: Boolean get() = denied.isNotEmpty() || permanentlyDenied.isNotEmpty()
        val hasPermanentlyDenied: Boolean get() = permanentlyDenied.isNotEmpty()
    }

    /**
     * Gets all required permissions based on Android version.
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                // Camera permissions
                Manifest.permission.CAMERA,
                
                // Location permissions (required for Wi-Fi Direct)
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                
                // Wi-Fi permissions
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                
                // Storage permissions (Android 13+)
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                
                // Notification permissions (Android 13+)
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                // Camera permissions
                Manifest.permission.CAMERA,
                
                // Location permissions (required for Wi-Fi Direct)
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                
                // Wi-Fi permissions
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                
                // Storage permissions (Android 12 and below)
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    /**
     * Gets permissions by category.
     */
    fun getPermissionsByCategory(category: PermissionCategory): Array<String> {
        return when (category) {
            PermissionCategory.CAMERA -> arrayOf(
                Manifest.permission.CAMERA
            )
            
            PermissionCategory.STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                    )
                } else {
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                }
            }
            
            PermissionCategory.LOCATION -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            
            PermissionCategory.WIFI -> arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
            
            PermissionCategory.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyArray()
                }
            }
        }
    }

    /**
     * Checks if all required permissions are granted.
     */
    fun areAllPermissionsGranted(): Boolean {
        return getRequiredPermissions().all { permission ->
            isPermissionGranted(permission)
        }
    }

    /**
     * Checks if a specific permission is granted.
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if permissions in a category are granted.
     */
    fun areCategoryPermissionsGranted(category: PermissionCategory): Boolean {
        return getPermissionsByCategory(category).all { permission ->
            isPermissionGranted(permission)
        }
    }

    /**
     * Gets missing permissions from all required permissions.
     */
    fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { permission ->
            !isPermissionGranted(permission)
        }
    }

    /**
     * Gets missing permissions for a specific category.
     */
    fun getMissingPermissions(category: PermissionCategory): List<String> {
        return getPermissionsByCategory(category).filter { permission ->
            !isPermissionGranted(permission)
        }
    }

    /**
     * Gets critical permissions that are absolutely required for core functionality.
     */
    fun getCriticalPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, // Required for Wi-Fi Direct
            Manifest.permission.CAMERA // Required for QR scanning
        )
    }

    /**
     * Checks if critical permissions are granted.
     */
    fun areCriticalPermissionsGranted(): Boolean {
        return getCriticalPermissions().all { permission ->
            isPermissionGranted(permission)
        }
    }

    /**
     * Gets user-friendly permission names for display.
     */
    fun getPermissionDisplayName(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> "Camera"
            Manifest.permission.ACCESS_FINE_LOCATION -> "Location (Precise)"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Location (Approximate)"
            Manifest.permission.ACCESS_WIFI_STATE -> "Wi-Fi State"
            Manifest.permission.CHANGE_WIFI_STATE -> "Wi-Fi Control"
            Manifest.permission.READ_EXTERNAL_STORAGE -> "Storage (Read)"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Storage (Write)"
            Manifest.permission.READ_MEDIA_IMAGES -> "Photos and Media"
            Manifest.permission.READ_MEDIA_VIDEO -> "Videos"
            Manifest.permission.READ_MEDIA_AUDIO -> "Audio Files"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
            else -> permission.substringAfterLast('.')
        }
    }

    /**
     * Gets user-friendly explanation for why a permission is needed.
     */
    fun getPermissionExplanation(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> 
                "Camera access is needed to scan QR codes for connecting to other devices."
            
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION -> 
                "Location access is required for Wi-Fi Direct functionality to discover and connect to nearby devices."
            
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE -> 
                "Wi-Fi access is needed to create direct connections between devices for file sharing."
            
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> 
                "Storage access is needed to read files for sending and save received files."
            
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO -> 
                "Media access is needed to select and share your photos, videos, and audio files."
            
            Manifest.permission.POST_NOTIFICATIONS -> 
                "Notification access is needed to show transfer progress and completion status."
            
            else -> "This permission is required for the app to function properly."
        }
    }

    /**
     * Gets category explanation for permission groups.
     */
    fun getCategoryExplanation(category: PermissionCategory): String {
        return when (category) {
            PermissionCategory.CAMERA -> 
                "Camera access is essential for scanning QR codes to connect with other devices securely."
            
            PermissionCategory.LOCATION -> 
                "Location access is required by Android for Wi-Fi Direct functionality. This allows the app to discover and connect to nearby devices without using your actual location data."
            
            PermissionCategory.STORAGE -> 
                "Storage access is needed to select files for sharing and save received files to your device."
            
            PermissionCategory.WIFI -> 
                "Wi-Fi access is required to create direct peer-to-peer connections for secure file sharing."
            
            PermissionCategory.NOTIFICATIONS -> 
                "Notification access allows the app to show transfer progress and notify you when transfers complete."
        }
    }

    /**
     * Checks if the app can request a permission (not permanently denied).
     */
    fun canRequestPermission(permission: String): Boolean {
        // This would typically be used with Activity.shouldShowRequestPermissionRationale()
        // Since we don't have activity context here, we return true
        // The calling activity should handle the rationale check
        return true
    }

    /**
     * Gets permissions that are essential for core app functionality.
     */
    fun getEssentialPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        )
    }

    /**
     * Gets permissions that enhance functionality but aren't critical.
     */
    fun getOptionalPermissions(): Array<String> {
        val allPermissions = getRequiredPermissions().toSet()
        val essentialPermissions = getEssentialPermissions().toSet()
        return (allPermissions - essentialPermissions).toTypedArray()
    }

    /**
     * Creates a permission result from granted/denied lists.
     */
    fun createPermissionResult(
        requestedPermissions: Array<String>,
        grantResults: IntArray,
        permanentlyDeniedPermissions: List<String> = emptyList()
    ): PermissionResult {
        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()

        requestedPermissions.forEachIndexed { index, permission ->
            if (index < grantResults.size) {
                if (grantResults[index] == PackageManager.PERMISSION_GRANTED) {
                    granted.add(permission)
                } else {
                    denied.add(permission)
                }
            }
        }

        // Remove permanently denied from regular denied list
        val regularDenied = denied.filter { it !in permanentlyDeniedPermissions }
        
        return PermissionResult(
            granted = granted,
            denied = regularDenied,
            permanentlyDenied = permanentlyDeniedPermissions
        )
    }

    /**
     * Gets a summary of current permission status.
     */
    fun getPermissionSummary(): Map<PermissionCategory, Boolean> {
        return mapOf(
            PermissionCategory.CAMERA to areCategoryPermissionsGranted(PermissionCategory.CAMERA),
            PermissionCategory.LOCATION to areCategoryPermissionsGranted(PermissionCategory.LOCATION),
            PermissionCategory.STORAGE to areCategoryPermissionsGranted(PermissionCategory.STORAGE),
            PermissionCategory.WIFI to areCategoryPermissionsGranted(PermissionCategory.WIFI),
            PermissionCategory.NOTIFICATIONS to areCategoryPermissionsGranted(PermissionCategory.NOTIFICATIONS)
        )
    }
}
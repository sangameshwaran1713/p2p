package com.p2pshare.app.permissions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity

/**
 * Permission handler utility for managing runtime permissions in activities.
 * 
 * This class provides:
 * - Easy permission request handling
 * - Permission rationale dialogs
 * - Settings navigation for permanently denied permissions
 * - Callback-based permission results
 */
class PermissionHandler(
    private val activity: FragmentActivity,
    private val permissionManager: PermissionManager
) {

    /**
     * Callback interface for permission results.
     */
    interface PermissionCallback {
        fun onPermissionsGranted(permissions: List<String>)
        fun onPermissionsDenied(permissions: List<String>)
        fun onPermissionsPermanentlyDenied(permissions: List<String>)
    }

    private var currentCallback: PermissionCallback? = null
    private var requestedPermissions: Array<String> = emptyArray()

    // Permission launcher
    private val permissionLauncher: ActivityResultLauncher<Array<String>> = 
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            handlePermissionResults(permissions)
        }

    /**
     * Requests permissions with callback handling.
     */
    fun requestPermissions(
        permissions: Array<String>,
        callback: PermissionCallback
    ) {
        currentCallback = callback
        requestedPermissions = permissions

        val missingPermissions = permissions.filter { permission ->
            !permissionManager.isPermissionGranted(permission)
        }

        if (missingPermissions.isEmpty()) {
            // All permissions already granted
            callback.onPermissionsGranted(permissions.toList())
            return
        }

        // Check if we should show rationale for any permissions
        val permissionsNeedingRationale = missingPermissions.filter { permission ->
            activity.shouldShowRequestPermissionRationale(permission)
        }

        if (permissionsNeedingRationale.isNotEmpty()) {
            showPermissionRationaleDialog(missingPermissions.toTypedArray())
        } else {
            // Request permissions directly
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * Requests all required permissions.
     */
    fun requestAllRequiredPermissions(callback: PermissionCallback) {
        requestPermissions(permissionManager.getRequiredPermissions(), callback)
    }

    /**
     * Requests permissions for a specific category.
     */
    fun requestCategoryPermissions(
        category: PermissionManager.PermissionCategory,
        callback: PermissionCallback
    ) {
        requestPermissions(permissionManager.getPermissionsByCategory(category), callback)
    }

    /**
     * Requests only critical permissions.
     */
    fun requestCriticalPermissions(callback: PermissionCallback) {
        requestPermissions(permissionManager.getCriticalPermissions(), callback)
    }

    /**
     * Shows permission rationale dialog before requesting permissions.
     */
    private fun showPermissionRationaleDialog(permissions: Array<String>) {
        val message = buildRationaleMessage(permissions)

        AlertDialog.Builder(activity)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Grant Permissions") { _, _ ->
                permissionLauncher.launch(permissions)
            }
            .setNegativeButton("Cancel") { _, _ ->
                currentCallback?.onPermissionsDenied(permissions.toList())
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Builds rationale message for requested permissions.
     */
    private fun buildRationaleMessage(permissions: Array<String>): String {
        return buildString {
            append("This app needs the following permissions to function properly:\n\n")
            
            permissions.forEach { permission ->
                val displayName = permissionManager.getPermissionDisplayName(permission)
                val explanation = permissionManager.getPermissionExplanation(permission)
                append("• $displayName: $explanation\n\n")
            }
            
            append("Please grant these permissions to continue.")
        }
    }

    /**
     * Handles permission request results.
     */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        val callback = currentCallback ?: return

        val granted = permissions.filterValues { it }.keys.toList()
        val denied = permissions.filterValues { !it }.keys.toList()

        // Check for permanently denied permissions
        val permanentlyDenied = denied.filter { permission ->
            !activity.shouldShowRequestPermissionRationale(permission)
        }
        val regularDenied = denied - permanentlyDenied.toSet()

        // Notify callback
        if (granted.isNotEmpty()) {
            callback.onPermissionsGranted(granted)
        }
        
        if (regularDenied.isNotEmpty()) {
            callback.onPermissionsDenied(regularDenied)
        }
        
        if (permanentlyDenied.isNotEmpty()) {
            callback.onPermissionsPermanentlyDenied(permanentlyDenied)
            showPermanentlyDeniedDialog(permanentlyDenied)
        }
    }

    /**
     * Shows dialog for permanently denied permissions.
     */
    private fun showPermanentlyDeniedDialog(permissions: List<String>) {
        val message = buildString {
            append("Some permissions have been permanently denied:\n\n")
            
            permissions.forEach { permission ->
                val displayName = permissionManager.getPermissionDisplayName(permission)
                append("• $displayName\n")
            }
            
            append("\nTo enable these permissions, please go to Settings > Apps > ${activity.packageManager.getApplicationLabel(activity.applicationInfo)} > Permissions and enable the required permissions.")
        }

        AlertDialog.Builder(activity)
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
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    /**
     * Shows permission explanation dialog for a specific category.
     */
    fun showCategoryExplanationDialog(
        category: PermissionManager.PermissionCategory,
        onProceed: () -> Unit
    ) {
        val title = when (category) {
            PermissionManager.PermissionCategory.CAMERA -> "Camera Permission"
            PermissionManager.PermissionCategory.LOCATION -> "Location Permission"
            PermissionManager.PermissionCategory.STORAGE -> "Storage Permission"
            PermissionManager.PermissionCategory.WIFI -> "Wi-Fi Permission"
            PermissionManager.PermissionCategory.NOTIFICATIONS -> "Notification Permission"
        }

        val explanation = permissionManager.getCategoryExplanation(category)

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(explanation)
            .setPositiveButton("Continue") { _, _ ->
                onProceed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Checks and requests permissions if needed, with automatic handling.
     */
    fun ensurePermissions(
        permissions: Array<String>,
        onAllGranted: () -> Unit,
        onDenied: ((List<String>) -> Unit)? = null
    ) {
        requestPermissions(permissions, object : PermissionCallback {
            override fun onPermissionsGranted(permissions: List<String>) {
                if (permissionManager.areAllPermissionsGranted()) {
                    onAllGranted()
                }
            }

            override fun onPermissionsDenied(permissions: List<String>) {
                onDenied?.invoke(permissions)
            }

            override fun onPermissionsPermanentlyDenied(permissions: List<String>) {
                onDenied?.invoke(permissions)
            }
        })
    }

    /**
     * Ensures all required permissions are granted.
     */
    fun ensureAllRequiredPermissions(
        onAllGranted: () -> Unit,
        onDenied: ((List<String>) -> Unit)? = null
    ) {
        ensurePermissions(
            permissionManager.getRequiredPermissions(),
            onAllGranted,
            onDenied
        )
    }

    /**
     * Ensures critical permissions are granted.
     */
    fun ensureCriticalPermissions(
        onAllGranted: () -> Unit,
        onDenied: ((List<String>) -> Unit)? = null
    ) {
        ensurePermissions(
            permissionManager.getCriticalPermissions(),
            onAllGranted,
            onDenied
        )
    }

    /**
     * Shows a comprehensive permission status dialog.
     */
    fun showPermissionStatusDialog() {
        val summary = permissionManager.getPermissionSummary()
        
        val message = buildString {
            append("Permission Status:\n\n")
            
            summary.forEach { (category, granted) ->
                val status = if (granted) "✓ Granted" else "✗ Not Granted"
                val categoryName = when (category) {
                    PermissionManager.PermissionCategory.CAMERA -> "Camera"
                    PermissionManager.PermissionCategory.LOCATION -> "Location"
                    PermissionManager.PermissionCategory.STORAGE -> "Storage"
                    PermissionManager.PermissionCategory.WIFI -> "Wi-Fi"
                    PermissionManager.PermissionCategory.NOTIFICATIONS -> "Notifications"
                }
                append("$categoryName: $status\n")
            }
            
            if (!permissionManager.areAllPermissionsGranted()) {
                append("\nSome permissions are missing. Grant all permissions for full functionality.")
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Permission Status")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Settings") { _, _ ->
                openAppSettings()
            }
            .show()
    }
}
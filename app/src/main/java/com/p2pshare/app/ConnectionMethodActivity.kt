package com.p2pshare.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.p2pshare.app.connection.*

/**
 * Activity for selecting connection method and viewing device compatibility.
 * 
 * This activity shows users what connection methods are available on their device
 * and allows them to choose the best method for file sharing.
 */
class ConnectionMethodActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ConnectionMethodActivity"
        const val EXTRA_SELECTED_METHOD = "selected_method"
    }
    
    // UI Components
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvRecommendation: TextView
    private lateinit var rvConnectionMethods: RecyclerView
    private lateinit var btnContinue: MaterialButton
    private lateinit var cvCompatibilityReport: MaterialCardView
    private lateinit var tvCompatibilityDetails: TextView
    
    // Components
    private lateinit var compatibilityChecker: DeviceCompatibilityChecker
    private lateinit var methodAdapter: ConnectionMethodAdapter
    
    // State
    private var compatibilityReport: DeviceCompatibilityChecker.CompatibilityReport? = null
    private var selectedMethod: ConnectionMethod = ConnectionMethod.AUTO
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_method)
        
        initializeViews()
        initializeComponents()
        setupClickListeners()
        checkCompatibility()
    }
    
    /**
     * Initializes all view references.
     */
    private fun initializeViews() {
        tvDeviceInfo = findViewById(R.id.tv_device_info)
        tvRecommendation = findViewById(R.id.tv_recommendation)
        rvConnectionMethods = findViewById(R.id.rv_connection_methods)
        btnContinue = findViewById(R.id.btn_continue)
        cvCompatibilityReport = findViewById(R.id.cv_compatibility_report)
        tvCompatibilityDetails = findViewById(R.id.tv_compatibility_details)
    }
    
    /**
     * Initializes components.
     */
    private fun initializeComponents() {
        compatibilityChecker = DeviceCompatibilityChecker(this)
        
        // Setup RecyclerView
        methodAdapter = ConnectionMethodAdapter { method ->
            selectedMethod = method
            updateUI()
        }
        
        rvConnectionMethods.apply {
            layoutManager = LinearLayoutManager(this@ConnectionMethodActivity)
            adapter = methodAdapter
        }
    }
    
    /**
     * Sets up click listeners.
     */
    private fun setupClickListeners() {
        btnContinue.setOnClickListener {
            continueWithSelectedMethod()
        }
        
        cvCompatibilityReport.setOnClickListener {
            showDetailedCompatibilityReport()
        }
        
        // Back button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    /**
     * Checks device compatibility for all connection methods.
     */
    private fun checkCompatibility() {
        // Show loading state
        tvDeviceInfo.text = "Checking device compatibility..."
        
        // Run compatibility check in background
        Thread {
            val report = compatibilityChecker.checkCompatibility()
            
            runOnUiThread {
                compatibilityReport = report
                displayCompatibilityResults(report)
            }
        }.start()
    }
    
    /**
     * Displays compatibility results.
     */
    private fun displayCompatibilityResults(report: DeviceCompatibilityChecker.CompatibilityReport) {
        // Update device info
        val deviceInfo = report.deviceInfo
        tvDeviceInfo.text = "${deviceInfo.manufacturer} ${deviceInfo.model} (Android ${deviceInfo.androidVersion})"
        
        // Update recommendation
        val recommendedMethodInfo = ConnectionMethodInfo.getMethodInfo(report.recommendedMethod)
        tvRecommendation.text = "Recommended: ${recommendedMethodInfo.displayName}\n${recommendedMethodInfo.description}"
        
        // Set default selection to recommended method
        selectedMethod = report.recommendedMethod
        
        // Update methods list
        val methodsWithStatus = ConnectionMethodInfo.getAllMethods().map { methodInfo ->
            val result = report.results[methodInfo.method]
            ConnectionMethodWithStatus(
                methodInfo = methodInfo,
                isSupported = result?.isSupported ?: false,
                isRecommended = result?.isRecommended ?: false,
                issues = result?.issues ?: emptyList(),
                requirements = result?.requirements ?: emptyList()
            )
        }
        
        methodAdapter.updateMethods(methodsWithStatus)
        
        // Update compatibility details
        updateCompatibilityDetails(report)
        
        updateUI()
    }
    
    /**
     * Updates compatibility details text.
     */
    private fun updateCompatibilityDetails(report: DeviceCompatibilityChecker.CompatibilityReport) {
        val details = StringBuilder()
        
        details.append("Device Capabilities:\n")
        details.append("• Wi-Fi Direct: ${if (report.deviceInfo.hasWifiDirect) "✓" else "✗"}\n")
        details.append("• Wi-Fi Hotspot: ${if (report.deviceInfo.hasHotspotCapability) "✓" else "✗"}\n")
        details.append("• Bluetooth: ${if (report.deviceInfo.hasBluetooth) "✓" else "✗"}\n\n")
        
        details.append("Recommended Method: ${ConnectionMethodInfo.getMethodInfo(report.recommendedMethod).displayName}\n")
        
        if (report.fallbackMethods.isNotEmpty()) {
            details.append("Fallback Methods: ${report.fallbackMethods.joinToString(", ") { 
                ConnectionMethodInfo.getMethodInfo(it).displayName 
            }}")
        }
        
        tvCompatibilityDetails.text = details.toString()
    }
    
    /**
     * Updates UI based on current state.
     */
    private fun updateUI() {
        val report = compatibilityReport ?: return
        
        // Update continue button
        val selectedMethodInfo = ConnectionMethodInfo.getMethodInfo(selectedMethod)
        btnContinue.text = "Continue with ${selectedMethodInfo.displayName}"
        btnContinue.isEnabled = true
        
        // Update adapter selection
        methodAdapter.setSelectedMethod(selectedMethod)
    }
    
    /**
     * Continues with the selected connection method.
     */
    private fun continueWithSelectedMethod() {
        // Show a confirmation dialog with method details
        val methodInfo = ConnectionMethodInfo.getMethodInfo(selectedMethod)
        val report = compatibilityReport
        
        val message = buildString {
            append("Selected Method: ${methodInfo.displayName}\n")
            append("Description: ${methodInfo.description}\n")
            append("Speed: ${methodInfo.estimatedSpeed}\n\n")
            
            if (report != null) {
                val result = report.results[selectedMethod]
                if (result != null) {
                    append("Status: ${if (result.isSupported) "Supported" else "Not Supported"}\n")
                    if (result.issues.isNotEmpty()) {
                        append("Issues: ${result.issues.joinToString(", ")}\n")
                    }
                    if (result.requirements.isNotEmpty()) {
                        append("Requirements: ${result.requirements.joinToString(", ")}\n")
                    }
                }
            }
            
            append("\nProceed with this method?")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Confirm Connection Method")
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ ->
                val action = intent.getStringExtra("action") ?: "send"
                val intent = Intent().apply {
                    putExtra(EXTRA_SELECTED_METHOD, selectedMethod.name)
                    putExtra("action", action)
                }
                setResult(RESULT_OK, intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Shows detailed compatibility report dialog.
     */
    private fun showDetailedCompatibilityReport() {
        val report = compatibilityReport ?: return
        
        val details = StringBuilder()
        
        report.results.forEach { (method, result) ->
            val methodInfo = ConnectionMethodInfo.getMethodInfo(method)
            details.append("${methodInfo.displayName}:\n")
            details.append("  Supported: ${if (result.isSupported) "Yes" else "No"}\n")
            details.append("  Recommended: ${if (result.isRecommended) "Yes" else "No"}\n")
            
            if (result.issues.isNotEmpty()) {
                details.append("  Issues:\n")
                result.issues.forEach { issue ->
                    details.append("    • $issue\n")
                }
            }
            
            if (result.requirements.isNotEmpty()) {
                details.append("  Requirements:\n")
                result.requirements.forEach { requirement ->
                    details.append("    • $requirement\n")
                }
            }
            
            details.append("\n")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Detailed Compatibility Report")
            .setMessage(details.toString())
            .setPositiveButton("OK", null)
            .show()
    }
    
    /**
     * Data class for connection method with status.
     */
    data class ConnectionMethodWithStatus(
        val methodInfo: ConnectionMethodInfo,
        val isSupported: Boolean,
        val isRecommended: Boolean,
        val issues: List<String>,
        val requirements: List<String>
    )
    
    /**
     * Adapter for connection methods RecyclerView.
     */
    private class ConnectionMethodAdapter(
        private val onMethodSelected: (ConnectionMethod) -> Unit
    ) : RecyclerView.Adapter<ConnectionMethodAdapter.ViewHolder>() {
        
        private var methods = listOf<ConnectionMethodWithStatus>()
        private var selectedMethod: ConnectionMethod = ConnectionMethod.AUTO
        
        fun updateMethods(newMethods: List<ConnectionMethodWithStatus>) {
            methods = newMethods
            notifyDataSetChanged()
        }
        
        fun setSelectedMethod(method: ConnectionMethod) {
            selectedMethod = method
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_connection_method, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(methods[position], selectedMethod == methods[position].methodInfo.method)
        }
        
        override fun getItemCount(): Int = methods.size
        
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cvMethod: MaterialCardView = itemView.findViewById(R.id.cv_method)
            private val tvMethodName: TextView = itemView.findViewById(R.id.tv_method_name)
            private val tvMethodDescription: TextView = itemView.findViewById(R.id.tv_method_description)
            private val tvMethodSpeed: TextView = itemView.findViewById(R.id.tv_method_speed)
            private val tvMethodCompatibility: TextView = itemView.findViewById(R.id.tv_method_compatibility)
            private val tvMethodStatus: TextView = itemView.findViewById(R.id.tv_method_status)
            
            fun bind(methodWithStatus: ConnectionMethodWithStatus, isSelected: Boolean) {
                val method = methodWithStatus.methodInfo
                
                tvMethodName.text = method.displayName
                tvMethodDescription.text = method.description
                tvMethodSpeed.text = "Speed: ${"★".repeat(method.speedRating)}${"☆".repeat(5 - method.speedRating)} (${method.estimatedSpeed})"
                tvMethodCompatibility.text = "Compatibility: ${"★".repeat(method.compatibilityRating)}${"☆".repeat(5 - method.compatibilityRating)}"
                
                // Update status
                val status = when {
                    !methodWithStatus.isSupported -> "❌ Not Supported"
                    methodWithStatus.isRecommended -> "✅ Recommended"
                    methodWithStatus.requirements.isNotEmpty() -> "⚠️ Needs Setup"
                    else -> "✅ Available"
                }
                tvMethodStatus.text = status
                
                // Update selection state
                cvMethod.isChecked = isSelected
                cvMethod.strokeWidth = if (isSelected) 4 else 1
                
                // Set click listener
                cvMethod.setOnClickListener {
                    if (methodWithStatus.isSupported) {
                        onMethodSelected(method.method)
                    }
                }
                
                // Disable if not supported
                cvMethod.alpha = if (methodWithStatus.isSupported) 1.0f else 0.5f
                cvMethod.isClickable = methodWithStatus.isSupported
            }
        }
    }
}
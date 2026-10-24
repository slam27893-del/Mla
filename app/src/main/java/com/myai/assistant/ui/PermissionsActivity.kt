package com.myai.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.myai.assistant.MyAiApplication
import com.myai.assistant.R
import com.myai.assistant.accessibility.AccessibilityBridge
import com.myai.assistant.databinding.ActivityPermissionsBinding
import com.myai.assistant.databinding.ItemPermissionBinding

class PermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsBinding
    private val container by lazy { (application as MyAiApplication).container }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshRows() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnAppDetails.setOnClickListener { openAppDetails() }
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    private fun refreshRows() {
        binding.permissionContainer.removeAllViews()
        rows().forEach { row ->
            val rowBinding = ItemPermissionBinding.inflate(
                layoutInflater,
                binding.permissionContainer,
                true
            )
            rowBinding.permissionTitle.text = getString(row.title)
            rowBinding.permissionDescription.text = getString(row.description)
            rowBinding.permissionStatus.text = getString(row.status)
            rowBinding.permissionStatus.setTextColor(ContextCompat.getColor(this, row.statusColor))
            rowBinding.permissionAction.setText(getString(row.actionLabel))
            rowBinding.permissionAction.setOnClickListener { row.onClick() }
        }
    }

    private fun rows(): List<Row> {
        val overlay = PermissionUtils.isOverlayGranted(this)
        val notifications = PermissionUtils.anyNotificationsEnabled(this)
        val accessibility = PermissionUtils.isAccessibilityServiceEnabled(this)
        val powerManager = getSystemService(PowerManager::class.java)
        val batteryFree = powerManager?.isIgnoringBatteryOptimizations(packageName) == true

        val accessibilityStatus = when {
            accessibility && AccessibilityBridge.isConnected -> R.string.perm_accessibility_enabled_note
            accessibility -> R.string.perm_accessibility_enabled_not_connected
            else -> R.string.perm_status_missing
        }

        return listOf(
            Row(
                title = R.string.perm_overlay_title,
                description = R.string.perm_overlay_desc,
                status = if (overlay) R.string.perm_status_granted else R.string.perm_status_missing,
                statusColor = if (overlay) R.color.success else R.color.warning,
                actionLabel = R.string.perm_action_open_settings,
                onClick = {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            ),
            Row(
                title = R.string.perm_notifications_title,
                description = R.string.perm_notifications_desc,
                status = if (notifications) R.string.perm_status_granted else R.string.perm_status_missing,
                statusColor = if (notifications) R.color.success else R.color.warning,
                actionLabel = R.string.perm_action_open_settings,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !notifications) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings()
                    }
                }
            ),
            Row(
                title = R.string.perm_accessibility_title,
                description = R.string.perm_accessibility_desc,
                status = accessibilityStatus,
                statusColor = if (accessibility) R.color.success else R.color.muted,
                actionLabel = R.string.perm_action_open_settings,
                onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            ),
            Row(
                title = R.string.perm_battery_title,
                description = R.string.perm_battery_desc,
                status = if (batteryFree) R.string.perm_status_granted else R.string.perm_status_missing,
                statusColor = if (batteryFree) R.color.success else R.color.warning,
                actionLabel = R.string.perm_action_open_settings,
                onClick = { requestBatteryExemption() }
            ),
            Row(
                title = R.string.perm_app_settings_title,
                description = R.string.perm_app_settings_desc,
                status = R.string.perm_action_open_settings,
                statusColor = R.color.muted,
                actionLabel = R.string.common_open,
                onClick = { openAppDetails() }
            )
        )
    }

    private fun openAppDetails() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun requestBatteryExemption() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        val opened = runCatching { startActivity(direct) }.isSuccess
        if (!opened) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private data class Row(
        @StringRes val title: Int,
        @StringRes val description: Int,
        @StringRes val status: Int,
        @ColorRes val statusColor: Int,
        @StringRes val actionLabel: Int,
        val onClick: () -> Unit
    )
}

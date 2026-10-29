package com.igscrollguard.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.igscrollguard.app.utils.AccessibilityUtil
import com.igscrollguard.app.utils.PrefsManager

class ScrollGuardTileService : TileService() {

    private lateinit var prefsManager: PrefsManager

    override fun onCreate() {
        super.onCreate()
        prefsManager = PrefsManager.getInstance(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val isAccessibilityEnabled = AccessibilityUtil.isAccessibilityServiceEnabled(this)
        val isEn = prefsManager.isEnglish

        if (!isAccessibilityEnabled) {
            val msg = if (isEn) {
                "Please enable \"IG Scroll Guard\" in Accessibility Settings."
            } else {
                getString(R.string.toast_open_accessibility)
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            openAccessibilitySettingsFromTile()
            return
        }

        // Toggle between Active and Paused
        if (prefsManager.isAnyFeatureEnabled) {
            prefsManager.isAntiThrowEnabled = false
            prefsManager.isAutoSwipeEnabled = false
            prefsManager.isAutoSkipLiveEnabled = false
            val msg = if (isEn) "⏸️ IG Scroll Guard: PAUSED" else "⏸️ IG Scroll Guard: DIJEDA"
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        } else {
            prefsManager.isAntiThrowEnabled = true
            prefsManager.isAutoSwipeEnabled = true
            val msg = if (isEn) "🛡️ IG Scroll Guard: ACTIVE" else "🛡️ IG Scroll Guard: AKTIF"
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }

        refreshTileState()
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val isAccessibilityEnabled = AccessibilityUtil.isAccessibilityServiceEnabled(this)
        val isGuardActive = isAccessibilityEnabled && prefsManager.isAnyFeatureEnabled
        val isEn = prefsManager.isEnglish

        tile.state = if (isGuardActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !isAccessibilityEnabled -> if (isEn) "Permission Off" else "Izin Nonaktif"
                isGuardActive -> if (isEn) "Active" else "Aktif"
                else -> if (isEn) "Paused" else "Dijeda"
            }
        }

        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openAccessibilitySettingsFromTile() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            startActivity(intent)
        }
    }
}

package com.igscrollguard.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.igscrollguard.app.databinding.ActivityMainBinding
import com.igscrollguard.app.utils.AccessibilityUtil
import com.igscrollguard.app.utils.PrefsManager
import com.igscrollguard.app.utils.XiaomiIntentHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager.getInstance(this)

        setupUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        applyLanguageStrings()
        updateServiceStatusUI()
        updateStatsUI()
        requestTileSync()
    }

    private fun setupUI() {
        applyLanguageStrings()
        updateServiceStatusUI()
        updateStatsUI()
    }

    private fun setupListeners() {
        // Language Toggle Button (ID <-> EN)
        binding.btnLanguageToggle.setOnClickListener {
            val nextLang = if (prefsManager.isEnglish) PrefsManager.LANG_ID else PrefsManager.LANG_EN
            prefsManager.appLanguage = nextLang
            applyLanguageStrings()
            updateServiceStatusUI()
            updateStatsUI()
            requestTileSync()

            val msg = if (prefsManager.isEnglish) {
                "🇬🇧 Language & Ad Rules set to English"
            } else {
                "🇮🇩 Bahasa & Aturan Iklan diubah ke Indonesia"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Master OS Accessibility Settings Bar
        binding.btnMasterAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // Independent Switch 1: Anti-Throw / State Memory
        binding.switchAntiThrow.setOnCheckedChangeListener { _, isChecked ->
            handleAntiThrowToggle(isChecked)
        }

        binding.cardAntiThrow.setOnClickListener {
            binding.switchAntiThrow.isChecked = !binding.switchAntiThrow.isChecked
        }

        // Independent Switch 2: Auto-Swipe Ads (Feed, Reels & Story)
        binding.switchAutoSwipe.setOnCheckedChangeListener { _, isChecked ->
            handleAutoSwipeToggle(isChecked)
        }

        binding.cardAutoSwipe.setOnClickListener {
            binding.switchAutoSwipe.isChecked = !binding.switchAutoSwipe.isChecked
        }

        // Independent Switch 3: Auto-Skip Instagram Live
        binding.switchAutoSkipLive.setOnCheckedChangeListener { _, isChecked ->
            handleAutoSkipLiveToggle(isChecked)
        }

        binding.cardAutoSkipLive.setOnClickListener {
            binding.switchAutoSkipLive.isChecked = !binding.switchAutoSkipLive.isChecked
        }

        // Reset Stats button
        binding.btnResetStats.setOnClickListener {
            prefsManager.resetStats()
            updateStatsUI()
            val msg = if (prefsManager.isEnglish) {
                "State Memory statistics reset"
            } else {
                "Statistik State Memory direset"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Xiaomi Optimization Buttons
        binding.btnAutostart.setOnClickListener {
            XiaomiIntentHelper.openAppDetailsAndAutostart(this)
        }

        binding.btnDefaultApps.setOnClickListener {
            XiaomiIntentHelper.openDefaultAppsSettings(this)
        }

        binding.btnManageLinks.setOnClickListener {
            XiaomiIntentHelper.openManageDomainUrls(this)
        }
    }

    private fun handleAntiThrowToggle(isChecked: Boolean) {
        prefsManager.isAntiThrowEnabled = isChecked
        if (isChecked) {
            ensureAccessibilityPermission()
        }
        updateServiceStatusUI()
        requestTileSync()
    }

    private fun handleAutoSwipeToggle(isChecked: Boolean) {
        prefsManager.isAutoSwipeEnabled = isChecked
        if (isChecked) {
            ensureAccessibilityPermission()
        }
        updateServiceStatusUI()
        requestTileSync()
    }

    private fun handleAutoSkipLiveToggle(isChecked: Boolean) {
        prefsManager.isAutoSkipLiveEnabled = isChecked
        if (isChecked) {
            ensureAccessibilityPermission()
        }
        updateServiceStatusUI()
        requestTileSync()
    }

    private fun ensureAccessibilityPermission() {
        if (!AccessibilityUtil.isAccessibilityServiceEnabled(this)) {
            val msg = if (prefsManager.isEnglish) {
                "Please enable \"IG Scroll Guard\" in Accessibility Settings first."
            } else {
                getString(R.string.toast_open_accessibility)
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
        }
    }

    private fun applyLanguageStrings() {
        val isEn = prefsManager.isEnglish

        binding.btnLanguageToggle.text = if (isEn) "🇬🇧 EN" else "🇮🇩 ID"
        binding.tvHeaderSubtitle.text = if (isEn) {
            "MIUI & HyperOS Optimized"
        } else {
            "Dioptimalkan untuk MIUI & HyperOS"
        }

        binding.tvSwitchAntiThrowTitle.text = if (isEn) {
            "1. Anti-Redirect Shield (State Memory)"
        } else {
            "1. Perisai Anti-Lemparan (State Memory)"
        }
        binding.tvSwitchAntiThrowDesc.text = if (isEn) {
            "Blocks ad redirects to Shopee/Tokopedia/Browser & auto-closes IG In-App Browser. Turn off if you only want Auto-Swipe."
        } else {
            "Menahan lemparan iklan ke Shopee/Tokopedia/Browser & menutup In-App Browser otomatis. Matikan jika hanya ingin Auto-Swipe."
        }

        binding.tvSwitchAutoSwipeTitle.text = if (isEn) {
            "2. Auto-Swipe Ads (Feed, Reels & Story)"
        } else {
            "2. Mode Auto-Swipe Iklan (Feed, Reels & Story)"
        }
        binding.tvSwitchAutoSwipeDesc.text = if (isEn) {
            "Swipes up on Feed/Reels ads & swipes sideways on Insta-Story ads. Works independently even if Anti-Redirect is off."
        } else {
            "Swipe ke atas otomatis di Feed/Reels & Swipe ke samping otomatis di Insta-Story. Tetap berjalan walau Anti-Lemparan mati."
        }

        binding.tvSwitchAutoSkipLiveTitle.text = if (isEn) {
            "3. Auto-Skip Instagram Live (Optional)"
        } else {
            "3. Mode Auto-Skip Live Instagram (Opsional)"
        }
        binding.tvSwitchAutoSkipLiveDesc.text = if (isEn) {
            "Automatically skips Instagram Live streams when encountered in Stories or Reels/Feed."
        } else {
            "Otomatis melewati Siaran Langsung (Live) Instagram saat muncul di tengah Insta-Story maupun Reels/Feed."
        }

        binding.tvMonitorTitle.text = if (isEn) "STATE MEMORY MONITOR" else "MONITOR STATE MEMORY"
        binding.tvSwipedLabel.text = if (isEn) "Ads / Live Swiped" else "Iklan / Live Di-Swipe"
        binding.tvBlockedLabel.text = if (isEn) "Redirects Blocked" else "Lemparan Ditahan"

        binding.tvSectionXiaomiTitle.text = if (isEn) {
            "REQUIRED XIAOMI SETTINGS (MIUI/HyperOS)"
        } else {
            getString(R.string.section_xiaomi_title)
        }
        binding.tvSectionXiaomiDesc.text = if (isEn) {
            "Required so MIUI/HyperOS does not kill the accessibility service in the background:"
        } else {
            getString(R.string.section_xiaomi_desc)
        }

        binding.tvBtnAutostartTitle.text = if (isEn) {
            "1. Lock Xiaomi Autostart & Battery"
        } else {
            getString(R.string.btn_autostart_title)
        }
        binding.tvBtnAutostartSub.text = if (isEn) {
            "Enable Autostart and set Battery Saver to \"No restrictions\"."
        } else {
            getString(R.string.btn_autostart_subtitle)
        }

        binding.tvBtnDefaultAppsTitle.text = if (isEn) {
            "2. Manage Default Browser & Links"
        } else {
            getString(R.string.btn_default_apps_title)
        }
        binding.tvBtnDefaultAppsSub.text = if (isEn) {
            "Configure default apps for opening web links."
        } else {
            getString(R.string.btn_default_apps_subtitle)
        }

        binding.tvBtnManageLinksTitle.text = if (isEn) {
            "3. Disable E-Commerce Deep Links"
        } else {
            getString(R.string.btn_manage_links_title)
        }
        binding.tvBtnManageLinksSub.text = if (isEn) {
            "Turn off \"Open supported links\" on Shopee / Tokopedia."
        } else {
            getString(R.string.btn_manage_links_subtitle)
        }
    }

    private fun updateServiceStatusUI() {
        val isServiceEnabledInOs = AccessibilityUtil.isAccessibilityServiceEnabled(this)
        val antiThrow = prefsManager.isAntiThrowEnabled
        val autoSwipe = prefsManager.isAutoSwipeEnabled
        val autoSkipLive = prefsManager.isAutoSkipLiveEnabled
        val isGuardActive = isServiceEnabledInOs && (antiThrow || autoSwipe || autoSkipLive)
        val isEn = prefsManager.isEnglish

        // Bind switch states without triggering listeners
        binding.switchAntiThrow.setOnCheckedChangeListener(null)
        binding.switchAutoSwipe.setOnCheckedChangeListener(null)
        binding.switchAutoSkipLive.setOnCheckedChangeListener(null)

        binding.switchAntiThrow.isChecked = antiThrow
        binding.switchAutoSwipe.isChecked = autoSwipe
        binding.switchAutoSkipLive.isChecked = autoSkipLive

        binding.switchAntiThrow.setOnCheckedChangeListener { _, isChecked ->
            handleAntiThrowToggle(isChecked)
        }
        binding.switchAutoSwipe.setOnCheckedChangeListener { _, isChecked ->
            handleAutoSwipeToggle(isChecked)
        }
        binding.switchAutoSkipLive.setOnCheckedChangeListener { _, isChecked ->
            handleAutoSkipLiveToggle(isChecked)
        }

        // Update Master OS Accessibility Bar
        if (isServiceEnabledInOs) {
            binding.tvMasterServiceStatus.text = if (isEn) {
                "✅ OS Accessibility Service: Enabled (Tap to configure)"
            } else {
                "✅ Izin Aksesibilitas OS: Aktif (Ketuk untuk atur)"
            }
        } else {
            binding.tvMasterServiceStatus.text = if (isEn) {
                "⚠️ OS Accessibility Service: OFF (Tap here to enable!)"
            } else {
                "⚠️ Izin Aksesibilitas OS: BELUM AKTIF (Ketuk di sini!)"
            }
        }

        // Update Header Status Badge
        when {
            !isServiceEnabledInOs -> {
                binding.tvStatusBadge.text = if (isEn) "OS OFF" else "IZIN MATI"
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_pill_inactive)
            }
            isGuardActive -> {
                binding.tvStatusBadge.text = if (isEn) "ACTIVE" else "AKTIF"
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_pill)
            }
            else -> {
                binding.tvStatusBadge.text = if (isEn) "PAUSED" else "DIJEDA"
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_pill_inactive)
            }
        }
    }

    private fun updateStatsUI() {
        binding.tvSwipedCount.text = prefsManager.swipedAdsCount.toString()
        binding.tvBlockedCount.text = prefsManager.blockedThrowsCount.toString()
        binding.tvLastActionLog.text = "Log: ${prefsManager.lastActionLog}"
    }

    private fun requestTileSync() {
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, ScrollGuardTileService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

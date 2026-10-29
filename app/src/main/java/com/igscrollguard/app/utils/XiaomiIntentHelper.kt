package com.igscrollguard.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.igscrollguard.app.R

object XiaomiIntentHelper {

    /**
     * 1. Opens Application Details Settings for this package on Xiaomi (MIUI/HyperOS)
     * where both "Autostart" and "Battery Saver -> No Restrictions" can be configured.
     */
    fun openAppDetailsAndAutostart(context: Context) {
        val isEn = PrefsManager.getInstance(context).isEnglish
        val msg = if (isEn) {
            "Turn on Autostart and set Battery Saver to \"No restrictions\""
        } else {
            context.getString(R.string.toast_autostart_instructions)
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 2. Opens Default Apps Settings (Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
     */
    fun openDefaultAppsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }

    /**
     * 3. Opens Domain URLs / Opening Links Settings (android.settings.MANAGE_DOMAIN_URLS)
     * with fallback to Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS
     */
    fun openManageDomainUrls(context: Context) {
        val domainIntent = Intent("android.settings.MANAGE_DOMAIN_URLS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(domainIntent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

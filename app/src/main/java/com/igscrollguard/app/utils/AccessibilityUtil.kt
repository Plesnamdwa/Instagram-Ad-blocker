package com.igscrollguard.app.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.igscrollguard.app.AdGuardAccessibilityService

object AccessibilityUtil {

    /**
     * Checks if the AdGuardAccessibilityService is currently enabled and active in Android Settings.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedPackage = context.packageName
        val expectedClass = AdGuardAccessibilityService::class.java.name

        for (service in enabledServices) {
            val resolveInfo = service.resolveInfo ?: continue
            val serviceInfo = resolveInfo.serviceInfo ?: continue
            if (serviceInfo.packageName == expectedPackage && serviceInfo.name == expectedClass) {
                return true
            }
        }
        return false
    }
}

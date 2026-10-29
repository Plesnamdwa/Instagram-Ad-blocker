package com.igscrollguard.app.utils

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Independent Toggle 1: Anti-Throw / State Memory Link & App Redirect Blocker
     */
    var isAntiThrowEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANTI_THROW, true)
        set(value) = prefs.edit().putBoolean(KEY_ANTI_THROW, value).apply()

    /**
     * Independent Toggle 2: Auto-Swipe Ads (Vertical for Feed/Reels, Horizontal for Story)
     */
    var isAutoSwipeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SWIPE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SWIPE, value).apply()

    /**
     * Independent Toggle 3: Auto-Skip Instagram Live (Story & Reels/Feed)
     */
    var isAutoSkipLiveEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP_LIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SKIP_LIVE, value).apply()

    /**
     * Language setting: "id" (Indonesian) or "en" (English)
     */
    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, LANG_ID) ?: LANG_ID
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    val isEnglish: Boolean
        get() = appLanguage == LANG_EN

    /**
     * True if at least one protection/skip mode is enabled by the user
     */
    val isAnyFeatureEnabled: Boolean
        get() = isAntiThrowEnabled || isAutoSwipeEnabled || isAutoSkipLiveEnabled

    var swipedAdsCount: Int
        get() = prefs.getInt(KEY_SWIPED_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_SWIPED_COUNT, value).apply()

    var blockedThrowsCount: Int
        get() = prefs.getInt(KEY_BLOCKED_THROWS_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_BLOCKED_THROWS_COUNT, value).apply()

    var lastActionLog: String
        get() {
            val defaultLog = if (isEnglish) "No activity recorded yet" else "Belum ada aktivitas tercatat"
            return prefs.getString(KEY_LAST_ACTION_LOG, null) ?: defaultLog
        }
        set(value) = prefs.edit().putString(KEY_LAST_ACTION_LOG, value).apply()

    fun recordSwipe(keyword: String, isStory: Boolean) {
        val current = swipedAdsCount + 1
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val modeLabel = if (isStory) "Story-Swipe" else "Feed/Reels-Swipe"
        prefs.edit()
            .putInt(KEY_SWIPED_COUNT, current)
            .putString(KEY_LAST_ACTION_LOG, "[$time] $modeLabel (\"$keyword\")")
            .apply()
    }

    fun recordBlockedThrow(reason: String) {
        val current = blockedThrowsCount + 1
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val prefix = if (isEnglish) "Blocked" else "Ditahan"
        prefs.edit()
            .putInt(KEY_BLOCKED_THROWS_COUNT, current)
            .putString(KEY_LAST_ACTION_LOG, "[$time] $prefix: $reason")
            .apply()
    }

    fun resetStats() {
        val resetMsg = if (isEnglish) "Statistics reset" else "Statistik direset"
        prefs.edit()
            .putInt(KEY_SWIPED_COUNT, 0)
            .putInt(KEY_BLOCKED_THROWS_COUNT, 0)
            .putString(KEY_LAST_ACTION_LOG, resetMsg)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ig_scroll_guard_prefs"
        private const val KEY_ANTI_THROW = "key_anti_throw"
        private const val KEY_AUTO_SWIPE = "key_auto_swipe"
        private const val KEY_AUTO_SKIP_LIVE = "key_auto_skip_live"
        private const val KEY_APP_LANGUAGE = "key_app_language"
        private const val KEY_SWIPED_COUNT = "key_swiped_count"
        private const val KEY_BLOCKED_THROWS_COUNT = "key_blocked_throws_count"
        private const val KEY_LAST_ACTION_LOG = "key_last_action_log"

        const val LANG_ID = "id"
        const val LANG_EN = "en"

        @Volatile
        private var instance: PrefsManager? = null

        fun getInstance(context: Context): PrefsManager {
            return instance ?: synchronized(this) {
                instance ?: PrefsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

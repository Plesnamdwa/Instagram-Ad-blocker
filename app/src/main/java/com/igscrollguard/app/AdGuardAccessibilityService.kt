package com.igscrollguard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.igscrollguard.app.utils.PrefsManager

class AdGuardAccessibilityService : AccessibilityService() {

    private lateinit var prefsManager: PrefsManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // State Memory Timestamps
    private var lastAdDetectedTime: Long = 0L
    private var lastSwipeTime: Long = 0L
    private var lastBackActionTime: Long = 0L
    private var lastInstagramActiveTime: Long = 0L

    // Session tracker to distinguish ad throws from normal Home screen app launches
    private var isSessionInInstagram: Boolean = false

    private data class DetectionResult(
        val keyword: String,
        val bounds: Rect,
        val forceHorizontalSwipe: Boolean = false
    )

    companion object {
        private const val TAG = "AdGuardService"

        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val MIUI_SECURITY_PACKAGE = "com.miui.securitycenter"

        private val homeLauncherPackages = setOf(
            "com.miui.home",
            "com.mi.android.globallauncher"
        )

        val whitelistedPackages = setOf(
            "com.miui.home",
            "com.mi.android.globallauncher",
            "com.android.systemui"
        )

        val blacklistedPackages = setOf(
            "com.shopee.id",
            "com.tokopedia.tkpd",
            "com.whatsapp",
            "com.android.chrome",
            "com.mi.globalbrowser",
            "com.lazada.android",
            "com.tiktok.android",
            "com.android.vending"
        )

        // Indonesian Ad Keywords
        val adKeywordsId = listOf(
            "bersponsor",
            "belanja sekarang",
            "ngobrol sekarang",
            "kunjungi situs",
            "pelajari selengkapnya",
            "instal sekarang",
            "daftar",
            "pesan sekarang",
            "kunjungi profil instagram",
            "beli sekarang",
            "dapatkan penawaran"
        )

        // English Ad Keywords
        val adKeywordsEn = listOf(
            "sponsored",
            "shop now",
            "chat now",
            "visit site",
            "learn more",
            "install now",
            "sign up",
            "book now",
            "order now",
            "visit instagram profile",
            "get offer",
            "buy now",
            "apply now",
            "get quote"
        )

        // Explicit Live Stream Preview CTA Phrases (Indonesian & English)
        private val liveStreamPhrases = listOf(
            "tonton siaran langsung",
            "ketuk untuk menonton siaran langsung",
            "gabung ke siaran langsung",
            "lihat video siaran langsung",
            "siaran langsung berakhir",
            "watch live",
            "watch live video",
            "tap to watch live",
            "join live video"
        )

        private val dialogPromptKeywords = listOf(
            // Indonesian
            "buka aplikasi",
            "buka tautan",
            "keluar dari instagram",
            "buka dengan",
            "izinkan",
            // English
            "open app",
            "open link",
            "leave instagram",
            "open with",
            "allow"
        )

        private val cancelKeywords = listOf(
            // Indonesian
            "batal",
            "tolak",
            "jangan",
            // English
            "cancel",
            "deny",
            "don't allow",
            "reject"
        )

        // Timing thresholds (ms)
        const val AD_REDIRECT_TOLERANCE_MS = 3000L
        const val SWIPE_COOLDOWN_MS = 1400L
        private const val BACK_ACTION_COOLDOWN_MS = 800L
        private const val IG_TRANSITION_WINDOW_MS = 4000L
    }

    override fun onCreate() {
        super.onCreate()
        prefsManager = PrefsManager.getInstance(this)
        Log.d(TAG, "AdGuardAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AdGuardAccessibilityService connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Run only if at least one feature (Anti-Throw, Auto-Swipe, or Auto-Skip Live) is enabled
        if (!prefsManager.isAnyFeatureEnabled) return

        val packageName = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        when {
            // 1. User returned to Xiaomi Home Launcher or opened IG Scroll Guard app
            homeLauncherPackages.contains(packageName) || packageName == this.packageName -> {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    isSessionInInstagram = false
                }
            }

            // 2. Inside Instagram
            packageName == INSTAGRAM_PACKAGE -> {
                isSessionInInstagram = true
                lastInstagramActiveTime = now
                handleInstagramEvent(event, now)
            }

            // 3. MIUI Security Center or System Dialog Confirmation (Only when Anti-Throw is ON)
            packageName == MIUI_SECURITY_PACKAGE || isSystemDialogPackage(packageName) -> {
                if (prefsManager.isAntiThrowEnabled) {
                    handleSystemOrMiuiDialog(now)
                }
            }

            // 4. Transitioned outside Instagram (Only when Anti-Throw is ON)
            !isWhitelisted(packageName) -> {
                if (prefsManager.isAntiThrowEnabled) {
                    handleExternalPackageTransition(packageName, now)
                }
            }
        }
    }

    /**
     * Handles events inside com.instagram.android:
     * 1. If Anti-Throw is ON & In-App Browser opened within 3000ms after an ad -> Auto-Back
     * 2. Scans node tree for ad keywords -> Updates State Memory & triggers Auto-Swipe (if Auto-Swipe is ON)
     * 3. If Auto-Skip Live is ON -> Scans node tree for Instagram Live stream & skips (Horizontal in Story, Vertical in Reels/Feed)
     */
    private fun handleInstagramEvent(event: AccessibilityEvent, now: Long) {
        val rootNode = rootInActiveWindow ?: return
        try {
            // Priority 1: If Anti-Throw is enabled and In-App Browser opened within 3000ms of an ad, close it
            if (prefsManager.isAntiThrowEnabled &&
                (now - lastAdDetectedTime) < AD_REDIRECT_TOLERANCE_MS &&
                isInAppBrowserActive(rootNode, event)
            ) {
                Log.w(TAG, "In-App Browser internal Instagram terdeteksi paska iklan! Menjalankan Auto-Back.")
                if (performSafeBack("In-App Browser Instagram")) {
                    val msg = if (prefsManager.isEnglish) {
                        "🛡️ Ad In-App Browser Closed!"
                    } else {
                        "🛡️ In-App Browser Iklan Ditutup!"
                    }
                    showBlockedToast(msg)
                }
                return
            }

            // Priority 2: Scan active window for sponsored/ad keywords (Needed for Anti-Throw State Memory OR Auto-Swipe)
            if (prefsManager.isAntiThrowEnabled || prefsManager.isAutoSwipeEnabled) {
                val adDetection = findAdKeywordInTree(rootNode)
                if (adDetection != null) {
                    lastAdDetectedTime = now
                    Log.d(TAG, "Ad detected [keyword: '${adDetection.keyword}', bounds: ${adDetection.bounds}] at $lastAdDetectedTime")

                    if (prefsManager.isAutoSwipeEnabled && (now - lastSwipeTime) > SWIPE_COOLDOWN_MS) {
                        lastSwipeTime = now
                        val isStory = isInstaStoryScreen(rootNode, adDetection.bounds)
                        if (isStory) {
                            performHorizontalStorySwipe(adDetection.keyword)
                        } else {
                            performVerticalSwipe(adDetection.keyword)
                        }
                        return
                    }
                }
            }

            // Priority 3: Scan active window for Instagram Live broadcasts if Auto-Skip Live is enabled
            if (prefsManager.isAutoSkipLiveEnabled && (now - lastSwipeTime) > SWIPE_COOLDOWN_MS) {
                val liveDetection = detectInstagramLiveScreen(rootNode)
                if (liveDetection != null) {
                    lastAdDetectedTime = now
                    lastSwipeTime = now
                    Log.d(TAG, "Instagram Live terdeteksi [${liveDetection.keyword}]! Melakukan Auto-Skip.")

                    val isStoryOrFullLive = liveDetection.forceHorizontalSwipe ||
                        isInstaStoryScreen(rootNode, liveDetection.bounds)
                    if (isStoryOrFullLive) {
                        performHorizontalStorySwipe("LIVE: ${liveDetection.keyword}")
                    } else {
                        performVerticalSwipe("LIVE: ${liveDetection.keyword}")
                    }
                }
            }
        } finally {
            try {
                rootNode.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Detects if the user is currently viewing an active Instagram Live stream or Live preview card,
     * while carefully ignoring the small Story Tray ring on the Main Home Feed.
     */
    private fun detectInstagramLiveScreen(rootNode: AccessibilityNodeInfo): DetectionResult? {
        val screenHeight = resources.displayMetrics.heightPixels

        var hasMainHomeFeedList = false
        var hasClipsReelsViewer = false
        var hasStoryOrLiveViewerId = false
        var hasBottomTabBar = false
        var liveBadgeCandidate: DetectionResult? = null
        var liveCtaCandidate: DetectionResult? = null

        fun scanForLiveNodes(node: AccessibilityNodeInfo, insideStoryTray: Boolean) {
            if (!node.isVisibleToUser) return

            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            val isTrayNode = insideStoryTray ||
                viewId.contains("reel_tray") ||
                viewId.contains("story_tray") ||
                viewId.contains("tray_item")

            if (viewId.isNotEmpty()) {
                if (viewId.contains("row_feed_") || viewId.contains("sticky_header_list")) {
                    hasMainHomeFeedList = true
                }
                if (viewId.contains("clips_viewer") || viewId.contains("clips_video")) {
                    hasClipsReelsViewer = true
                }
                if (viewId.contains("tab_bar") || viewId.contains("bottom_navigation")) {
                    hasBottomTabBar = true
                }
                // Explicit Instagram Live Viewer IDs (outside Home Story Tray)
                if (!isTrayNode && (
                        viewId.contains("iglive_") ||
                        viewId.contains("live_viewer") ||
                        viewId.contains("reel_live_") ||
                        viewId.contains("clips_live_")
                    )
                ) {
                    hasStoryOrLiveViewerId = true
                    if (liveBadgeCandidate == null) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        liveBadgeCandidate = DetectionResult("iglive_view", bounds, forceHorizontalSwipe = !hasClipsReelsViewer)
                    }
                }
            }

            if (!isTrayNode) {
                val text = node.text?.toString()?.trim()?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

                // Check explicit Live preview CTA phrases (works in Reels, Feed, and Stories)
                val matchedPhrase = liveStreamPhrases.firstOrNull { phrase ->
                    text.contains(phrase) || desc.contains(phrase)
                }
                if (matchedPhrase != null && liveCtaCandidate == null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    liveCtaCandidate = DetectionResult(matchedPhrase, bounds)
                }

                // Check exact "LIVE" or "SIARAN LANGSUNG" header badge
                val isExactLiveBadge = text == "siaran langsung" ||
                    text == "live" ||
                    desc == "siaran langsung" ||
                    desc == "live"

                if (isExactLiveBadge && liveBadgeCandidate == null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    // In Live Viewer (Story) or Reels Live, the badge is either in top header (< 25%) or bottom overlay (> 60%)
                    if (bounds.centerY() < screenHeight * 0.25f || bounds.centerY() > screenHeight * 0.60f) {
                        liveBadgeCandidate = DetectionResult(
                            keyword = if (text.isNotEmpty()) text else desc,
                            bounds = bounds
                        )
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    scanForLiveNodes(child, isTrayNode)
                } finally {
                    try {
                        child.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        scanForLiveNodes(rootNode, insideStoryTray = false)

        // 1. If an explicit "Watch live" / "Tonton siaran langsung" CTA is on screen, always valid
        if (liveCtaCandidate != null) {
            return liveCtaCandidate
        }

        // 2. If an iglive_ view ID was found, always valid
        if (hasStoryOrLiveViewerId && liveBadgeCandidate != null) {
            return liveBadgeCandidate
        }

        // 3. If an exact "LIVE" / "SIARAN LANGSUNG" badge was found:
        //    Accept it if we are inside Story/Live Viewer (!hasMainHomeFeedList && !hasBottomTabBar)
        //    OR inside Reels Viewer (hasClipsReelsViewer).
        //    Reject if we are on the Main Home Feed so a top Story Tray ring never false-triggers!
        if (liveBadgeCandidate != null && (!hasMainHomeFeedList && (!hasBottomTabBar || hasClipsReelsViewer))) {
            return liveBadgeCandidate
        }

        return null
    }

    /**
     * Determines whether the current Instagram screen is an Insta-Story / Full-Screen Live (horizontal paging)
     * rather than Feed or Reels (vertical scrolling).
     */
    private fun isInstaStoryScreen(rootNode: AccessibilityNodeInfo, targetBounds: Rect): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels

        var hasStoryViewId = false
        var hasFeedOrClipsViewId = false
        var hasStoryReplyInput = false
        var bottomTabBarFound = false

        fun inspectUiStructure(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return

            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (viewId.isNotEmpty()) {
                // Instagram internal Story & Live viewer IDs
                if (viewId.contains("reel_viewer") ||
                    viewId.contains("story_viewer") ||
                    viewId.contains("story_progress") ||
                    viewId.contains("story_cta") ||
                    viewId.contains("iglive_")
                ) {
                    hasStoryViewId = true
                }
                // Instagram internal Feed & Reels (Clips) & Bottom Bar IDs
                if (viewId.contains("clips_viewer") ||
                    viewId.contains("clips_video") ||
                    viewId.contains("row_feed_") ||
                    viewId.contains("sticky_header_list") ||
                    viewId.contains("tab_bar")
                ) {
                    hasFeedOrClipsViewId = true
                }
                if (viewId.contains("tab_bar") || viewId.contains("bottom_navigation")) {
                    bottomTabBarFound = true
                }
            }

            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            if (text.contains("kirim pesan") ||
                text.contains("send message") ||
                desc.contains("kirim pesan") ||
                desc.contains("send message")
            ) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerY() > screenHeight * 0.80f) {
                    hasStoryReplyInput = true
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    inspectUiStructure(child)
                    if (hasStoryViewId && hasFeedOrClipsViewId) return
                } finally {
                    try {
                        child.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        inspectUiStructure(rootNode)

        if (hasStoryViewId && !hasFeedOrClipsViewId) return true
        if (hasStoryReplyInput && !bottomTabBarFound) return true

        // Geometric fallback: In Stories/Live, header badge is in the top (< 24% height)
        // and there is no bottom navigation tab bar or feed/clips container.
        if (!hasFeedOrClipsViewId && !bottomTabBarFound && targetBounds.centerY() < (screenHeight * 0.24f)) {
            return true
        }

        return false
    }

    /**
     * Handles Xiaomi MIUI/HyperOS confirmation pop-ups ("Buka aplikasi" / "Open app")
     */
    private fun handleSystemOrMiuiDialog(now: Long) {
        val withinAdWindow = (now - lastAdDetectedTime) < AD_REDIRECT_TOLERANCE_MS
        val fromInstagramRecently = isSessionInInstagram && (now - lastInstagramActiveTime) < IG_TRANSITION_WINDOW_MS

        if (!withinAdWindow && !fromInstagramRecently) return

        val rootNode = rootInActiveWindow ?: return
        try {
            val isConfirmPopup = containsDialogPrompt(rootNode) || withinAdWindow
            if (isConfirmPopup) {
                if (clickCancelOnDialog(rootNode)) {
                    Log.i(TAG, "Dialog confirmation cancelled automatically.")
                    prefsManager.recordBlockedThrow("MIUI Dialog")
                    val msg = if (prefsManager.isEnglish) {
                        "🛡️ Ad Confirmation Dialog Rejected!"
                    } else {
                        "🛡️ Dialog Konfirmasi Iklan Ditolak!"
                    }
                    showBlockedToast(msg)
                } else {
                    if (performSafeBack("MIUI Dialog")) {
                        val msg = if (prefsManager.isEnglish) {
                            "🛡️ Ad Dialog Cancelled!"
                        } else {
                            "🛡️ Dialog Iklan Dibatalkan!"
                        }
                        showBlockedToast(msg)
                    }
                }
            }
        } finally {
            try {
                rootNode.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Handles package switches outside Instagram to e-commerce / browsers / external apps
     */
    private fun handleExternalPackageTransition(packageName: String, now: Long) {
        val withinAdWindow = (now - lastAdDetectedTime) < AD_REDIRECT_TOLERANCE_MS
        val isBlacklisted = blacklistedPackages.contains(packageName)
        val fromInstagram = isSessionInInstagram || (now - lastInstagramActiveTime) < IG_TRANSITION_WINDOW_MS

        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            try {
                if (withinAdWindow && clickCancelOnDialog(rootNode)) {
                    prefsManager.recordBlockedThrow("Dialog $packageName")
                    val msg = if (prefsManager.isEnglish) {
                        "🛡️ Ad Redirect Rejected!"
                    } else {
                        "🛡️ Lemparan Iklan Ditolak!"
                    }
                    showBlockedToast(msg)
                    return
                }
            } finally {
                try {
                    rootNode.recycle()
                } catch (_: Exception) {
                }
            }
        }

        if (fromInstagram && (isBlacklisted || withinAdWindow)) {
            Log.w(TAG, "Ad throw to $packageName detected! Executing GLOBAL_ACTION_BACK.")
            if (performSafeBack(packageName)) {
                val msg = if (prefsManager.isEnglish) {
                    "🛡️ Ad Redirect Blocked!"
                } else {
                    "🛡️ Lemparan Iklan Diblokir!"
                }
                showBlockedToast(msg)
            }
        }
    }

    private fun getActiveAdKeywords(): List<String> {
        return if (prefsManager.isEnglish) {
            adKeywordsEn + adKeywordsId
        } else {
            adKeywordsId + adKeywordsEn
        }
    }

    private fun findAdKeywordInTree(node: AccessibilityNodeInfo): DetectionResult? {
        if (!node.isVisibleToUser) return null

        val text = node.text?.toString()?.trim()?.lowercase()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase()
        val keywords = getActiveAdKeywords()

        for (kw in keywords) {
            val matched = when (kw) {
                "daftar", "sign up" -> {
                    text == kw || text == "daftar sekarang" || desc == kw || desc == "daftar sekarang"
                }
                else -> {
                    (text != null && text.contains(kw)) || (desc != null && desc.contains(kw))
                }
            }

            if (matched) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                return DetectionResult(kw, bounds)
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findAdKeywordInTree(child)
                if (found != null) return found
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun isInAppBrowserActive(rootNode: AccessibilityNodeInfo, event: AccessibilityEvent): Boolean {
        val eventClass = event.className?.toString() ?: ""
        if (eventClass.contains("Browser", ignoreCase = true) ||
            eventClass.contains("WebView", ignoreCase = true) ||
            eventClass.contains("InstantExperiences", ignoreCase = true) ||
            eventClass.contains("CustomTab", ignoreCase = true)
        ) {
            return true
        }

        fun scanForBrowserIndicators(node: AccessibilityNodeInfo): Boolean {
            if (!node.isVisibleToUser) return false

            val nodeClass = node.className?.toString() ?: ""
            if (nodeClass == "android.webkit.WebView") return true

            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (viewId.contains("browser_container") ||
                viewId.contains("ig_browser") ||
                viewId.contains("web_view") ||
                viewId.contains("browser_chrome")
            ) {
                return true
            }

            val text = node.text?.toString()?.lowercase()?.trim() ?: ""
            if (text.isNotEmpty() && !text.contains(" ")) {
                if (text.startsWith("http://") ||
                    text.startsWith("https://") ||
                    text.contains(".co.id") ||
                    text.contains(".com") ||
                    text.contains("shopee.co.id") ||
                    text.contains("tokopedia.com")
                ) {
                    return true
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    if (scanForBrowserIndicators(child)) return true
                } finally {
                    try {
                        child.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
            return false
        }

        return scanForBrowserIndicators(rootNode)
    }

    private fun containsDialogPrompt(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (dialogPromptKeywords.any { text.contains(it) || desc.contains(it) }) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsDialogPrompt(child)) return true
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun clickCancelOnDialog(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

        val matchesCancel = cancelKeywords.any { kw ->
            text == kw || text.contains(kw) || desc == kw || desc.contains(kw)
        }

        if (matchesCancel) {
            if (performClickOnNodeOrParent(node)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (clickCancelOnDialog(child)) return true
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var currentParent = node.parent
        var depth = 0
        while (currentParent != null && depth < 4) {
            try {
                if (currentParent.isClickable) {
                    val clicked = currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    currentParent.recycle()
                    return clicked
                }
                val nextParent = currentParent.parent
                currentParent.recycle()
                currentParent = nextParent
                depth++
            } catch (_: Exception) {
                break
            }
        }
        return false
    }

    private fun performSafeBack(reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBackActionTime < BACK_ACTION_COOLDOWN_MS) {
            return false
        }
        lastBackActionTime = now
        val result = performGlobalAction(GLOBAL_ACTION_BACK)
        if (result) {
            prefsManager.recordBlockedThrow(reason)
        }
        return result
    }

    /**
     * Feed & Reels: Dispatches a fast vertical swipe from Y=75% to Y=20% at X=center (duration 150ms)
     */
    private fun performVerticalSwipe(detectedKeyword: String) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val centerX = width * 0.5f
        val startY = height * 0.75f
        val endY = height * 0.20f

        val swipePath = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(swipePath, 0L, 150L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Vertical Auto-Swipe (Feed/Reels) selesai untuk: $detectedKeyword")
                prefsManager.recordSwipe(detectedKeyword, isStory = false)
            }
        }, null)
    }

    /**
     * Insta-Story & Story Live: Dispatches a fast horizontal swipe from Right (X=85%) to Left (X=15%)
     * at Y=45% (safe middle zone, avoiding bottom CTA link stickers) over 140ms.
     */
    private fun performHorizontalStorySwipe(detectedKeyword: String) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val startX = width * 0.85f
        val endX = width * 0.15f
        val safeMiddleY = height * 0.45f

        val swipePath = Path().apply {
            moveTo(startX, safeMiddleY)
            lineTo(endX, safeMiddleY)
        }

        val stroke = GestureDescription.StrokeDescription(swipePath, 0L, 140L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Horizontal Story Auto-Swipe selesai untuk: $detectedKeyword")
                prefsManager.recordSwipe(detectedKeyword, isStory = true)
            }
        }, null)
    }

    private fun isSystemDialogPackage(packageName: String): Boolean {
        return packageName == "android" ||
            packageName == "com.lbe.security.miui" ||
            packageName == "com.miui.guardprovider"
    }

    private fun isWhitelisted(packageName: String): Boolean {
        return packageName == this.packageName || whitelistedPackages.contains(packageName)
    }

    private fun showBlockedToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AdGuardAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isSessionInInstagram = false
        Log.i(TAG, "AdGuardAccessibilityService destroyed")
    }
}

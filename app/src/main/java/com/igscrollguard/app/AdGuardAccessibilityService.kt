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

    // Scan-throttle state: TYPE_WINDOW_CONTENT_CHANGED arrives in bursts, so full tree
    // scans are debounced into a single Single-Pass Scan per screen change.
    private var lastScanTime: Long = 0L
    private var pendingScanScheduled: Boolean = false
    private var pendingBrowserClassName: String? = null
    private var scheduledScanToken = 0

    // Session tracker to distinguish ad throws from normal Home screen app launches
    private var isSessionInInstagram: Boolean = false

    // Gesture dispatch guard: at most one Auto-Swipe may be in flight at a time.
    // dispatchGesture is always invoked on the main thread (from onAccessibilityEvent
    // or the scheduled rescan), and its callbacks fire on the main looper, so a plain
    // mutable flag is sufficient and allocation-free.
    private var gestureInFlight = false

    // Pre-built swipe gestures, cached until the display metrics change (rotation / resize).
    private var cachedVerticalSwipe: GestureDescription? = null
    private var cachedHorizontalStorySwipe: GestureDescription? = null
    private var cachedGestureMetricsKey = ""

    // Reusable bounds Rect: geometry-only checks (live-badge zones, story-reply zone)
    // read node bounds into this scratch Rect so no allocation happens in the hot
    // traversal. Candidates that must outlive the node are copied into a fresh Rect.
    private val scratchBounds = Rect()

    /** Memoized active ad-keyword table, rebuilt only when the UI language changes. */
    private class KeywordMemo(
        val language: String,
        val keywords: Array<String>,
        val isStrict: BooleanArray
    )

    private var keywordMemo: KeywordMemo? = null

    private data class DetectionResult(
        val keyword: String,
        val bounds: Rect,
        val forceHorizontalSwipe: Boolean = false
    )

    /**
     * Immutable result of a SINGLE pre-order DFS traversal of the active window's node tree.
     * Merges ad detection, Instagram Live detection, Story structure detection, and
     * In-App Browser indicator detection so a screen change costs exactly one traversal.
     */
    private data class ScreenSnapshot(
        // Ad (first keyword match in pre-order)
        val adKeyword: String?,
        val adBounds: Rect?,
        // Live
        val hasMainHomeFeedList: Boolean,
        val hasClipsReelsViewer: Boolean,
        val hasStoryOrLiveViewerId: Boolean,
        val hasBottomTabBar: Boolean,
        val liveBadge: DetectionResult?,
        val liveCta: DetectionResult?,
        // Story structure
        val hasStoryViewId: Boolean,
        val hasFeedOrClipsViewId: Boolean,
        val hasStoryReplyInput: Boolean,
        val storyBottomTabBarFound: Boolean,
        // In-App Browser
        val hasBrowserIndicator: Boolean
    )

    /** Mutable accumulator filled during the single traversal. */
    private class ScanBuilder {
        var adKeyword: String? = null
        var adBounds: Rect? = null
        var hasMainHomeFeedList = false
        var hasClipsReelsViewer = false
        var hasStoryOrLiveViewerId = false
        var hasBottomTabBar = false
        var liveBadge: DetectionResult? = null
        var liveCta: DetectionResult? = null
        var hasStoryViewId = false
        var hasFeedOrClipsViewId = false
        var hasStoryReplyInput = false
        var storyBottomTabBarFound = false
        var hasBrowserIndicator = false

        fun toSnapshot() = ScreenSnapshot(
            adKeyword = adKeyword,
            adBounds = adBounds,
            hasMainHomeFeedList = hasMainHomeFeedList,
            hasClipsReelsViewer = hasClipsReelsViewer,
            hasStoryOrLiveViewerId = hasStoryOrLiveViewerId,
            hasBottomTabBar = hasBottomTabBar,
            liveBadge = liveBadge,
            liveCta = liveCta,
            hasStoryViewId = hasStoryViewId,
            hasFeedOrClipsViewId = hasFeedOrClipsViewId,
            hasStoryReplyInput = hasStoryReplyInput,
            storyBottomTabBarFound = storyBottomTabBarFound,
            hasBrowserIndicator = hasBrowserIndicator
        )
    }

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

        // Debounce window for full node-tree scans: TYPE_WINDOW_CONTENT_CHANGED bursts
        // collapse into a single Single-Pass Scan
        private const val SCAN_THROTTLE_MS = 400L
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
     * Debounces the expensive tree scan for fast TYPE_WINDOW_CONTENT_CHANGED bursts.
     * Window state changes are always handled immediately; content changes are throttled
     * to one scan per SCAN_THROTTLE_MS window, with a trailing rescan so no detection is lost.
     */
    private fun handleInstagramEvent(event: AccessibilityEvent, now: Long) {
        val browserClassName = event.className?.toString()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val elapsed = now - lastScanTime
            if (elapsed < SCAN_THROTTLE_MS) {
                pendingBrowserClassName = browserClassName
                if (!pendingScanScheduled) {
                    pendingScanScheduled = true
                    val token = ++scheduledScanToken
                    mainHandler.postDelayed({
                        // A newer immediate scan may have already run since scheduling:
                        if (token != scheduledScanToken) return@postDelayed
                        pendingScanScheduled = false
                        performScheduledScan()
                    }, SCAN_THROTTLE_MS - elapsed)
                }
                return
            }
        }

        lastScanTime = now
        pendingScanScheduled = false
        pendingBrowserClassName = null
        scheduledScanToken++
        performInstagramScan(browserClassName, now)
    }

    /**
     * Runs a scan from a rescan that was scheduled because a TYPE_WINDOW_CONTENT_CHANGED
     * burst was throttled: uses the latest captured browser-class hint and re-arms the
     * throttle window so the next burst can be collapsed the same way.
     */
    private fun performScheduledScan() {
        val now = System.currentTimeMillis()
        lastScanTime = now
        val className = pendingBrowserClassName
        pendingBrowserClassName = null
        performInstagramScan(className, now)
    }

    /**
     * Handles events inside com.instagram.android using a single node-tree traversal:
     * 1. If Anti-Throw is ON & In-App Browser opened within 3000ms after an ad -> Auto-Back
     * 2. Scans node tree for ad keywords -> Updates State Memory & triggers Auto-Swipe (if Auto-Swipe is ON)
     * 3. If Auto-Skip Live is ON -> detects Instagram Live stream & skips (Horizontal in Story, Vertical in Reels/Feed)
     */
    private fun performInstagramScan(browserClassName: String?, now: Long) {
        val rootNode = rootInActiveWindow ?: return
        try {
            val snapshot = scanScreen(rootNode)

            val antiThrow = prefsManager.isAntiThrowEnabled
            val autoSwipe = prefsManager.isAutoSwipeEnabled
            val autoSkipLive = prefsManager.isAutoSkipLiveEnabled

            // Priority 1: If Anti-Throw is enabled and In-App Browser opened within 3000ms of an ad, close it
            if (antiThrow &&
                (now - lastAdDetectedTime) < AD_REDIRECT_TOLERANCE_MS &&
                (isBrowserClassActive(browserClassName) || snapshot.hasBrowserIndicator)
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

            // Priority 2: React to an ad keyword found in the tree (Needed for Anti-Throw State Memory OR Auto-Swipe)
            if ((antiThrow || autoSwipe) && snapshot.adKeyword != null) {
                lastAdDetectedTime = now
                Log.d(TAG, "Ad detected [keyword: '${snapshot.adKeyword}', bounds: ${snapshot.adBounds}] at $lastAdDetectedTime")

                if (autoSwipe && (now - lastSwipeTime) > SWIPE_COOLDOWN_MS) {
                    lastSwipeTime = now
                    val adKeyword = snapshot.adKeyword
                    val isStory = isInstaStoryScreen(snapshot, snapshot.adBounds)
                    if (isStory) {
                        performHorizontalStorySwipe(adKeyword)
                    } else {
                        performVerticalSwipe(adKeyword)
                    }
                    return
                }
            }

            // Priority 3: Auto-Skip Instagram Live broadcasts if enabled
            if (autoSkipLive && (now - lastSwipeTime) > SWIPE_COOLDOWN_MS) {
                val liveDetection = detectInstagramLiveScreen(snapshot)
                if (liveDetection != null) {
                    lastAdDetectedTime = now
                    lastSwipeTime = now
                    Log.d(TAG, "Instagram Live terdeteksi [${liveDetection.keyword}]! Melakukan Auto-Skip.")

                    val isStoryOrFullLive = liveDetection.forceHorizontalSwipe ||
                        isInstaStoryScreen(snapshot, liveDetection.bounds)
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
     * SINGLE-PASS scan: one pre-order DFS over the node tree that simultaneously
     * collects ad keyword matches, Instagram Live signals, Story structure flags,
     * and In-App Browser indicators.
     */
    private fun scanScreen(rootNode: AccessibilityNodeInfo): ScreenSnapshot {
        val memo = activeKeywordMemo()
        val screenHeight = resources.displayMetrics.heightPixels
        val builder = ScanBuilder()
        scanNode(rootNode, insideStoryTray = false, builder, memo, screenHeight)
        return builder.toSnapshot()
    }

    private fun scanNode(
        node: AccessibilityNodeInfo,
        insideStoryTray: Boolean,
        builder: ScanBuilder,
        memo: KeywordMemo,
        screenHeight: Int
    ) {
        if (!node.isVisibleToUser) return

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val isTrayNode = insideStoryTray ||
            viewId.contains("reel_tray") ||
            viewId.contains("story_tray") ||
            viewId.contains("tray_item")

        if (viewId.isNotEmpty()) {
            // ---- Shared structural flags (Live + Story + Browser) ----
            if (viewId.contains("row_feed_") || viewId.contains("sticky_header_list")) {
                builder.hasMainHomeFeedList = true
            }
            if (viewId.contains("clips_viewer") || viewId.contains("clips_video")) {
                builder.hasClipsReelsViewer = true
            }
            if (viewId.contains("tab_bar") || viewId.contains("bottom_navigation")) {
                builder.hasBottomTabBar = true
                builder.storyBottomTabBarFound = true
            }

            // Explicit Instagram Live Viewer IDs (outside Home Story Tray)
            if (!isTrayNode &&
                (viewId.contains("iglive_") ||
                    viewId.contains("live_viewer") ||
                    viewId.contains("reel_live_") ||
                    viewId.contains("clips_live_"))
            ) {
                builder.hasStoryOrLiveViewerId = true
                if (builder.liveBadge == null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    builder.liveBadge = DetectionResult(
                        "iglive_view",
                        bounds,
                        forceHorizontalSwipe = !builder.hasClipsReelsViewer
                    )
                }
            }

            // Story viewer structure IDs
            if (viewId.contains("reel_viewer") ||
                viewId.contains("story_viewer") ||
                viewId.contains("story_progress") ||
                viewId.contains("story_cta") ||
                viewId.contains("iglive_")
            ) {
                builder.hasStoryViewId = true
            }
            if (viewId.contains("clips_viewer") ||
                viewId.contains("clips_video") ||
                viewId.contains("row_feed_") ||
                viewId.contains("sticky_header_list") ||
                viewId.contains("tab_bar")
            ) {
                builder.hasFeedOrClipsViewId = true
            }

            // In-App Browser indicator IDs
            if (viewId.contains("browser_container") ||
                viewId.contains("ig_browser") ||
                viewId.contains("web_view") ||
                viewId.contains("browser_chrome")
            ) {
                builder.hasBrowserIndicator = true
            }
        }

        // ---- Text / content-description based checks ----
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

        // Ad keyword match (first match in pre-order wins; recorded once)
        if (builder.adKeyword == null && (text.isNotEmpty() || desc.isNotEmpty())) {
            val keywords = memo.keywords
            val strict = memo.isStrict
            for (i in keywords.indices) {
                val kw = keywords[i]
                val matched = if (strict[i]) {
                    text == kw || text == "daftar sekarang" || desc == kw || desc == "daftar sekarang"
                } else {
                    text.contains(kw) || desc.contains(kw)
                }
                if (matched) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    builder.adKeyword = kw
                    builder.adBounds = bounds
                    break
                }
            }
        }

        if (!isTrayNode) {
            // Explicit Live preview CTA phrases (Reels, Feed, and Stories)
            if (builder.liveCta == null) {
                val matchedPhrase = liveStreamPhrases.firstOrNull { phrase ->
                    text.contains(phrase) || desc.contains(phrase)
                }
                if (matchedPhrase != null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    builder.liveCta = DetectionResult(matchedPhrase, bounds)
                }
            }

            // Exact "LIVE" / "SIARAN LANGSUNG" header badge
            val isExactLiveBadge = text == "siaran langsung" ||
                text == "live" ||
                desc == "siaran langsung" ||
                desc == "live"
            if (isExactLiveBadge && builder.liveBadge == null) {
                node.getBoundsInScreen(scratchBounds)
                // In Live Viewer (Story) or Reels Live, the badge is in the top header (< 25%)
                // or the bottom overlay (> 60%)
                if (scratchBounds.centerY() < screenHeight * 0.25f ||
                    scratchBounds.centerY() > screenHeight * 0.60f
                ) {
                    val bounds = Rect(scratchBounds)
                    builder.liveBadge = DetectionResult(
                        keyword = if (text.isNotEmpty()) text else desc,
                        bounds = bounds
                    )
                }
            }
        }

        // Story reply input ("Kirim pesan" / "Send message") near the bottom
        if (!builder.hasStoryReplyInput &&
            (text.contains("kirim pesan") || text.contains("send message") ||
                desc.contains("kirim pesan") || desc.contains("send message"))
        ) {
            node.getBoundsInScreen(scratchBounds)
            if (scratchBounds.centerY() > screenHeight * 0.80f) {
                builder.hasStoryReplyInput = true
            }
        }

        // In-App Browser URL-ish text indicators
        if (!builder.hasBrowserIndicator &&
            text.isNotEmpty() && !text.contains(" ") &&
            (text.startsWith("http://") ||
                text.startsWith("https://") ||
                text.contains(".co.id") ||
                text.contains(".com") ||
                text.contains("shopee.co.id") ||
                text.contains("tokopedia.com"))
        ) {
            builder.hasBrowserIndicator = true
        }
        if (node.className?.toString() == "android.webkit.WebView") {
            builder.hasBrowserIndicator = true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanNode(child, isTrayNode, builder, memo, screenHeight)
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Memoized active ad keywords. The combined array + strict-match flags are rebuilt
     * only when the app language changes, never per node or per scan.
     */
    private fun activeKeywordMemo(): KeywordMemo {
        val language = prefsManager.appLanguage
        val existing = keywordMemo
        if (existing != null && existing.language == language) return existing

        val combined = if (language == PrefsManager.LANG_EN) {
            adKeywordsEn + adKeywordsId
        } else {
            adKeywordsId + adKeywordsEn
        }
        val keywords = combined.toTypedArray()
        val isStrict = BooleanArray(keywords.size) { i ->
            keywords[i] == "daftar" || keywords[i] == "sign up"
        }
        val memo = KeywordMemo(language, keywords, isStrict)
        keywordMemo = memo
        return memo
    }

    /**
     * Determines whether the current Instagram screen is an Insta-Story / Full-Screen Live
     * (horizontal paging) rather than Feed or Reels (vertical scrolling), using the
     * structure flags collected during the single-pass scan.
     */
    private fun isInstaStoryScreen(snapshot: ScreenSnapshot, targetBounds: Rect?): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels

        if (snapshot.hasStoryViewId && !snapshot.hasFeedOrClipsViewId) return true
        if (snapshot.hasStoryReplyInput && !snapshot.storyBottomTabBarFound) return true

        // Geometric fallback: In Stories/Live, the header badge sits in the top (< 24% height)
        // and there is no bottom navigation tab bar or feed/clips container.
        val centerY = targetBounds?.centerY() ?: return false
        if (!snapshot.hasFeedOrClipsViewId &&
            !snapshot.storyBottomTabBarFound &&
            centerY < (screenHeight * 0.24f)
        ) {
            return true
        }

        return false
    }

    /**
     * Detects an active Instagram Live stream / Live preview card from the single-pass
     * snapshot, while carefully ignoring the small Story Tray ring on the Main Home Feed.
     */
    private fun detectInstagramLiveScreen(snapshot: ScreenSnapshot): DetectionResult? {
        // 1. An explicit "Watch live" / "Tonton siaran langsung" CTA is always valid
        snapshot.liveCta?.let { return it }

        // 2. An iglive_ view ID was found, always valid
        if (snapshot.hasStoryOrLiveViewerId && snapshot.liveBadge != null) {
            return snapshot.liveBadge
        }

        // 3. An exact "LIVE" / "SIARAN LANGSUNG" badge:
        //    Accept it inside Story/Live Viewer (!hasMainHomeFeedList && !hasBottomTabBar)
        //    OR inside Reels Viewer (hasClipsReelsViewer).
        //    Reject on the Main Home Feed so a top Story Tray ring never false-triggers.
        val badge = snapshot.liveBadge
        if (badge != null &&
            (!snapshot.hasMainHomeFeedList && (!snapshot.hasBottomTabBar || snapshot.hasClipsReelsViewer))
        ) {
            return badge
        }

        return null
    }

    /**
     * Cheap class-name shortcut for the In-App Browser (complements the tree-based
     * `snapshot.hasBrowserIndicator` from the single-pass scan).
     */
    private fun isBrowserClassActive(className: String?): Boolean {
        val c = className ?: return false
        return c.contains("Browser", ignoreCase = true) ||
            c.contains("WebView", ignoreCase = true) ||
            c.contains("InstantExperiences", ignoreCase = true) ||
            c.contains("CustomTab", ignoreCase = true)
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

    /** Builds (or reuses) the cached pre-built swipe gestures. */
    private fun ensureCachedGestures() {
        val metrics = resources.displayMetrics
        val key = "${metrics.widthPixels}x${metrics.heightPixels}"
        if (key == cachedGestureMetricsKey &&
            cachedVerticalSwipe != null &&
            cachedHorizontalStorySwipe != null
        ) {
            return
        }

        cachedGestureMetricsKey = key
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        // Feed & Reels: fast vertical swipe from Y=75% to Y=20% at X=center (150ms)
        val verticalPath = Path().apply {
            moveTo(width * 0.5f, height * 0.75f)
            lineTo(width * 0.5f, height * 0.20f)
        }
        cachedVerticalSwipe = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(verticalPath, 0L, 150L))
            .build()

        // Insta-Story & Story Live: fast horizontal swipe from Right (X=85%) to Left (X=15%)
        // at the safe middle Y=45% (140ms), avoiding the bottom CTA link stickers.
        val horizontalPath = Path().apply {
            moveTo(width * 0.85f, height * 0.45f)
            lineTo(width * 0.15f, height * 0.45f)
        }
        cachedHorizontalStorySwipe = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(horizontalPath, 0L, 140L))
            .build()
    }

    /**
     * Feed & Reels: dispatches the cached fast vertical swipe (Y=75% -> Y=20%, X=center).
     */
    private fun performVerticalSwipe(detectedKeyword: String) {
        if (gestureInFlight) return
        ensureCachedGestures()
        val gesture = cachedVerticalSwipe ?: return
        gestureInFlight = true

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureInFlight = false
                Log.d(TAG, "Vertical Auto-Swipe (Feed/Reels) selesai untuk: $detectedKeyword")
                prefsManager.recordSwipe(detectedKeyword, isStory = false)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureInFlight = false
            }
        }, null)
    }

    /**
     * Insta-Story & Story Live: dispatches the cached fast horizontal swipe (X=85% -> X=15%, Y=45%).
     */
    private fun performHorizontalStorySwipe(detectedKeyword: String) {
        if (gestureInFlight) return
        ensureCachedGestures()
        val gesture = cachedHorizontalStorySwipe ?: return
        gestureInFlight = true

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureInFlight = false
                Log.d(TAG, "Horizontal Story Auto-Swipe selesai untuk: $detectedKeyword")
                prefsManager.recordSwipe(detectedKeyword, isStory = true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureInFlight = false
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

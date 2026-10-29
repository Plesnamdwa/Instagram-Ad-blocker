# 🛡️ IG Scroll Guard

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="100" height="100" alt="IG Scroll Guard Icon" />
</p>

<p align="center">
  <strong>Next-gen Instagram Ad &amp; Redirect Blocker for Android (Optimized for Xiaomi MIUI &amp; HyperOS)</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-brightgreen.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin-orange.svg" alt="Language" />
  <img src="https://img.shields.io/badge/UI_Style-NeoBrutalism-yellow.svg" alt="UI Style" />
  <img src="https://img.shields.io/badge/Architecture-Accessibility_State_Memory-blue.svg" alt="Architecture" />
  <img src="https://img.shields.io/badge/License-MIT-black.svg" alt="License" />
</p>

---

## 📖 Overview

**IG Scroll Guard** is a lightweight, non-root Android utility designed to eliminate sponsored ad fatigue and unwanted shopping throws while browsing Instagram. 

By combining an **Accessibility Service with State Memory**, **contextual gesture dispatching**, and **system dialog interception**, it seamlessly detects and skips ads in your Feed, Reels, and Insta-Stories—and immediately pulls you back if an ad attempts to redirect you to external apps like Shopee, Tokopedia, Lazada, or Chrome.

Designed with a bold **NeoBrutalism** aesthetic (high-contrast canvas, 3dp solid black borders, and 4dp hard drop-shadows) and built to withstand aggressive MIUI/HyperOS background task killers.

---

## ✨ Key Features

### 1. 🔄 Context-Aware Auto-Swipe
- **Feed &amp; Reels**: Instantly performs a rapid vertical swipe (`Y=75% -> Y=20%`) the moment an ad is detected in the viewport.
- **Insta-Story Patch**: In Instagram Stories, swiping up accidentally triggers the ad's "Swipe Up / View Link" action. IG Scroll Guard detects Story mode and executes a **Horizontal Swipe (`X=85% -> X=15%` at safe midpoint `Y=45%`)**, smoothly advancing to the next story without touching link stickers!
- **Zero Pre-fetch False Positives**: Validates visible nodes (`isVisibleToUser`) so background/recycled items don't trigger premature gestures.

### 2. 🛡️ Anti-Redirect Shield (State Memory)
- **External App Interception**: Detects if your active window switches from Instagram to blacklisted e-commerce apps (`com.shopee.id`, `com.tokopedia.tkpd`, `com.lazada.android`, `com.whatsapp`, etc.) or external browsers, immediately executing an automatic `Back` action.
- **In-App Browser Auto-Close**: If an ad is tapped and opens Instagram's internal webview within 3000ms, it is closed instantly.
- **Xiaomi System Dialog Auto-Cancel**: Traverses clickable parent hierarchies on MIUI Security pop-ups (*"Open app" / "Buka aplikasi"*) to automatically press *"Cancel / Deny / Batal"*.
- **Launcher-Safe**: Whitelists home launchers (`com.miui.home`, `com.mi.android.globallauncher`, Samsung One UI, etc.), ensuring you can open your shopping or messaging apps normally from your home screen.

### 3. 🔴 Auto-Skip Instagram Live Streams (Optional)
- Eliminates promotional Live broadcasts when scrolling through Stories or Reels.
- **Smart Filtering**: Ignores the small "LIVE" ring badges on the top Home Feed story tray, only activating when you enter an actual full-screen Live broadcast.

### 4. 🎛️ Independent Toggles &amp; Control Center Tile
- All 3 features (Anti-Redirect Shield, Auto-Swipe Ads, Auto-Skip Live) are **100% independent**. You can run Auto-Swipe alone, Anti-Redirect alone, or all together.
- Includes an Android **Quick Settings Tile** for easy toggling directly from your Control Center / Notification Shade.

### 5. 🌐 Bilingual Support (ID / EN)
- One-tap toggle (`🇮🇩 ID` / `🇬🇧 EN`) to switch both the dashboard UI and ad keyword detection priority between Indonesian and English.

---

## 📱 Xiaomi (MIUI / HyperOS) Setup Guide

Because Xiaomi devices enforce strict background app restrictions, follow these one-time steps for 24/7 reliability:

1. **Allow Restricted Settings (Android 13+ / HyperOS)**:
   - Long-press the **IG Scroll Guard** icon on your home screen > tap **App Info (i)**.
   - Tap the **three dots (⋮)** in the top-right corner.
   - Select **"Allow restricted settings"** and confirm your PIN/Fingerprint.
2. **Lock Autostart &amp; Battery**:
   - In the app, tap **"1. Lock Xiaomi Autostart &amp; Battery"**.
   - Enable **Autostart (Mulai otomatis)**.
   - Set **Battery Saver (Penghemat baterai)** to **"No restrictions (Tidak ada pembatasan)"**.
3. **Enable Accessibility Service**:
   - Turn on the **Accessibility Service** switch > go to **Downloaded apps** > select **IG Scroll Guard** > turn ON and accept the 10-second security prompt.

> **Note for Samsung / Google Pixel / Other Brands**: The app works out of the box on all Android 8.0+ devices! Simply grant the Accessibility permission in Settings.

---

## 🛠️ Tech Stack &amp; Architecture

- **Language**: Kotlin 1.9.22
- **Minimum SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 14 (API 34)
- **UI Architecture**: ViewBinding, Material 3, Custom NeoBrutalism XML Vector &amp; Layer-List Drawables
- **Core APIs**: `AccessibilityService`, `GestureDescription`, `TileService`, `SharedPreferences`
- **Build System**: Gradle 8.5, Android Gradle Plugin (AGP) 8.2.2

```
IG Scroll Guard/
├── app/
│   ├── src/main/
│   │   ├── java/com/igscrollguard/app/
│   │   │   ├── MainActivity.kt               # NeoBrutalism Dashboard & Switch Controller
│   │   │   ├── AdGuardAccessibilityService.kt # State Memory & Gesture Dispatch Engine
│   │   │   ├── ScrollGuardTileService.kt     # Quick Settings Tile Service
│   │   │   └── utils/
│   │   │       ├── AccessibilityUtil.kt      # OS permission checker
│   │   │       ├── PrefsManager.kt           # SharedPreferences wrapper & stats logger
│   │   │       └── XiaomiIntentHelper.kt     # Deep-link navigation for MIUI/HyperOS
│   │   └── res/                              # Layouts, NeoBrutalism drawables, strings
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🚀 Building from Source

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (Hedgehog 2023.1.1 or newer)
- JDK 17 or JDK 21
- Android SDK 34

### Steps
1. Clone this repository:
   ```bash
   git clone https://github.com/your-username/ig-scroll-guard.git
   cd ig-scroll-guard
   ```
2. Open the project folder in **Android Studio**.
3. Let Gradle sync dependencies.
4. Build the Debug APK:
   - In Android Studio: **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
   - Or via CLI:
     ```bash
     ./gradlew assembleDebug
     ```
5. The generated APK will be located at:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 🔒 Privacy &amp; Security

- **100% Offline &amp; Private**: IG Scroll Guard requires **NO internet permission** (`android.permission.INTERNET` is not even declared in the manifest).
- **No Data Collected**: None of your Instagram credentials, messages, or viewing history leave your device.
- **Open Source**: Full source code is available for auditing.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

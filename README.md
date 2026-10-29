# Gplay 🎬

A sleek, modern, and high-performance Android video player powered by **libmpv**. Designed for fluid touch interactions, desktop-grade codec support, and a clean, customizable user interface.

---

## ✨ Features

- **🚀 libmpv Core Engine**  
  Hardware-accelerated playback supporting almost any video & audio format (MKV, MP4, WebM, AVI, TS, FLAC, Opus, etc.) with advanced subtitle rendering (ASS/SSA styled subtitles, SRT, Embedded tracks).

- **🖐️ Intuitive Touch Gestures**  
  - Swipe vertically on the left/right for Brightness and Volume.
  - Horizontal swipe for smooth seeking.
  - Pinch-to-zoom and video scale options (Fit, Crop, Stretch, 100%).
  - One-tap screen lock to prevent accidental touches.

- **🎛️ Precision Playback & Speed Ruler**  
  - Intuitive speed ruler for granular playback speed adjustments.
  - Buffer ring indicator and precise seek slider.
  - Resume playback store that remembers where you left off.

- **🎧 Background Playback & Picture-in-Picture (PiP)**  
  - Seamless Picture-in-Picture mode on supported Android versions.
  - Background audio playback service with rich notification media controls.

- **🎚️ Audio Equalizer & Sound Effects**  
  - Integrated equalizer presets and customizable audio tuning.

- **📁 Smart Media Library**  
  - Multiple layout views: Cards, Grid, List, and Compact.
  - Automatic folder organization and fast video scanning via MediaStore.

- **🎨 Dynamic Theming & Custom Icons**  
  - Switchable app icon themes (Violet, Teal, Rose, Green, Amber).

---

## 🛠️ Tech Stack & Requirements

- **Language:** Kotlin
- **Build System:** Gradle (Kotlin DSL - `.gradle.kts`)
- **Min SDK:** 26 (Android 8.0 Oreo)
- **Target SDK:** 34 (Android 14)
- **Compile SDK:** 36
- **JDK:** Java 17
- **Key Dependencies:**
  - [`dev.jdtech.mpv:libmpv:1.0.0`](https://github.com/jarnedemeulemeester/mpv-android) — mpv backend for Android

---

## 🏗️ Project Architecture

```
app/src/main/java/com/example/iinadroid/
├── MainActivity.kt       # Primary player UI, gesture controller & lifecycle handling
├── PlaybackService.kt    # Foreground service for background audio & media notifications
├── VideoRepo.kt          # Video scanning, metadata extraction & folder indexing
├── LibraryView.kt        # Media library view & layout switchers (Grid/Cards/List)
├── ResumeStore.kt        # Persistence for playback timestamps and watch history
├── SpeedRuler.kt         # Custom UI control for variable playback speeds
├── AudioFx.kt            # Equalizer and audio effects management
├── Slider.kt             # Custom scrub bar & timeline
├── BufferRing.kt         # Visual buffer progress ring
├── Downloads.kt          # Network streaming and media handling
└── Ui.kt                 # UI helpers, animations, and theme styles
```

---

## 🚀 Building & Running

### 1. Clone the repository
```bash
git clone git@github-gulshan36:Gulshan36/Gplay.git
cd Gplay
```

### 2. Build Debug APK
```bash
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Release APK
Create a `keystore.properties` file in the project root with the following keys:
```properties
storeFile=path/to/keystore.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```
Then build the release bundle:
```bash
./gradlew assembleRelease
```

---

## 📄 License

This project is licensed under the terms of the open-source license applicable to the project. See individual files for licensing details.

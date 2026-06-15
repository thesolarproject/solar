<img width="480" height="360" alt="Screenshot_20260615_172845" src="https://github.com/user-attachments/assets/d804a2a5-a2c8-4a0e-ad16-d1da7551d5fd" />
<img width="480" height="360" alt="Screenshot_20260615_172516" src="https://github.com/user-attachments/assets/5b76d3c5-2d62-4119-8c01-49b9b79c055d" />
<img width="480" height="360" alt="Screenshot_20260615_172415" src="https://github.com/user-attachments/assets/b072b3c4-78b6-4c24-8e27-08193779ac96" />
<img width="480" height="360" alt="Screenshot_20260615_172223" src="https://github.com/user-attachments/assets/e1ef1c7f-cec3-4613-b228-4f413136a42d" />
<img width="480" height="360" alt="Screenshot_20260615_172109" src="https://github.com/user-attachments/assets/39b8fa44-c7a0-4c40-8a37-36e087874099" />


# JJ Launcher (MO-ON Launcher)

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/Status-Beta-Orange?style=flat-square" alt="Status">
</p>

## 🌟 v0.5 update (2026/06/15)
1. Added color themes
2. Added widget support
3. Fixed visual rendering issues in File Explorer (Resolved slowdowns when loading a large number of songs)
4. Removed radio buttons
5. Replaced battery indicator with an icon
6. Optimized performance and fixed bugs

---

## 🌟 Key Features

1. **Custom Embedded Media Scanner**
   * Automatically and rapidly categorizes large music libraries without relying on the Android system scanner.
2. **Advanced Playback & EQ Controls**
   * Full support for shuffle, repeat modes, and built-in device Equalizer (EQ) presets.
3. **Global Hardware Key Mapping**
   * Skip to the previous or next track using physical hardware buttons from any screen within the app.
4. **Real-time Main Screen Sync**
   * The album art and track information on the main screen sync instantly whenever the active song changes.
5. **Infinite Custom Themes**
   * Easily add and apply your own custom color themes simply by copying files.
6. **Dynamic HD Blur Background**
   * Generates a high-definition blurred wallpaper dynamically matching the currently playing album art.
7. **In-App Bluetooth Pairing**
   * Scan and pair Bluetooth devices instantly within the launcher settings without needing to leave the app.
8. **Wheel-Optimized Virtual Keyboard**
   * Type Wi-Fi passwords and connect directly using a specialized keyboard tailored for wheel-input devices.
9. **Wireless PC File Upload**
   * Host a local Wi-Fi Web Server to wirelessly upload music files directly from your PC browser without cables.

---

## 💾 Installation Guide

> ⚠️ **Important:** You must install **Rockbox** beforehand to fully utilize all power-off functionalities.

### 🛠️ Step-by-Step Installation
1. Install **Rockbox** on your device.
2. Choose **Reboot to Stock Firmware** to boot into the stock system.
3. Connect the device to your PC and install the new launcher using the **ADB Tool**:
   ```bash
   adb install app-release.apk
4. ```bash
   adb shell pm disable com.innioasis.y1
5. Reboot the device to complete the setup.

---
## ⚠️ Caveats / Notes
1. Regarding Bluetooth Headphones: Due to the lack of a Bluetooth test device in the developer's environment, this specific feature has not been fully tested yet. Please keep this in mind during use.

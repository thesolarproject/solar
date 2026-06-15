<img width="480" height="360" alt="Screenshot_20260615_172845" src="https://github.com/user-attachments/assets/d804a2a5-a2c8-4a0e-ad16-d1da7551d5fd" />
<img width="480" height="360" alt="Screenshot_20260615_172516" src="https://github.com/user-attachments/assets/5b76d3c5-2d62-4119-8c01-49b9b79c055d" />
<img width="480" height="360" alt="Screenshot_20260615_172415" src="https://github.com/user-attachments/assets/b072b3c4-78b6-4c24-8e27-08193779ac96" />
<img width="480" height="360" alt="Screenshot_20260615_172223" src="https://github.com/user-attachments/assets/e1ef1c7f-cec3-4613-b228-4f413136a42d" />
<img width="480" height="360" alt="Screenshot_20260615_172109" src="https://github.com/user-attachments/assets/39b8fa44-c7a0-4c40-8a37-36e087874099" />




# JJ Launcher (MO-ON Launcher)
for innioasis y1
<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/Status-Beta-Orange?style=flat-square" alt="Status">
</p>

---

## 🛠️ Note for Users / Bug Reports

> ⚠️ **Important Notice**
> Please note that this launcher is currently in its **Alpha stage**, and you may encounter unexpected bugs or stability issues. 

If you find any bugs or have suggestions, please **open an Issue** in this repository. I will do my best to address them and release updates whenever my schedule allows. Thank you for your patience and support! 🙏

## 🌟 v0.5 update (2026/06/15)
1. Added color themes
2. Added widget support
3. Fixed visual rendering issues in File Explorer (Resolved slowdowns when loading a large number of songs)
4. Removed radio buttons
5. Replaced battery indicator with an icon
6. Auto-Download Album Art via Wi-Fi
7. Optimized performance and fixed bugs

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

## 🌟 ADB Tool Download:
https://dl.google.com/android/repository/platform-tools-latest-windows.zip?hl=ko
Platform Tools for Windows (Latest)
---
## ⚠️ Caveats / Notes
1. Regarding Bluetooth Headphones: Due to the lack of a Bluetooth test device in the developer's environment, this specific feature has not been fully tested yet. Please keep this in mind during use.



---
# How to Create a Custom Theme for JJ Launcher

Creating a custom theme is the ultimate way to make this DAP (Digital Audio Player) truly yours! The launcher dynamically loads theme resources (colors, icons, and fonts) directly from the device's internal storage.

## 🛠️ Step 1: Create the Theme Folder
1. Connect your device to a PC or open your file manager app.
2. Navigate to the root theme directory: `/storage/sdcard0/Y1_Themes/`
3. Create a new folder inside it and give it a name without spaces (e.g., `/storage/sdcard0/Y1_Themes/Cyberpunk_Dark/`).

## 🛠️ Step 2: Prepare Custom Icons & Fonts (Optional)
Drop your custom assets directly into your new theme folder.

* **Custom Icons:** Must be `.png` files with a transparent background. Name them exactly as follows:
  * `icon_now_playing.png` (Now Playing menu)
  * `icon_music.png` (All Songs / Library menu)
  * `icon_bluetooth.png` (Bluetooth setup)
  * `icon_setting.png` (Settings menu)
  * `icon_radio.png` (FM Radio)
  * `icon_server.png` (Web Server menu)
  * `icon_default_album.png` (Fallback image for missing album art)
* **Custom Font:** Drop a `.ttf` or `.otf` font file into the folder (e.g., `myfont.ttf`).

## 🛠️ Step 3: Create the `config.json` File
This is the core of your theme. Create a text file named exactly `config.json` inside your theme folder and paste the following template:


```json
{
  "name": "My Awesome Theme",
  "font": "myfont.ttf",
  "textPrimary": "#FFFFFF",
  "textSecondary": "#88AADD",
  "bgOverlay": "#DD0F172A",
  "statusBarBg": "#99002255",
  "btnNormal": "#221E40AF",
  "btnFocused": "#DD3B82F6",
  "btnFocusedText": "#000000",
  "button_radius": 30
}
```

## 🛠️ Step 4: Understanding the Configuration Values
* `name`: The display name of your theme in the Settings menu.
* `font`: The exact filename of the custom font (delete this line to use the system default font).
* `textPrimary`: Color for main titles, active text, and the clock.
* `textSecondary`: Color for artist names and inactive text.
* `bgOverlay`: Background color for menus. The first two characters dictate transparency (e.g., `DD`).
* `statusBarBg`: Background color for the top status bar. (Delete this line to default to `bgOverlay`).
* `btnNormal`: Default background color of list buttons.
* `btnFocused`: Highlight color when a button is selected (also applies to battery ring and volume bars).
* `btnFocusedText`: Text color inside a highlighted button.
* `button_radius`: Controls button roundness (`0` = sharp square, `10` = slightly rounded, `30+` = fully rounded pill).

> **💡 Quick Tip:** Always use **8-character Hex Codes** (e.g., `#DD0F172A`) for background colors if you want transparency. The first two characters (`DD`) control the opacity (`00` for invisible, `FF` for solid).

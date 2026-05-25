<div align="center">

# 🕶️ NoPeek

### See who's watching before they see you.

[![Android](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen?style=flat-square)](https://android-arsenal.com/api?level=26)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)
[![Privacy](https://img.shields.io/badge/No%20Ads-No%20Tracking-red?style=flat-square)]()

**NoPeek detects nearby smart glasses and VR headsets via Bluetooth — before they record you.**

[Download APK](#) · [Report Bug](https://github.com/getnopeek/nopeek-android/issues) · [Request Feature](https://github.com/getnopeek/nopeek-android/issues)

</div>

---

## 👁️ The Problem

Meta sold **7 million Ray-Ban smart glasses** in 2024 alone. They look identical to normal glasses. They record video and audio. Facial recognition is coming.

You have no way of knowing if someone near you is recording.

**Until now.**

---

## 📱 What NoPeek Does

NoPeek scans Bluetooth Low Energy (BLE) signals around you and detects known camera-capable devices — smart glasses, AR headsets, and VR headsets — before they can record you without consent.

> No internet connection required. No data leaves your phone. Ever.

---

## ✨ Features

- 🔍 **Real-time BLE scanning** — detects devices within ~10-15m
- 📏 **Distance estimation** — know how close the threat is (~2m, ~5m, ~10m)
- 🚨 **Instant alerts** — vibration + sound alarm on detection
- 🔔 **Push notifications** — alerts even when app is minimised
- 🔄 **Background scanning** — runs silently while you go about your day
- 📋 **Detection history** — log of every device ever detected
- 🎯 **Zero false positives** — smart matching prevents your iPhone triggering alerts
- ⚙️ **Configurable** — adjust RSSI threshold and notification cooldown
- 🚫 **No ads. No tracking. No accounts.**

---

## 🕶️ Devices Detected

| Device | Risk | Detection Method |
|--------|------|-----------------|
| Meta Ray-Ban Glasses | 🔴 High | Company ID `0x0D53` (Luxottica) |
| Oakley Meta Glasses | 🔴 High | Company ID `0x0D53` (Luxottica) |
| Snap Spectacles | 🔴 High | Company ID `0x03C2` (Snapchat) |
| TCL RayNeo Glasses | 🔴 High | Company ID `0x07D7` (TCL) |
| Meta Quest VR | 🔴 High | Company ID `0x01AB` (Meta) |
| Apple Vision Pro | 🔴 High | Company ID `0x004C` + name |
| Pico VR | 🔴 High | Company ID `0x0BA7` |
| HTC Vive | 🟡 Medium | Company ID `0x00E0` |
| Sony PSVR | 🟡 Medium | Name + MAC prefix |
| Valve Index | 🟡 Medium | Name + MAC prefix |
| Microsoft HoloLens | 🟡 Medium | Name + MAC prefix |

---

## 🔬 How Detection Works

NoPeek uses a **3-layer detection system**:

```
Layer 1 — BLE Manufacturer Company ID (primary, most reliable)
          Every BLE device broadcasts a company ID in its ADV frame.
          This ID is immutable and cannot be randomized or hidden.
          Source: Bluetooth SIG Assigned Numbers database.

Layer 2 — Device name keyword matching (secondary)
          Only visible during pairing/power-on. Used as confirmation.

Layer 3 — MAC address OUI prefix (fallback, low confidence)
          MAC addresses are randomized on modern devices.
          Used only as supporting evidence alongside other signals.
```

**False positive prevention:** Apple's company ID `0x004C` is used by ALL Apple devices (iPhone, AirPods, MacBook). NoPeek requires BOTH the company ID AND the device name "Vision Pro" before flagging an Apple device — your iPhone will never trigger an alert.

**RSSI threshold:** Default -75 dBm (~10-15m outdoors, 3-10m indoors) — per research by [Nearby Glasses](https://github.com/yjeanrenaud/yj_nearbyglasses).

---

## 🚀 Getting Started

### Requirements
- Android 8.0 (API 26) or higher
- Bluetooth enabled
- Location permission (required by Android for BLE scanning)

### Build from source
```bash
git clone https://github.com/getnopeek/nopeek-android
cd nopeek-android
# Open in Android Studio and run
```

### Permissions used
| Permission | Why |
|-----------|-----|
| `BLUETOOTH_SCAN` | Scan for nearby BLE devices |
| `BLUETOOTH_CONNECT` | Connect to detected devices for identification |
| `ACCESS_FINE_LOCATION` | Required by Android OS for BLE scanning |
| `POST_NOTIFICATIONS` | Alert you when a device is detected |
| `FOREGROUND_SERVICE` | Background scanning |
| `VIBRATE` | Vibration alerts |

---

## 🔒 Privacy

- **No internet permission** — NoPeek cannot send data anywhere
- **No analytics** — zero tracking, zero telemetry
- **No accounts** — no sign-up, no login
- **Open source** — verify every line yourself
- Detection history stored locally on your device only

---

## 🤝 Contributing

Contributions welcome! Especially:
- New device signatures (company IDs, MAC prefixes)
- False positive reports
- Translations

Please open an issue before submitting a PR.

---

## 📄 License

MIT License — free to use, modify, and distribute.

---

## ⚠️ Disclaimer

NoPeek is a detection tool only. Detection is not guaranteed — devices may not broadcast BLE at all times. Use as one layer of privacy awareness, not as a complete solution.

---

<div align="center">

**If this protects your privacy, give it a ⭐**

Made with 🔒 by [@getnopeek](https://github.com/getnopeek)

</div>

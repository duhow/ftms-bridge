# FTMS Bridge

<p align="center">
  <strong>Connect to Bluetooth FTMS fitness machines, record workouts and monitor real-time metrics.</strong>
</p>

<p align="center">
  <a href="https://github.com/duhow/ftms-bridge/actions/workflows/build.yml">
    <img src="https://github.com/duhow/ftms-bridge/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin" alt="Kotlin">
</p>

<p align="center">
<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/duhow/ftms-bridge">
  <img src="https://github.com/ImranR98/Obtainium/blob/main/assets/graphics/badge_obtainium.png" 
       alt="Get it on Obtainium" align="center" height="54" />
</a>

<a href="https://github.com/duhow/ftms-bridge/releases/latest">
  <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/4711835e032fe2735dc80c1329beb4685899aa91/get-it-on-github.png"
       alt="Download APK" align="center" height="81" />
</a>
</p>

---

## Features

- **FTMS Protocol Support** — Connect to any Bluetooth FTMS-compatible treadmill or indoor bike (BH Fitness, and other brands)
- **Heart Rate Sensor** — Pair with BLE heart rate monitors (e.g. Xiaomi Smart Band 7 via Gadgetbridge)
- **Real-time Dashboard** — Live metrics: speed, cadence, power, distance, heart rate, energy, time, inclination, resistance
- **Session Recording** — Automatically log workout data to a local database
- **Debug BT Logger** — Captures all raw Bluetooth communication for protocol analysis and reverse engineering
- **BH Fitness Ready** — Dedicated device classes for BH Fitness treadmills and indoor bikes
- **OpenTracks Integration** — Prepared for OpenTracks API integration via Android intents

## Supported Devices

### Fitness Machines (FTMS)
- BH Fitness treadmills (i.Concept, Multimedia monitors)
- BH Fitness indoor bikes (i.Concept, Multimedia monitors)
- Any FTMS-compatible machine from other brands

### Heart Rate Sensors
- Any BLE Heart Rate Profile (0x180D) device
- Xiaomi Smart Band 7 (via Gadgetbridge)
- Standard chest straps and arm bands

## Debug Mode

Debug mode is enabled by default. All Bluetooth communication is logged as hex dumps to:
```
Documents/FTMSBridge/debug/
```

Each session creates a log file with timestamps, UUIDs, and raw byte data for protocol analysis.

## Building

```sh
./fastlane/gradlew assembleDebug
```

### Keystore

To sign your APK, create a keystore. **Keep it safe**.

```sh
keytool -genkeypair -v -keystore release.jks -alias ftms-bridge -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 10000
```

You can upload it to GitHub Actions as Secret `ANDROID_KEYSTORE_BASE64` to generate Release APKs.

```sh
base64 -w 0 release.jks ; echo
```

Then define `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD` (default is the same), and `ANDROID_KEY_ALIAS` as configured.

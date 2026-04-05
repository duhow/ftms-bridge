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
- **Heart Rate Sensor** — Pair with BLE heart rate monitors (e.g. Gadgetbridge-compatible smart bands and standard chest straps)
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
- Gadgetbridge-compatible smart bands and wearables
- Standard chest straps and arm bands

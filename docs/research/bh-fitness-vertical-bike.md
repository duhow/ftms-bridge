# BH Fitness Vertical Bike — Protocol Research

> Device tested: **C01_12DB5** (MAC DA:F4:90:E1:2D:B5)
> Session log date: 2026-04-04
> Source: reverse-engineered from live BLE connection log.
>
> ⚠️ Only the connection handshake has been captured so far.
> Exercise data packets (0x2ACE notifications) have not yet been observed.
> Findings below are based on service discovery and capability reads only.

---

## BLE services present

| Service UUID | Name |
|--------------|------|
| `0x1826` | Fitness Machine Service (FTMS) |
| `0x180D` | Heart Rate |
| `0x180A` | Device Information |
| `0x180F` | Battery Service |
| `0x1800` | Generic Access |
| `0x1801` | Generic Attribute |
| `0xFE59` | Nordic DFU / OTA |
| `0xC100` | iConcept proprietary |

### FTMS characteristics

| UUID | Props | Notes |
|------|-------|-------|
| `0x2ACE` | 16 (NOTIFY) | Cross Trainer Data — used for vertical bike metrics |
| `0x2ACC` | 2 (READ) | Fitness Machine Feature |
| `0x2AD9` | 24 (NOTIFY\|WRITE_NO_RESPONSE) | Control Point |
| `0x2AD5` | 2 | Supported Inclination Range |
| `0x2AD6` | 2 | Supported Resistance Range |
| `0x2AD8` | 2 | Supported Power Range |

> This device advertises the **Cross Trainer** (0x2ACE) FTMS characteristic rather
> than Indoor Bike (0x2AD2), even though it is physically a vertical/upright bike.

### iConcept characteristics

| UUID | Props | Notes |
|------|-------|-------|
| `0xC101` | 8 (WRITE) | Unknown write channel |
| `0xC102` | 8 (WRITE) | Unknown write channel |
| `0xC111` | 16 (NOTIFY) | Subscribed; notifications not yet observed |
| `0xC112` | 16 (NOTIFY) | Subscribed; may carry workout counters (unconfirmed) |
| `0xC121` | 8 (WRITE) | Unknown |
| `0xC151` | 8 (WRITE) | Unknown |
| `0xC158` | 8 (WRITE) | Unknown |
| `0xC159` | 8 (WRITE) | Unknown |

---

## Device naming convention

BH Fitness / iConcept devices use a Bluetooth advertised name of the form:

```
<model-letter><two-digit-number>_<5 hex chars of MAC>
```

Examples observed:
| Device name | Device type | FTMS characteristic |
|-------------|-------------|---------------------|
| `B01_479D7` | Indoor Bike | 0x2AD2 |
| `C01_12DB5` | Vertical Bike | 0x2ACE |

The app recognises this pattern via `FitnessDevice.isBhFitness()` using the regex
`^[a-z]\d{2}_[0-9a-f]{5}$`.

---

## Fitness Machine Feature (0x2ACC)

```
READ 8 bytes: C4 5E 00 00 0E 00 00 00
machineFeatures   = 0x00005EC4 = 24260
targetSettings    = 0x0000000E = 14
```

### Machine features (0x5EC4 = 0101 1110 1100 0100)

| Bit | Meaning | Present |
|-----|---------|---------|
| 0 | Average Speed | No |
| 1 | Cadence | No |
| 2 | Total Distance | **Yes** |
| 3 | Inclination | No |
| 4 | Elevation Gain | No |
| 5 | Pace | No |
| 6 | Step Count (strides/min) | **Yes** |
| 7 | Resistance Level | **Yes** |
| 8 | Stride Count | No |
| 9 | Expended Energy | **Yes** |
| 10 | Heart Rate Measurement | **Yes** |
| 11 | Metabolic Equivalent | **Yes** |
| 12 | Elapsed Time | **Yes** |
| 13 | Remaining Time | No |
| 14 | Power Measurement | **Yes** |
| 15 | Force on Belt / Power Output | No |

### Target setting features (0x000E = 0000 1110)

| Bit | Meaning | Present |
|-----|---------|---------|
| 0 | Speed Target | No |
| 1 | Inclination Target | **Yes** |
| 2 | Resistance Target | **Yes** |
| 3 | Power Target | **Yes** |

---

## Cross Trainer Data (0x2ACE) — expected packet format

Based on FTMS capabilities, packets should include:
- Speed (instantaneous, from "More Data" = 0 convention)
- Step Count (strides/min + avg strides/min) — bit 3
- Total Distance — bit 2
- Resistance Level — bit 7
- Instantaneous Power — bit 8
- Expended Energy (kcal + kcal/hr + kcal/min) — bit 10
- Heart Rate — bit 11
- Metabolic Equivalent — bit 12
- Elapsed Time — bit 13

> ⚠️ Actual packets have not been captured.  Whether the device honours all
> capability flags or leaves some fields as zeros (like BH treadmills do for
> distance/elapsed time) is unknown.

---

## iConcept 0xC112 — Workout counters (unconfirmed)

The BH Fitness treadmill sends workout counters (elapsed time, distance, calories)
on `0xC112` because the corresponding FTMS fields are always zero.  It is unknown
whether the vertical bike exhibits the same behaviour.

`BhFitnessVerticalBike.onDataReceived()` supplements FTMS data with cached iConcept
values for elapsed time, distance, and calories **only when the FTMS-reported value
is zero**.  This is safe regardless of whether the device uses FTMS or iConcept for
these fields.

---

## Control point

The `0x2AD9` characteristic has properties `NOTIFY | WRITE_NO_RESPONSE` (props = 24),
identical to other BH Fitness iConcept 3.0 devices.  Writing `0x00` (Request Control)
after subscribing is confirmed to succeed (status = 0) and triggers the device to
acknowledge on the control-point notification (`80 00 01`).

---

## Connection handshake observations

```
[NOTIFY] 00002ad9 (3 bytes): 80 00 01    ← control point ACK: opcode 0x00 accepted
[NOTIFY] 00002a37 (2 bytes): 00 00       ← HR: 0 bpm (no sensor attached)
[READ]   00002acc (8 bytes): C4 5E 00 00 0E 00 00 00   ← FTMS capabilities
[READ]   00002a27 (6 bytes): 56 31 2E 30 2E 30         ← HW revision "V1.0.0"
[READ]   00002a26 (6 bytes): 31 35 2E 30 2E 30         ← FW revision "15.0.0"
```

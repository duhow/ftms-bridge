# BH Fitness Indoor Bike — Protocol Research

> Device tested: **B01_479D7** (MAC C8:FC:E7:F4:79:D7)
> Session log date: 2026-04-04
> Source: reverse-engineered from live BLE captures.

---

## BLE services present

| Service UUID | Name |
|--------------|------|
| `0x1826` | Fitness Machine Service (FTMS) |
| `0x180D` | Heart Rate |
| `0x180A` | Device Information |
| `0xC100` | iConcept proprietary |

### FTMS characteristics

| UUID | Props | Notes |
|------|-------|-------|
| `0x2AD2` | 16 (NOTIFY) | Indoor Bike Data — streams ~0.5–2 Hz |
| `0x2ACC` | 2 (READ) | Fitness Machine Feature |
| `0x2AD9` | 24 (NOTIFY\|WRITE_NO_RESPONSE) | Control Point |
| `0x2AD5` | 2 | Supported Inclination Range |
| `0x2AD6` | 2 | Supported Resistance Range |
| `0x2AD8` | 2 | Supported Power Range |

### iConcept characteristics

| UUID | Props | Notes |
|------|-------|-------|
| `0xC101` | 8 (WRITE) | Unknown write channel |
| `0xC102` | 8 (WRITE) | Unknown write channel |
| `0xC111` | 16 (NOTIFY) | Subscribed; **no notifications observed** during session |
| `0xC112` | 16 (NOTIFY) | Subscribed; **no notifications observed** during session |
| `0xC121` | 8 (WRITE) | Unknown |
| `0xC151` | 8 (WRITE) | Unknown |
| `0xC158` | 8 (WRITE) | Unknown |
| `0xC159` | 8 (WRITE) | Unknown |

> ⚠️ Unlike the BH treadmill, the indoor bike **did not send any C112 notifications**
> during the capture session.  The iConcept channel does not appear to carry workout
> counters for this device.

---

## Fitness Machine Feature (0x2ACC)

```
READ 8 bytes: 8E 5E 00 00 0E 20 00 00
machineFeatures   = 0x00005E8E = 24206
targetSettings    = 0x0000200E = 8206
```

---

## Indoor Bike Data (0x2AD2) packet format

### Flags

All observed packets carry flags `0x0F54`:

```
Binary: 0000 1111 0101 0100
Bit  0 = 0  → More Data = 0 → Instantaneous Speed present
Bit  1 = 0  → Average Speed absent
Bit  2 = 1  → Instantaneous Cadence present
Bit  3 = 0  → Average Cadence absent
Bit  4 = 1  → Total Distance present (3 bytes)
Bit  5 = 0  → Resistance Level absent
Bit  6 = 1  → Instantaneous Power present
Bit  7 = 0  → Average Power absent
Bit  8 = 1  → Expended Energy present (5 bytes)
Bit  9 = 1  → Heart Rate present
Bit 10 = 1  → Metabolic Equivalent present
Bit 11 = 1  → Elapsed Time present
Bit 12 = 0  → Remaining Time absent
Bit 13 = 0  → Reserved / future use (observed byte pair tracks console "level")
```

### Byte map (20-byte packet)

```
Offset  Len  Type     Field                  Notes
------  ---  -------  ---------------------  -----------------------------------
 0–1     2   UINT16   Flags                  Always 0x0F54 on this device
 2–3     2   UINT16   Instantaneous Speed    ← REPURPOSED: stride counter × 100
                                               (spec: km/h × 0.01)
 4–5     2   UINT16   Instantaneous Cadence  Correct; spec: rpm × 0.5
 6–8     3   UINT24   Total Distance         Always 0; device does not report
 9–10    2   SINT16   Instantaneous Power    Correct; spec: watts
11–12    2   UINT16   Total Energy           ← REPURPOSED: stride counter × 100
                                               (same raw bytes as Speed field)
13–14    2   UINT16   Energy/hr              ← CONSTANT 0x5400 = 21504; garbage
15       1   UINT8    Energy/min             Always 0
16       1   UINT8    Heart Rate             Valid when chest-strap connected
17       1   UINT8    Metabolic Equivalent   ← CONSTANT 0x7B = 12.3 MET; garbage
18–19    2   UINT16   Elapsed Time           Always 0; device does not report
```

### Field derivation: strides/min

Both the Speed and Total Energy fields carry the identical raw value `R`:

```
strides_per_min = R / 100.0
```

This is because the FTMS speed resolution of 0.01 km/h and the ×100 stride encoding
happen to use the same divisor — they are the same number.

### Annotated sample packets

```
# At rest / just connected
54 0F  00 00  82 00  00 00 00  54 00  00 00  00 54  00  00  7B  00 00
flags  spd=0  cad=65  dist=0    pwr=84  tot=0   ehr   emin  hr  met  time

# Light workout (~61 rpm, 78 W)
54 0F  74 09  7A 00  00 00 00  4E 00  74 09  00 54  00  00  7B  00 00
       2420   61 rpm            78 W   2420                           
       →24.2 strides/min same as above                               

# Higher cadence (~76 rpm, 67 W), HR sensor attached (72 bpm)
54 0F  0E 0B  98 00  00 00 00  43 00  0E 0B  00 54  00  48  7B  00 00
       2830   76 rpm            67 W   2830           0  72      0

# Further into workout (~80 rpm, 70 W, HR 120 bpm = 0x78)
54 0F  A4 0B  A0 00  00 00 00  46 00  A4 0B  00 54  00  78  7B  00 00
       2980   80 rpm            70 W   2980           0  120     0
```

> ℹ️ Notice `00 54` at Energy/hr offset **always** stays `00 54` (LE = 0x5400 = 21504)
> regardless of any workout parameter changing.  This is a device quirk / unused field.

### Cross-field consistency check

```
Sample: speed_raw=2420, cadence_raw=122 (61 rpm)
 → strides_per_min = 2420/100 = 24.2

Sample: speed_raw=2980, cadence_raw=160 (80 rpm)
 → strides_per_min = 2980/100 = 29.8
```

At 80 rpm cadence, 29.8 strides/min seems low — possibly the "strides" counter is
incremented by firmware logic (e.g. every N pedal revolutions) rather than
per-revolution.  **⚠️ The exact stride/cadence relationship is unconfirmed.**

---

## Fields NOT reported by this device

| Field | Reason | Workaround |
|-------|--------|------------|
| Distance | Always 0 in FTMS; no C112 data | Not available |
| Calories | Field repurposed for strides | Not available |
| Elapsed Time | Always 0 in FTMS; no C112 data | Not available |

### Practical derivations used by the app

Because the bike does not provide real FTMS distance/calorie counters, the app now
derives:

- **Speed (km/h):** from repurposed FTMS speed field (`raw_speed / 100`).
- **Distance (m):** integrated over time from derived speed.
- **Energy (kcal):** integrated from instantaneous power over time (`W * s / 4184`).

### Spike handling (speed/cadence)

Some sessions show occasional one-packet outliers (speed/cadence jumps that
immediately return on the next packet). To reduce dashboard/export artifacts:

- Impossible absolute values are rejected.
- Single-step deltas above a time-scaled limit are treated as outliers and ignored.
- The last accepted value is kept for that packet.

### Resistance level derivation (console level 1..11)

This bike does not set FTMS bit 5 (Resistance Level). In all observed `0x0F54` packets,
bytes at offsets `18–19` are non-zero and vary with workout load despite FTMS elapsed
time not being reported by this device.

For the B01_17384 capture, these bytes tracked the console “level” changes. The app now
derives a resistance level `1..11` from estimated crank torque:

- `torqueNm = powerW / angularVelocityRadPerSec`
- `angularVelocityRadPerSec = cadenceRpm * 2π / 60`
- map torque bands to integer levels and clamp to `1..11` while pedaling

When cadence/power indicate idle, level is `0` and the tile shows `--`.

---

## Implementation summary

`BhFitnessIndoorBike.onDataReceived()` corrects:
- `speedKmh` from repurposed speed bytes (`raw / 100`) with outlier filtering
- `speedKmh = 0.0` in UI/output for indoor bike to avoid duplicating stride-derived value
- `averageSpeedKmh = 0.0` — not present but zeroed for safety
- `cadenceRpm` with outlier filtering
- `resistanceLevel` derived as bike level (1..11) from power+cadence torque estimate
- `totalDistanceM` by integrating filtered speed over packet interval
- `stridesPerMin = totalEnergyKcal / 100.0` — stride counter
- `totalEnergyKcal` by integrating power over packet interval
- `energyPerHourKcal = 0` — garbage constant cleared
- `energyPerMinuteKcal = 0` — always 0, cleared for clarity
- `metabolicEquivalent = 0.0` — constant 0x7B, not a real reading

Reliable fields passed through unchanged: `cadenceRpm`, `instantaneousPowerW`, `heartRateBpm`.

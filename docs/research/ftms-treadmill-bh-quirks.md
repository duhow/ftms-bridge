# BH Fitness — Deviations from the FTMS Spec

> Source: reverse-engineered from live BLE captures.
> The FTMS specification is Bluetooth SIG document "Fitness Machine Service" (GATT
> Specification Supplement 4, Assigned Numbers 2019).

---

## 1. Treadmill inclination scale (0x2ACD)

### What the spec says

The Inclination field in the Treadmill Data characteristic (flags bit 3) is a
**signed INT16** in units of **0.1 %**.  Range: −100 to +100 (−10 % to +10 %).

### What BH Fitness treadmills send

BH Fitness treadmills send inclination in the same INT16 field, but use a
**non-standard range of 0–1000** that maps to a **physical grade of 0 %–16 %**.

```
Raw value   FTMS interpretation   Actual physical grade
         0              0.0 %              0.0 %
       100             10.0 %              1.6 %
       500             50.0 %              8.0 %
      1000            100.0 %             16.0 %
```

The correction factor is:

```
factor = 100 (FTMS max meaningful value) / 16 (physical max %) = 6.25
actual_percent = raw_ftms_value_in_0.1pct_units * 0.1 / 6.25
               = raw_int16 / 62.5
```

The same factor applies to the Ramp Angle Grade field (also INT16, units 0.1°).

### Implementation

`BhFitnessTreadmill.onDataReceived()` divides both `inclinationPercent` and
`rampAngleDeg` by `6.25` after the standard FTMS parse.

The UI displays inclination as a **rounded integer** (e.g. `3` instead of `2.88`),
matching the whole-number percent labels shown on the machine console.

### Negative inclination

Because the field is signed INT16, negative raw values (downhill) work correctly
once the standard Kotlin `buf.short.toInt()` signed-extend is applied.
Example: raw `−62` → −62 × 0.1 / 6.25 = **−0.99 %** (rounds to −1 % in the UI).

> ⚠️ **Open question:** a capture on the T01 model (session log 2026-04-05) shows
> raw inclination = **450** (→ 7.2 % after correction) at a time when the machine
> console reportedly displayed **−1 %** decline.  It is not yet known whether the
> T01 encodes decline as a positive offset (e.g. a different physical range), or
> whether the console reading was misread.  Future captures with confirmed
> console-vs-BLE comparison are needed before adding a decline correction.

---

## 2. Elapsed time / total distance / total energy not reported via FTMS

The standard Treadmill Data flags for elapsed time (bit 10), total distance (bit 2),
and expended energy (bit 7) are technically set in the packet observed from BH Fitness
devices, but the device always sends **0** for all three fields throughout the workout.

The actual counters are streamed via the proprietary **0xC112** iConcept channel.
See [bh-fitness-iconcept.md](bh-fitness-iconcept.md).

### C112 fires only once at session start

From captures on the T01 model (CC:79:88:30:7D:57), the 0xC112 notification is sent
**exactly once**, about 2 s after GATT connection, with `elapsed_time=2`, `distance=0`,
and `total_kcal=0`.  It does **not** repeat during the workout.

Consequence: elapsed time, distance, and energy appear to freeze at their initial
values for the entire session.

**Elapsed time fix:** `BhFitnessTreadmill` records the system clock at the moment C112
arrives (`iConceptLastUpdateMs`) and adds the wall-clock delta to `iConceptBaseElapsedSec`
on every subsequent `onDataReceived()` call.  This keeps the timer advancing correctly
even when no further C112 packets are received.

**Distance and energy:** These remain at the device-reported value (0 at session start)
because there is no way to estimate them reliably from the available data without
the device sending updated C112 packets.

### Observed 0x2ACD packet (steady state at 6.10 km/h)

```
Bytes (hex, LE):  8C 01  62 02  00 00 00  B4 00 00 00  00 00 00 00 00  00  00
                  ┗━━━┛  ┗━━━┛  ┗━━━━━━┛  ┗━━━━━━━━━━┛  ┗━━━━━━━━━━━━┛  ┗━━━┛
                  flags  speed  distance  incl+ramp     energy fields    HR
```

| Field | Value | Notes |
|-------|-------|-------|
| Flags | `0x018C` | Bits 2,3,7,8 set |
| Speed | 610 → 6.10 km/h | Correct |
| Distance | 0 m | Always 0; use iConcept |
| Inclination | 180 → 18.0 % FTMS → **2.88 % actual** | Needs ÷6.25 correction |
| Ramp angle | 0 | |
| Total kcal | 0 | Always 0; use iConcept |
| kcal/h | 0 | |
| kcal/min | 0 | |
| Heart rate | 0 | No chest-strap attached |

---

## 3. Indoor bike — Multiple FTMS fields repurposed or set to garbage constants

### What the spec says

The Indoor Bike Data characteristic (0x2AD2) carries the following when the corresponding
flag bits are set:
- Bit 0 (cleared) → Instantaneous Speed (UINT16, km/h × 0.01)
- Bit 8 → Expended Energy block: Total Energy (UINT16 kcal) + Energy/hr (UINT16 kcal/hr) + Energy/min (UINT8 kcal/min)
- Bit 10 → Metabolic Equivalent (UINT8, × 0.1 MET)

### What BH Fitness indoor bikes send (flags 0x0F54)

The device sets flags bits 0, 2, 4, 6, 8, 9, 10, 11 but does NOT fill several of the
corresponding fields with real data:

| Field | Raw value (example) | Parsed by spec | Actual meaning |
|-------|---------------------|----------------|----------------|
| Speed (bit 0) | 0x0974 = 2420 | 24.2 km/h | **Stride counter × 100** — same bytes as Total Energy |
| Total Energy (bit 8) | 0x0974 = 2420 | 2420 kcal | **Stride counter × 100** — divide by 100 → strides/min |
| Energy/hr (bit 8) | 0x5400 = 21504 | 21504 kcal/hr | **Garbage constant** — always `00 54` (LE) |
| Energy/min (bit 8) | 0x00 | 0 kcal/min | Zero; not meaningful |
| MET (bit 10) | 0x7B = 123 | 12.3 MET | **Constant** — 0x7B across all packets; not a real reading |
| Distance (bit 4) | 0 | 0 m | Always 0; device does not report it |
| Elapsed Time (bit 11) | 0 | 0 s | Always 0; device does not report it |

**Key finding:** The Speed field and the Total Energy field always contain the **same
raw bytes**.  Both encode the stride counter.  The FTMS speed scaling (× 0.01) and the
divide-by-100 interpretation of Total Energy happen to produce the same quotient, causing
the displayed "speed" and "strides/min" to be identical — the reported bug.

### Reliable fields on BH indoor bikes

| Field | Notes |
|-------|-------|
| Cadence (rpm) | Correct; scales at × 0.5 |
| Instantaneous Power (W) | Correct; reasonable values |
| Heart Rate (bpm) | Correct when chest strap attached |

### Implementation

`BhFitnessIndoorBike.onDataReceived()` now:
- Zeroes `speedKmh` and `averageSpeedKmh` (not real speed)
- Derives `stridesPerMin = totalEnergyKcal / 100.0`
- Zeroes `totalEnergyKcal`, `energyPerHourKcal`, `energyPerMinuteKcal` (garbage/repurposed)
- Zeroes `metabolicEquivalent` (constant 0x7B, not a real reading)

See [bh-fitness-indoor-bike.md](bh-fitness-indoor-bike.md) for the full packet decode.

---

## 4. Control Point characteristic properties

The Fitness Machine Control Point (0x2AD9) on BH Fitness iConcept devices has GATT
properties **NOTIFY | WRITE_NO_RESPONSE** (property bitmask = 24) instead of the
spec-mandated **INDICATE | WRITE** (bitmask = 40).

The app therefore:
- Enables notifications (not indications) for this characteristic.
- Uses `writeCharacteristic` without the *signed write* variant.

Writing the *Request Control* opcode (`0x00`) to this characteristic triggers the
device to begin streaming data on the iConcept proprietary channel.

---

## Known-good device names

The `FitnessDevice.isBhFitness(name)` heuristic matches the following prefixes /
substrings (case-insensitive):

- `bh`
- `i.concept`
- `bhfitness`
- `bh fitness`

Add new entries here when a new BH model is confirmed to use the same quirks.

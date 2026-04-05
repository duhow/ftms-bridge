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

`BhFitnessTreadmill.onDataReceived()` applies the correct formula based on whether the
device is currently in decline mode (see below).

The UI displays inclination as a **rounded integer** (e.g. `3` instead of `2.88`),
matching the whole-number percent labels shown on the machine console.

### Negative inclination (T01 model)

The T01 model **does not** use negative INT16 values for decline.  Instead it sends
**positive** raw values in the range [300, 550] that are decoded with a different
formula:

```
actual_percent = (raw - 500) / 62.5
```

Confirmed observations (session 2026-04-05, CC:79:88:30:7D:57):

| Console display | Raw INT16 | Standard formula (raw/62.5) | Decline formula ((raw-500)/62.5) |
|-----------------|-----------|-----------------------------|----------------------------------|
| −1 %            | 450       | +7.2 % (wrong)              | **−0.8 % → rounds to −1 %**     |
| −2 %            | 380       | +6.1 % (wrong)              | **−1.9 % → rounds to −2 %**     |
| −3 %            | 320       | +5.1 % (wrong)              | **−2.9 % → rounds to −3 %**     |

Because the decline raw range [300, 550] overlaps with the positive incline range
(e.g. raw=450 = +7.2% when inclining), the two modes are distinguished by the
**transition behaviour**: when the user presses the decline button the raw value
**jumps in a single step** from flat (raw=0) to ~450, whereas positive incline
increases **gradually** in ~60-unit steps.

`BhFitnessTreadmill` tracks this transition with a `declineMode` flag:
- Enter decline mode: raw jumps from <50 to >270 in one packet.
- Exit decline mode: raw drops back below 50 (machine returns to flat).
- A `hasSeenFlat` guard prevents false triggers when connecting to a machine that is
  already at high positive incline (the machine must pass through flat first).

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

**Distance and energy fallback:** Because C112 does not continue streaming, the app now
derives:
- `distance_m += speed_kmh * dt * (1/3.6)`
- `energy_kcal` from ACSM treadmill metabolic equations (speed + grade), using a default
  body mass assumption for a practical estimate.

When an initial C112 snapshot is available, derived counters start from at least those
values and continue increasing from live FTMS speed/incline.

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
- Uses repurposed speed bytes only for internal distance integration; exported/displayed
  indoor-bike `speedKmh` is forced to 0 to avoid duplicating stride value
- Derives `stridesPerMin = totalEnergyKcal / 100.0`
- Derives cumulative `totalDistanceM` from synthetic speed over time
- Derives cumulative `totalEnergyKcal` from power over time
- Derives indoor-bike `resistanceLevel` (console level 1..11) from power+cadence torque
- Filters one-packet cadence/speed spikes using absolute and time-scaled delta limits
- Zeroes `energyPerHourKcal`, `energyPerMinuteKcal` (garbage/repurposed)
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

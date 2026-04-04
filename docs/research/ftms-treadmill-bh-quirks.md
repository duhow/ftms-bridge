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

### Negative inclination

Because the field is signed INT16, negative raw values (downhill) work correctly
once the standard Kotlin `buf.short.toInt()` signed-extend is applied.
Example: raw `−62` → −62 × 0.1 / 6.25 = **−0.99 %** (slightly downhill).

---

## 2. Elapsed time / total distance / total energy not reported via FTMS

The standard Treadmill Data flags for elapsed time (bit 10), total distance (bit 2),
and expended energy (bit 7) are technically set in the packet observed from BH Fitness
devices, but the device always sends **0** for all three fields throughout the workout.

The actual counters are streamed via the proprietary **0xC112** iConcept channel.
See [bh-fitness-iconcept.md](bh-fitness-iconcept.md).

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

## 3. Indoor bike — Total Energy field repurposed for stride rate

### What the spec says

The Total Energy field (UINT16, kcal) in Indoor Bike Data (0x2AD2) reports cumulative
expended energy.

### What BH Fitness indoor bikes send

BH Fitness indoor bikes send **strides per minute × 100** in this field.

```
Example: field = 8500 → 8500 / 100 = 85.0 strides/min
```

### Implementation

`BhFitnessIndoorBike.onDataReceived()` reads the field, divides by 100 to get
`stridesPerMin`, and zeroes out `totalEnergyKcal` so the UI does not display a
misleading calorie count.

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

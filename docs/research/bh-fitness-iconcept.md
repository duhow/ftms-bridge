# BH Fitness iConcept 3.0 — Proprietary BLE Service

> Source: reverse-engineered from live BLE captures on a BH Fitness treadmill.
> Fields marked **⚠️ unverified** were deduced from structural reasoning, not
> confirmed with multiple data points or manufacturer documentation.

## Service & characteristic UUIDs

All UUIDs use the Bluetooth base suffix `0000xxxx-0000-1000-8000-00805f9b34fb`.

| Short UUID | Full UUID | Role | Properties |
|------------|-----------|------|------------|
| `0xC100`   | `0000c100-…` | iConcept service root | — |
| `0xC111`   | `0000c111-…` | Notify channel 1 | NOTIFY |
| `0xC112`   | `0000c112-…` | Notify channel 2 — workout counters | NOTIFY |

The `0xC109` characteristic is believed to be a write channel used to send commands
to the device, but has not been confirmed in captures.

---

## Connection handshake

After subscribing (CCCD write) to all FTMS and iConcept characteristics, the app
writes the FTMS *Request Control* opcode (`0x00`) to the Fitness Machine Control
Point (`0x2AD9`).  On BH Fitness devices this control-point characteristic has
properties `NOTIFY | WRITE_NO_RESPONSE` (props = 24) rather than the standard
`INDICATE | WRITE`.  Writing `0x00` triggers the treadmill to start streaming
workout-counter data on `0xC112`.

---

## 0xC112 — Workout counter packet

### Observed raw packets (all little-endian)

```
# Treadmill at rest, ~2 s after workout start  (speed = 6.10 km/h, inclination raw = 180)
F1 0D  02 00  00 00 00  00 00  00 00  FF FF  00 00  02  FE F0  00 00
```

### Byte map

```
Offset  Len  Type     Name                Notes
------  ---  -------  ------------------  ----------------------------------------
 0       1   UINT8    header              Always 0xF1 for this packet family
 1       1   UINT8    sub-type            0x0D = workout-counter message
 2–3     2   UINT16   elapsed_time_sec    Seconds since workout start
 4–6     3   UINT24   distance_m          Total distance in metres (same encoding as FTMS 0x2ACD)
 7–8     2   UINT16   total_kcal          Total energy; 0xFFFF = not available
 9–10    2   ??       unknown             Always 0x0000 in observed captures
11–12    2   ??       unknown             0xFFFF in observed captures — possibly another energy
                                          field (kcal/h?) reported as "not available"
13–14    2   ??       unknown             Always 0x0000 in observed captures
15       1   ??       unknown             Value 0x02 in observed captures ⚠️ unverified
16–17    2   ??       unknown             0xFEF0 (LE) in observed captures ⚠️ unverified
                                          Does not map to any obvious workout metric at 6.10 km/h
18–19    2   ??       unknown             Always 0x0000 in observed captures
```

### Notes

- The workout counter packet is 20 bytes regardless of which fields are populated.
- The device sends `0xFFFF` for `total_kcal` when the value is genuinely unavailable
  (e.g. no chest-strap HR sensor attached and no MET estimate), **not** when the value
  is simply zero.  The parser must treat `0xFFFF` as zero / not-available.
- Bytes 15–19 are not yet decoded.  `0xFEF0` at bytes 16–17 is consistent across all
  samples but does not match speed (6.10 km/h = 0x0262 in FTMS units) or inclination.
  It may be a firmware version, a device-internal mode byte, or a checksum.
- Only sub-type `0x0D` is confirmed.  Other sub-types may exist (see 0xC111 below).

### Kotlin parser

`FtmsDataParser.parseIConceptWorkoutData(data: ByteArray): FitnessSample?`

---

## 0xC111 — Notify channel 1

No data collected yet.  The characteristic is subscribed to at connection time alongside
0xC112 but no notifications were observed during the captures that produced this
documentation.

---

## 0xC112 — Duplicate [INFO] log line (historic bug)

Prior to the deduplication fix, the `onCharacteristicChanged` handler logged two lines
per iConcept notification:

```
[NOTIFY] 0000c112-…  (20 bytes): F1 0D …    ← from logEvent("NOTIFY", …)
[INFO]   iConcept notify 34fb: F1 0D …       ← from logMessage(…)
```

The `takeLast(4)` call on the UUID string yielded `34fb` (the Bluetooth base UUID
suffix) for **both** C111 and C112, making the log entry useless for distinguishing
the two channels.  The correct approach is `uuid.toString().substring(4, 8)` which
yields `c111` / `c112`.

Both redundant issues are resolved: the [INFO] line is removed (the deduplicated
[NOTIFY] line carries the same information), and the UUID substring is fixed where
needed.

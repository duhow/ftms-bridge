# Debug Log Noise Patterns

This file documents BLE notification patterns that generate high-volume repetitive
log entries with zero diagnostic value, the root cause of each, and the mitigation
applied.

---

## Pattern 1 — Heart-rate "00 00" flood (0x2A37)

### Symptom

```
[NOTIFY] 00002a37-… (2 bytes): 00 00
[NOTIFY] 00002a37-… (2 bytes): 00 00
[NOTIFY] 00002a37-… (2 bytes): 00 00   ← repeats ~2 Hz indefinitely
```

### Cause

The Heart Rate Measurement characteristic (0x2A37) is subscribed at connection time
even when no HR sensor is attached.  The device sends `flags=0x00, hr=0x00` at its
natural notification cadence (~2 Hz) as long as no sensor is detected.

### Mitigation

`BtDebugLogger.logEvent()` maintains a per-UUID cache of the last logged payload.
If the incoming payload is byte-for-byte identical to the cached value the line is
silently dropped.  The first packet and every **change** still appear in the log.

The cache is cleared in `stopSession()` so a reconnect always logs the first fresh
packet.

---

## Pattern 2 — Unchanged iConcept counter packets (0xC112)

### Symptom

```
[NOTIFY] 0000c112-… (20 bytes): F1 0D 02 00 00 00 00 00 00 00 00 FF FF 00 00 02 FE F0 00 00
[INFO]   iConcept notify 34fb: F1 0D 02 00 …    ← duplicate of the line above
[NOTIFY] 0000c112-… (20 bytes): F1 0D 02 00 …   ← identical again, ~3 Hz
[INFO]   iConcept notify 34fb: F1 0D 02 00 …
```

### Cause

Two issues compounded:

1. The characteristic streams at ~3 Hz.  When the workout is at steady state (constant
   speed, no elapsed-time increment between two BLE poll cycles), the same 20-byte
   payload repeats many times.
2. `onCharacteristicChanged` called `logEvent("NOTIFY", …)` **and** `logMessage("iConcept notify …")`,
   producing two lines for every single notification.

### Mitigation

- The per-UUID NOTIFY deduplication (Pattern 1 fix) suppresses repeated identical
  `[NOTIFY]` lines.
- The redundant `[INFO] iConcept notify …` `logMessage` call is removed; the
  deduplicated `[NOTIFY]` line already carries the UUID and full hex payload.

---

## Pattern 3 — Unchanged treadmill data packets (0x2ACD)

### Symptom

```
[NOTIFY] 00002acd-… (17 bytes): 8C 01 62 02 00 00 00 B4 00 00 00 00 00 00 00 00 00
[NOTIFY] 00002acd-… (17 bytes): 8C 01 62 02 00 00 00 B4 00 00 00 00 00 00 00 00 00
[NOTIFY] 00002acd-… (17 bytes): 8C 01 62 02 00 00 00 B4 00 00 00 00 00 00 00 00 00
```

### Cause

Same as Pattern 2: ~2–3 Hz notification cadence with no payload change during
steady-state exercise.

### Mitigation

Per-UUID NOTIFY deduplication in `BtDebugLogger.logEvent()`.

---

## Pattern 4 — CCCD write confirmation spam

### Symptom

```
[INFO] CCCD write 2acd
[INFO] Descriptor write for 00002acd-…: status=0
[INFO] CCCD write 2ad7
[INFO] Descriptor write for 00002ad7-…: status=0
[INFO] CCCD write 2ad3
[INFO] Descriptor write for 00002ad3-…: status=0
[INFO] CCCD write 2ad9
[INFO] Descriptor write for 00002ad9-…: status=0
[INFO] CCCD write 2a37
[INFO] Descriptor write for 00002a37-…: status=0
[INFO] CCCD write c111
[INFO] Descriptor write for 0000c111-…: status=0
[INFO] CCCD write c112
[INFO] Descriptor write for 0000c112-…: status=0
```

### Cause

The app serialises CCCD writes through a `GattOp` queue.  For a typical BH Fitness
treadmill with 7 notifiable characteristics this produces 14 consecutive log lines
(7 "initiation" + 7 "completion") every time the device is connected.  The
`onDescriptorWrite` callback always logs status=0 (success), which carries no
information beyond "it worked".

### Mitigation

`onDescriptorWrite` only logs if `status != BluetoothGatt.GATT_SUCCESS` (i.e. only
failures).  The initiation log line (`CCCD write xxxx`) is retained so the sequence
of setup operations remains visible in the log.

Result: 7 lines instead of 14 for a successful connection setup.

---

## Summary table

| Pattern | UUID | Mitigation | Location |
|---------|------|------------|----------|
| HR `00 00` flood | `0x2A37` | Per-UUID NOTIFY dedup | `BtDebugLogger.logEvent` |
| iConcept unchanged | `0xC112` | Per-UUID NOTIFY dedup + remove duplicate `[INFO]` line | `BtDebugLogger.logEvent`, `BleConnectionManager` |
| Treadmill unchanged | `0x2ACD` | Per-UUID NOTIFY dedup | `BtDebugLogger.logEvent` |
| CCCD write confirmations | any | Only log failures | `BleConnectionManager.onDescriptorWrite` |

# Protocol Research Notes

This folder documents reverse-engineered BLE protocol behaviour discovered during
development of FTMS Bridge. Each file covers one device family or characteristic.

## Index

| File | Contents |
|------|----------|
| [bh-fitness-iconcept.md](bh-fitness-iconcept.md) | BH Fitness iConcept 3.0 proprietary BLE service (0xC100) — UUIDs, packet formats, known quirks |
| [ftms-treadmill-bh-quirks.md](ftms-treadmill-bh-quirks.md) | BH Fitness deviations from the FTMS spec on the standard 0x2ACD characteristic |
| [log-noise-patterns.md](log-noise-patterns.md) | Patterns that flood the debug log with zero-information entries, and the mitigations applied |

## How to use these docs

- When adding support for a new device, check here first — the quirk may already be documented.
- When a new packet format is observed in a debug log, annotate it in the relevant file and mark
  uncertain fields clearly (use `??` or a `> ⚠️ unverified` block-quote).
- Cross-reference the matching Kotlin parser code so both stay in sync.

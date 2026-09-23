# Changelog

All notable changes to BetterAC are documented here.

## Unreleased

Add new changes here before pushing the next release.

## 1.3 - 2026-09-22

### Added

- Added Killaura snap-hit and consistency checks.
- Split scaffold detection into place-rate, snap, and Telly checks.
- Added sword-specific AutoBlock tracking.

### Changed

- Raised the default violation threshold from 10 to 12.
- Tuned combat angle, snap, and MultiAura thresholds.
- Made Aim Snap require a combat swing and nearby target to reduce false positives.
- Increased violation decay frequency.

### Validation

- `gradlew.bat build` completed successfully.

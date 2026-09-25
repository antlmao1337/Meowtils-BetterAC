# Changelog

All notable changes to BetterAC are documented here.

## Unreleased

Add new changes here before pushing the next release.

## 1.5 - 2026-09-25

### Changed

- Loosened kill aura, aim snap, snap-hit, and aim-lock so a hitbox snap counts. It no longer has to land on the eyes and sit there perfectly.
- A single pattern now adds violation level immediately. A repeat adds more, so a real aura can reach chat without a long streak.
- Default violation threshold is 7. Snap threshold is 45°. On-target angle is 36°. Violation level decays more slowly and drops by 3 after a chat line instead of 6.
- Chat lines use one format. Repeats say Likely, then Confirmed. The same check will not print more than once every 1.5 seconds.

### Validation

- `gradlew.bat build` completed successfully.

## 1.4 - 2026-09-25

### Changed

- Rebuilt aim-snap, killaura snap-hit, killaura angle, killaura consistency, and multiaura so they use the crosshair and the hitbox instead of the nearest player.
- Rotation packets are queued as they arrive and judged together on the client tick. A turn no longer stays "armed" until later swings.
- Aim Snap now needs repeated snaps that land on the eyes and stay there. Snap-Hit now needs the rotation to return to the pre-snap angles (silent aura). One flick is not enough.
- Killaura Angle only builds when this player is the only nearby swinger and someone in reach takes damage while the crosshair is off them.
- Consistency now looks at aim-error spread on a moving target, not at "they aimed well."
- Multiaura only counts players whose hitbox is actually under the crosshair. The local player counts as a target.
- One frame can add at most 8 violation level, so a single bad tick cannot confirm a cheat by itself.
- New threshold: On-Target Angle.

### Validation

- `gradlew.bat build` completed successfully.

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

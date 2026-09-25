# Meowtils BetterAC

A client-side Meowtils extension for Minecraft 1.8.9 that provides heuristic anti-cheat checks against nearby players.

See [CHANGELOG.md](CHANGELOG.md) for a readable history of changes. GitHub's commit history shows the exact code diff for each update.

BetterAC watches client-visible movement, rotations, swings, block placements, and entity packets. It assigns violation levels and reports repeated patterns locally through Meowtils.

## Features

- **Combat checks**
  - Killaura: swings while a nearby player takes damage and the crosshair is nowhere near them
  - Killaura snap-hit: snap onto a hitbox, then restore the old rotation
  - Killaura consistency: aim error that barely moves while the target is moving
  - MultiAura: several hitboxes pass under the crosshair in a short window
  - AutoBlock behavior
  - Aim snap: repeated snaps that land on a player's hitbox and stay there

- **Movement checks**
  - NoSlow movement while using items
  - Scaffold placement rate and rotation patterns
  - Legit-scaffold / assisted-bridging heuristics

- **Client-side feedback**
  - Configurable violation threshold
  - Optional debug messages
  - Optional flag sound
  - Bot/NPC name filtering
  - Per-player violation decay

## Checks

| Check | Purpose |
|-------|---------|
| Killaura Angle | Flags repeated swings toward a nearby hurt player while the crosshair is outside the configured max angle |
| Killaura Pitch | Flags repeated invalid or out-of-range pitch values in combat rotation data |
| Killaura Snap-Hit | Detects a snap onto a target hitbox followed by a restore to the previous rotation |
| Killaura Consistency | Detects unusually stable aim error while the target is moving |
| MultiAura | Detects distinct target hitboxes passing under the crosshair in short and medium windows |
| AutoBlock | Flags sword swings while an item is being used |
| NoSlow | Looks for unusually high movement while using an item |
| Aim Snap | Detects repeated one-step snaps that land on a hitbox and stay there |
| Scaffold | Scores rapid placement, snap-to-place rotation, and telly-bridging pitch patterns |
| Legit Scaffold | Detects repeated assisted-bridging indicators |

### Threshold options

| Option | Range | Default | Used by |
|--------|-------|---------|---------|
| VL Threshold | 4–20 | 7 | All checks; violation level required before a chat flag |
| Max Angle | 50–130° | 90° | Killaura Angle and target visibility checks |
| MultiAura Ticks | 2–8 | 3 | MultiAura short-window target switching |
| Snap Threshold | 30–90° | 45° | Killaura Snap-Hit, Aim Snap, and rotation snap heuristics |
| On-Target Angle | 18–48° | 36° | Hitbox alignment used by snap and combat checks |

## Installation

1. Install Meowtils for Minecraft 1.8.9.
2. Build the extension or download a release when one is available.
3. Place the resulting `.meowtils` file in the Meowtils extensions directory.
4. Restart the client and enable **BetterAntiCheat** in Meowtils.

## Configuration

The extension exposes its settings through the Meowtils module configuration:

- Enable or disable individual checks
- Change the violation-level threshold
- Tune angle, snap, on-target, and timing thresholds
- Toggle debug messages and flag sounds
- Ignore or include likely NPC/bot entities

## Build

Requires Java 8 and the Meowtils development jar.

Place the compatible Meowtils jar at `libs/meowtils.jar`, then run:

```bash
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

The extension is written for Minecraft 1.8.9 and is output with the `.meowtils` file extension.

## Notes

- This is a client-side heuristic tool; it does not enforce punishments on a server.
- Packet visibility, latency, entity behavior, and server implementations can produce false positives or false negatives.
- Flags should be treated as indicators for review, not definitive proof of cheating.
- The project intentionally excludes generated Gradle/build output and local dependency jars.

## Credits

Developed as a focused client-side anti-cheat extension for Meowtils and Minecraft 1.8.9.
For questions, problems, or suggestions, please use the repository's issue tracker.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the commit and pull-request format.

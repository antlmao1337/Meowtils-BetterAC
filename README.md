# Meowtils BetterAC

A client-side Meowtils extension for Minecraft 1.8.9 that provides heuristic anti-cheat checks against nearby players.

BetterAC watches client-visible movement, rotations, swings, block placements, and entity packets. It assigns violation levels and reports repeated patterns locally through Meowtils.

## Features

- **Combat checks**
  - Killaura angle heuristics
  - MultiAura target switching
  - AutoBlock behavior
  - Aim-snap detection

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
| Killaura Angle | Compares attack direction with nearby targets |
| MultiAura | Tracks distinct targets attacked in a short window |
| AutoBlock | Flags swings while an item is being used |
| NoSlow | Looks for unusually high movement while using an item |
| Aim Snap | Detects large head-rotation changes around attacks |
| Scaffold | Scores rapid, aligned, or repeated block placement patterns |
| Legit Scaffold | Detects repeated assisted-bridging indicators |

## Installation

1. Install Meowtils for Minecraft 1.8.9.
2. Build the extension or download a release when one is available.
3. Place the resulting `.meowtils` file in the Meowtils extensions directory.
4. Restart the client and enable **BetterAntiCheat** in Meowtils.

## Configuration

The extension exposes its settings through the Meowtils module configuration:

- Enable or disable individual checks
- Change the violation-level threshold
- Tune angle, snap, and timing thresholds
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

# Contributing

Thanks for taking a look at BetterAC. Small fixes and useful reports are welcome, especially when they include the Minecraft version, Meowtils version, enabled checks, and a short description of what happened.

## Before opening a pull request

Please check the existing issues first. For code changes, keep the pull request focused on one thing and explain the reason for the change. Anti-cheat changes should call out any new thresholds, configuration fields, chat messages, or situations that may cause false positives.

Run the build from the project directory before pushing:

```bat
gradlew.bat clean build
```

If the change affects detection behavior, include a quick note about how you tested it. A short reproduction, replay, or debug-log excerpt is more useful than a general statement that it was tested.

## Commits

Use a short imperative subject and keep it under about 72 characters. For example:

```text
Fix snap-hit restore timing
Document current check thresholds
Reduce NoSlow false positives
```

The commit body is optional, but use it when the reason for a change is not obvious from the subject.

## Pull request checklist

- The change is limited to the stated problem.
- `gradlew.bat clean build` completes successfully.
- README or changelog entries are updated when behavior or configuration changes.
- Generated files and the local `libs/meowtils.jar` dependency are not included.
- Known limitations and false-positive risks are mentioned.

BetterAC is a client-side heuristic tool. A flag is an indicator for review, not proof that someone is cheating, so reports and changes should keep that distinction in mind.

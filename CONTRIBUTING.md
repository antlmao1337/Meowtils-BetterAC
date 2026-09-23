# Contributing

Keep each commit focused on one logical change. GitHub automatically shows the exact file diff for every commit, so commit messages should explain the intent rather than list every line edited.

## Commit format

Use an imperative subject under 72 characters:

```text
Add scaffold snap detection
Fix false positives in AutoBlock
Tune combat violation thresholds
```

In the commit body or pull request description, include:

- What changed and why
- Any behavior or configuration changes
- How it was tested, for example `gradlew.bat build`
- Known limitations or false-positive risks

## Before pushing

```bat
git status
git diff
gradlew.bat build
git add .
git commit -m "Describe the change"
git push
```

Do not commit generated build output or the local Meowtils dependency jar; those paths are ignored by `.gitignore`.

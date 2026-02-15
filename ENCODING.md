# Encoding Rules

Use these rules to prevent Korean text corruption and broken quotes.

## Required

- Save all source/resource files as UTF-8.
- Use LF line endings in repository files.
- Do not use default `Set-Content` without `-Encoding utf8`.

## Preferred editing path

- Prefer Android Studio or `apply_patch` for code edits.
- Avoid ad-hoc shell rewrites for `.kt` and `.xml`.

## Quick check before commit

- Run `git diff` and verify Korean strings are readable.
- Run `.\gradlew.bat --no-daemon assembleDebug` to catch syntax breakage.

# Localization Guide

This project is now prepared for multi-language releases with:

- `app/src/main/res/values/strings.xml` as default strings.
- `app/src/main/res/values-ko/strings.xml` for Korean.
- XML menus/layouts moved to `@string/...` references (major UI surfaces).
- `lint.xml` with `HardcodedText` as error.

## Rules

1. Never add user-facing text directly in XML or Kotlin.
2. Add new keys to `values/strings.xml` first.
3. Add translations in `values-ko/strings.xml` (and future locales like `values-ja`, `values-es`).
4. For dynamic messages, use placeholders:
   - `%1$s` for strings
   - `%1$d` for integers

## Kotlin usage

Use:

```kotlin
getString(R.string.some_key)
getString(R.string.some_count_message, count)
```

## Release checklist

1. Run `./gradlew lint` (or `.\gradlew.bat lint` on Windows).
2. Fix `HardcodedText` errors.
3. Verify key screens in each locale.

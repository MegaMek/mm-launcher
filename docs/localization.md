# Localization

The launcher uses the same string-loading mechanism as MegaMek and MekHQ.
[`I18n`](../src/main/java/org/megamek/launcher/internationalization/I18n.java) is a copy of MegaMek's
`megamek.common.internationalization.I18n`; MekHQ keeps the same copy as `MHQInternationalization`. The
launcher runs without MegaMek's jar, so it can't call MegaMek's class directly.

- `I18n.getTextAt(bundle, key)` and `I18n.getFormattedTextAt(bundle, key, args...)` read a bundle.
  `getText` and `getFormattedText` read the default bundle, `org.megamek.launcher.messages`.
- Bundles are UTF-8 `.properties` files under `src/main/resources`. A missing key shows as `!key!`.
- Values read with `getFormattedText*` are `java.text.MessageFormat` patterns: write a single quote as
  `''`, and use `{0}`, `{1}` and so on for the values the code fills in.

## Language

Settings ▸ Appearance ▸ **Language** offers **System language** (the default), English, Deutsch,
Русский and Español, in the same order as MegaMek's `CommonSettingsDialog.LOCALE_CHOICES`. The
choice is stored in launcher settings and applies the next time the launcher opens. System language
uses the operating system's language. A language the launcher has no translation for, and any key a
translation is missing, falls back to English.

What is translated: everything a player reads in the launcher window and its dialogs. What stays in
English: log files, exception details, bug-report content and command-line output, so that logs and
bug reports stay readable for the team.

### Text that reaches both the player and the logs

Some text is shown on screen and also written to logs. Progress steps, launcher update results, error
titles and a few error messages are examples. Code passes this text as a
[`LocalizedText`](../src/main/java/org/megamek/launcher/internationalization/LocalizedText.java) (a
bundle key plus arguments) instead of an already-translated string:

- `LocalizedText.of(bundle, key, args...)` builds it. With arguments, the value is a `MessageFormat`
  pattern, the same as `getFormattedTextAt`. `LocalizedText.literal(text)` wraps text that isn't
  translated.
- `text()` is in the player's language, for the screen. `english()` is for logs and diagnostics.
- `OperationContext.phase`, `progress` and `cleanupPhase` take a `LocalizedText`. The progress
  dialog shows `OperationProgress.displayDetail()`, and operation logs keep the English
  `detail()`.
- [`LocalizedIOException`](../src/main/java/org/megamek/launcher/internationalization/LocalizedIOException.java)
  returns English from `getMessage()` and the player's language from `getLocalizedMessage()`.
  `SanitizedErrors.display` uses the localized message, and `SanitizedErrors.persistent` uses the
  English one.
- Error dialogs show their title in the player's language. Diagnostics record the English title.

## Bundles

| Bundle | Covers |
| --- | --- |
| `org/megamek/launcher/messages` | Shared launcher text such as the Language setting |
| `org/megamek/launcher/gui/frame` | The main window: Home, Installations and Settings |
| `org/megamek/launcher/gui/dialogs` | Dialogs: progress, errors, alerts, bug report, What's new, launch options |
| `org/megamek/launcher/gui/home` | Home and first-launch controls, the intro and the news feed |
| `org/megamek/launcher/design/host/messages` | Design-system controls such as Close, Help and Retry |
| `org/megamek/launcher/services` | Progress steps and summaries from installs, updates and imports |

## Adding a translation

1. Each bundle has an empty `_de`, `_es` and `_ru` file beside it, for example
   `gui/dialogs_de.properties`. Copy keys from the English file into it and translate the values.
   Keys you leave out show in English.
2. Keep every `{0}` placeholder, and keep `''` wherever the English value has it.
3. Run `.\gradlew.bat test`. The bundle tests check that each translation only uses keys the English
   file has, and that formatted values still parse.

A new language also needs its tag added to `I18n.LOCALE_CHOICES`, matching MegaMek's list.

## Windows installer

The Windows installer's own MSI dialogs read their text from
`src/distribution/windows/msi/MegaMekLauncher_en-us.wxl` through `!(loc.…)` ids in `ui.wxs`, and the
setup `.exe` reads its screens from `src/distribution/windows/bundle/theme.wxl`. The build passes only
the English files to WiX and makes one English installer, so shipping a translated installer needs a
build change first (for example one MSI per language). Only English is written today.

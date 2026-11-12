# Selia Speak UI

Voice: plain, quiet, factual. Explain the next action.

## Tokens

* Ink `#16243C`, Canvas `#F4F7FB`, White `#FFFFFF`.
* Azure `#245BDB`, Teal `#14735A`, Danger `#B3261E`.
* System sans for content. System monospace for model sizes and measurements.
* Spacing: 4, 8, 12, 16, 24, 32 dp. Minimum control target: 48 dp.
* Signature: an S-shaped speech waveform. No decorative motion.

## Layout

```text
Selia Speak
Ready to dictate / next required action
Selected model
Language and processing mode

[Stop / Resume]
[Choose model]
[Permissions]

Advanced settings [collapsed]
Privacy, terms and data deletion
```

Onboarding has an introduction and a permission explanation. The user can reach
the dashboard before finishing setup. Status names the missing step.

The model browser uses search, language and capability filters. Model rows show
download size separately from memory requirements. Missing measurements remain
unknown. Error rates sort only within a matching language, metric and test.

Advanced settings contain manual/automatic language, installed-model routing,
optional local text correction, live preview, and a temporary dictation field.
They do not create recording or transcript history.

## Verification

Inspect portrait, landscape, large text, dark mode, keyboard visibility, download
errors, permission denial and return from Android settings on a real device.
Parent owns integrated build and device checks.

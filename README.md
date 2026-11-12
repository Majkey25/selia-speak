# Selia Speak

Private Android dictation. Keep your keyboard and choose your local speech model.

Development build. Native speech recognition passed on Huawei Android 10 and an
Android 9 emulator, including offline inference. Full floating-dictation acceptance
is still pending; see [QA](docs/QA.md).

Android 9 or later, with an arm64 phone or x86_64 emulator. Application ID:
`com.majkeylab.seliaspeak`. English and Czech UI follows the device language.
Speech languages are independent of interface language.

## Use

1. Finish the short introduction and grant microphone access.
2. Read the separate accessibility explanation and enable Selia Speak's service.
3. Search the model catalog by language. Download and select a speech model.
4. Open an editable field with your usual keyboard. Drag the floating microphone
   to a comfortable position, tap to start, and tap again to finish.

The dashboard shows permissions, the selected model, and whether dictation is
enabled. Several models can be installed; one recognizer runs at a time.
Selected, configured, or in-use models cannot be deleted.

## Models and language modes

The verified catalog includes multilingual Whisper Tiny/Base/Small INT8,
Omnilingual CTC 300M, an English streaming Zipformer, a separate Whisper language
detector, and optional Qwen text editing. Exact revisions, sizes, hashes,
languages, and licenses live in [ModelCatalog.kt](app/src/main/java/cz/localvoice/app/ModelCatalog.kt).
The catalog is a checked set of compatible exports, not every model on the internet.

Search and filter by language, download size, automatic language support, and
native streaming. Unknown RAM requirements and WER/CER measurements stay unknown.
Error-rate sorting compares matching language, metric, dataset, and method.
Model-declared language support does not establish equal quality in every language.

Advanced settings are collapsed by default:

* Choose a manual language when the model accepts a hint, or automatic transcription.
* Install a separate language detector and map its supported languages to installed
  recognizers. Detection uses initial audio, then the recognizer receives the whole
  recording. Unmapped languages produce an error.
* Enable live previews with a native streaming model. Whisper and Omnilingual are
  batch models here and finish transcription after Stop.
* Install the optional experimental local text model to process corrections after Stop. It can
  replace recent text or discard the current dictation for a replacement. It cannot
  rewrite unseen older text outside its bounded context. Review important output.

Live semantic correction while recording is not implemented. Streaming previews
contain speech recognition output; optional semantic editing runs after Stop.
The real-model semantic acceptance suite currently fails on literal quoted output.
This feature remains off by default and is not release-ready.
Recording is bounded to 15 minutes and text to 50,000 UTF-16 characters.
Hardware memory, latency, battery use, and language accuracy require device checks.

## Privacy and safe insertion

No account, advertising, analytics, API key, or cloud transcription. Downloads
contact Hugging Face and verify pinned byte counts and SHA-256 hashes. Dictation
audio stays in RAM; transcript history is off. Recording requires an explicit gesture.

Accessibility identifies the active field and inserts requested text. Fields
reported as passwords, numeric or phone inputs are blocked. Compose editors that
omit their input type are supported only as editable text controls with a text
replacement action. Their numeric intent cannot be inferred reliably; password
flags still block access. Interim updates replace only this
dictation's span. Field, cursor, and application changes block further updates.
Cancel and Undo restore text only while the field still matches.
Copy requires an explicit action and shares text with the system clipboard.

[Privacy, terms, and deletion](https://majkey25.github.io/selia-speak/).

## Build and verify

Use JDK 17, Android SDK 36, and the repository's Gradle wrapper:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is for local
testing; it is not a Google Play production signing configuration.

The explicit `SemanticModelInstrumentedTest` requires the catalog editing model.
It exercises real inference and fails rather than skips when the model is missing.
See [QA](docs/QA.md) for evidence and gaps.

## License and origin

Original Selia Speak material is source-visible proprietary. Official binaries
may be used without charge under [LICENSE](LICENSE); additional source or
commercial rights require written permission. This is not an open-source license.

Based on [Local Voice at 51181a9](https://github.com/Majkey25/local-voice-android/tree/51181a9b9646afe4c3509a8f479a8cdacf0eabe2).
That previously published material retains its MIT grant. Dependencies, models,
and contributors retain their rights. See [third-party notices](THIRD_PARTY_NOTICES.md).

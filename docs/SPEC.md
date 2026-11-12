# Selia Speak contract

## Primary flow

Introduction, permission disclosure, verified model installation, selection, then
a dashboard with status, stop/resume, permissions, and model selection.

The normal keyboard remains selected. The movable accessibility overlay requires
a visible keyboard, eligible focused field, accepted disclosure, and enabled
dictation. Tap toggles recording; holding supports push-to-talk. Cancel and an
eight-second Undo are separate controls.

Reported password, numeric and phone input types are refused. Some Compose editors
report no input type. Compatibility requires an editable Android EditText with a
text replacement action, plus every existing visibility, password and package
guard. Opaque numeric intent cannot be inferred and is not claimed to be blocked.

## Ownership

* SpeakSettings owns bounded, versioned, atomically committed configuration.
* ModelCatalog owns compatible exports, capabilities, provenance, sizes, languages,
  and optional comparable evaluation records.
* ModelRepository owns installation, hashes, readiness, resumable partials,
  mutation exclusion, shared assets, deletion, and runtime leases.
* LocalRecognizer and SpeechLanguageDetector own native resources. One ASR or
  language detector holds the process speech slot.
* VoiceAccessibilityService owns recording sessions, native work ordering,
  targets, provisional writes, cancellation, and final insertion.
* FieldDraft owns only the current dictation span and expected post-write field.
* SemanticEngine owns optional local text inference. Parsed output always passes
  through field guards; it has no direct document editing authority.

## Language selection

Normal mode uses one installed recognizer and a supported manual hint or automatic
transcription. A model that accepts no language hint receives none. Multilingual
recognition and explicit language identification are distinct.

Advanced routing identifies approximately the first three seconds, closes the
detector, and selects an explicitly configured installed recognizer. The captured
prefix is replayed. Missing, unknown, unmapped, and unsupported languages fail
explicitly. The current detector exposes no confidence score.

## Recording and editing

Audio is mono 16 kHz PCM held in bounded RAM, with a 900-second ceiling. Native
streaming produces provisional text during recording. Batch transcription finishes
after Stop. Simulated streaming is not advertised as native streaming.

Semantic editing runs after Stop in bounded UTF-8 chunks with a recent draft tail.
Operations append, replace that tail, replace the whole current draft, or report
unsupported editing. Global replacement affects only the current dictation.
Unseen older content cannot be rewritten. Semantic failure does not silently
insert raw text; explicit Copy remains available.

Text is bounded to 50,000 UTF-16 characters. Parsers consume complete values and
reject malformed syntax, duplicate/unknown fields, and wrong types without echoing
private payloads in errors.

## Distribution

Minimum API 28. Internal namespace remains cz.localvoice.app. New install identity
is com.majkeylab.seliaspeak. Google Play setup is a draft. Production publication,
cloud inference, accounts, desktop clients, voice cloning, and acoustic fine-tuning
are outside this Android iteration.

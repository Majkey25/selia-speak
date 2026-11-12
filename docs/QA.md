# Selia Speak verification

## Baseline

On 12 November 2026, inherited Local Voice revision
51181a9b9646afe4c3509a8f479a8cdacf0eabe2 passed 63 JVM tests, Android lint,
and debug assembly. The clean build took 2 minutes 14 seconds with 58 tasks.
This was a build baseline, not a hardware dictation benchmark.

## Integrated candidate

On the same Windows host, Selia Speak passed 87 JVM tests without failures or
skips, Android lint, debug assembly, and Android-test APK assembly.
The final local gate completed in 1 minute 37 seconds with 87 tasks, 25 executed
and 62 up to date.
This incremental time is not comparable to the clean baseline time.

The first attempt found an invalid JUnit method return type in ModelRepositoryTest.
Explicit runBlocking<Unit> fixed discovery; assertions remained unchanged.

New checks cover strict model/settings responses, installed language routing,
rare variants, real HTTP/filesystem installation, same-size corrupt weights,
response bounds, leases, blocked-transport cancellation, and session-owned editing.

## Hardware gates

Connected HUAWEI YAL-L21 reports Android 10 and arm64. Observed memory before
installation: 5,765,952 KiB total, 2,288,608 KiB available. Selia Speak installs
separately from Local Voice.

Physical onboarding, microphone denial/retry, accessibility disclosure, model
browser, model download and selection were exercised on Huawei. Android 9 API 28
x86_64 emulator installation, onboarding, browser and downloads also ran.

RecognitionModelInstrumentedTest passed on both devices with actual catalog
Zipformer weights and the pinned public 6,625 ms fixture. Huawei: 1,541 ms load,
127 ms to a nonempty partial after load, 2,295 ms total. Android 9 emulator:
1,202 ms load, 104 ms first partial, 2,135 ms total. These are single runs feeding
audio faster than real time, not microphone-to-screen latency or accuracy scores.

The same Android 9 test passed with Wi-Fi and mobile data disabled and dumpsys
connectivity reporting `Active default network: none`. Both settings were restored.

On Huawei, the UI automation helper suppresses normal accessibility services.
After closing it, dumpsys confirmed Selia Speak bound. Real Compose inspection
found that the resolved delegate omits inputType; narrow EditText/SET_TEXT support
was added with password and known numeric/phone guards retained. The helper later
failed twice with `Android snapshot helper returned insufficient foreground app content`.
The screenshot still showed the app, but physical UI automation stopped as required.
The emulator later disappeared from adb; no app crash cause was established.

End-to-end microphone/overlay insertion, routing, cancellation during native
inference, landscape, dark/large-text layouts, battery and thermal behavior remain
unverified. Model declaration is not proof of every language or phone.

SemanticModelInstrumentedTest checks real English/Czech corrections, global reset
after a long draft, and quoted literal commands. It requires actual weights and
contains no assumption-based skips.

The real Huawei semantic suite is not green. English and Czech five-to-six
corrections and whole-draft reset passed with the initial prompt; literal quoted
output retained its introductory instruction. Prompt changes did not resolve the
suite and one regressed reset, so they were reverted. The feature stays off by
default and is visibly experimental. A sampled GPU-inference PSS was 2,402,904 KiB,
including 2,220,912 KiB graphics; this is neither a peak nor a RAM minimum.

## Reproduce explicit native recognition

Install/select Zipformer English INT8 in the app, then download the public fixture:

```powershell
curl.exe -fL -o .reference/tmp/asr-fixture.wav https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/test_wavs/0.wav
adb -s DEVICE push .reference/tmp/asr-fixture.wav /data/local/tmp/selia-asr-fixture.wav
adb -s DEVICE shell run-as com.majkeylab.seliaspeak cp /data/local/tmp/selia-asr-fixture.wav files/asr-fixture.wav
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -e class cz.localvoice.app.RecognitionModelInstrumentedTest com.majkeylab.seliaspeak.test/androidx.test.runner.AndroidJUnitRunner
```

Replace DEVICE with an adb device serial. Build both APKs first and install the
debug app. The test validates the fixture SHA-256, actual native partials and final
text. It does not prove word accuracy or microphone capture. Neither public
fixture nor model weights are committed.

## Review

Independent core review found stale AudioCapture cleanup and cancellation missing
between batch-ASR chunks. Per-recording resource ownership and coroutine checks
address both; the real-method ownership regression passes. Claude review could
not run because its account weekly quota was exhausted. Supplementary model advice
was checked against source; quota/region failures did not become approval evidence.

No common-corpus WER/CER measurements are claimed for catalog variants.
Model-declared language support is not project verification of every language.

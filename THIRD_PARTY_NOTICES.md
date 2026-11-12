# Third-party notices

Selia Speak uses or downloads the following components. Their original licences apply to them; the project proprietary licence does not replace those licences.

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) Android runtime | Apache-2.0.
- [OpenAI Whisper](https://github.com/openai/whisper/blob/main/LICENSE) models, including the selected INT8 exports | MIT.
- [English Zipformer export](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26) | Apache-2.0.
- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) Android runtime — Apache-2.0.
- [Qwen3-0.6B LiteRT model](https://huggingface.co/litert-community/Qwen3-0.6B) — use and redistribution remain subject to the model card and included licence.
- AndroidX, Jetpack Compose, and Material 3 — Apache-2.0.
- [Omnilingual ASR](https://huggingface.co/facebook/omniASR-CTC-300M) model and language metadata — Apache-2.0; the catalog pins both revisions.
- [Local Voice base](https://github.com/Majkey25/local-voice-android/tree/51181a9b9646afe4c3509a8f479a8cdacf0eabe2) — previously published MIT material; the exact grant is retained in LICENSE.
- JUnit 4 — Eclipse Public License 1.0.

Model files are not stored in this repository. [ModelCatalog.kt](app/src/main/java/cz/localvoice/app/ModelCatalog.kt) owns the pinned URLs, licenses, sizes and hashes. [ModelRepository.kt](app/src/main/java/cz/localvoice/app/ModelRepository.kt) downloads and verifies the files in app-private storage.

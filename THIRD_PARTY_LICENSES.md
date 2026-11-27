# Third-party licenses

| Artifact / component | Version | License | Source |
|---|---|---|---|
| `androidx.biometric:biometric` | 1.1.0 | Apache-2.0 (upstream AndroidX license; POM omits `<licenses>`) | https://developer.android.com/jetpack/androidx/releases/biometric |
| `com.google.ai.edge.litertlm:litertlm-android` | 0.19.1 | Apache-2.0 (upstream LiteRT-LM license; POM omits `<licenses>`) | https://github.com/google-ai-edge/LiteRT-LM |
| `com.dineshdev:pdfbox-android` | 2.0.27.1 | Apache-2.0 (declared in POM) | https://repo1.maven.org/maven2/com/dineshdev/pdfbox-android/2.0.27.1/ |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.1 | Apache-2.0 (declared in POM) | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.8.1 | Apache-2.0 (declared in POM) | https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | Apache-2.0 (declared in POM) | https://github.com/Kotlin/kotlinx.serialization |
| `junit:junit` | 4.13.2 | EPL-1.0 (declared in POM) | https://github.com/junit-team/junit4 |
| `org.robolectric:robolectric` | 4.18 | MIT (some copied Android sources are Apache-2.0) | https://github.com/robolectric/robolectric |
| `org.robolectric:android-all-instrumented` | 15-robolectric-14663667-2-i7 | Apache-2.0 (Android framework jar) | https://github.com/robolectric/robolectric |
| `io.github.takahirom.roborazzi:roborazzi` | 1.79.0 | Apache-2.0 | https://github.com/takahirom/roborazzi |
| AndroidX Test (`core`, `rules`, `runner`, `ext:junit`, `espresso-core`) | 1.6.1 / 1.6.2 / 1.2.1 / 3.6.1 | Apache-2.0 (upstream AndroidX license; POMs omit `<licenses>`) | https://developer.android.com/jetpack/androidx/releases/test |
| Kotlin standard library / compiler plugin | 2.4.0 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | https://developer.android.com/tools/builds |
| `whisper.cpp` (fetched by CMake with pinned `URL_HASH SHA256`) | pinned in `app/src/main/cpp/CMakeLists.txt` | MIT | https://github.com/ggml-org/whisper.cpp |

No signed artifacts, keystores, or secrets are committed. Release signing remains a template for the repository owner.

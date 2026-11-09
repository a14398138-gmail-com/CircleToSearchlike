# CircleToSearchlike / Circle OCR

Circle-to-Search style screen OCR (ML Kit, Japanese + Latin) with on-device
translation by PLaMo 3 Translate 0.8B via llama.cpp.

## Build prerequisites (external references — the repo is NOT self-contained)

The native build references two paths **outside** this repository, passed
from `app/build.gradle.kts`:

- `LLAMA_CPP_DIR` → `../../../llama.cpp` (a llama.cpp source checkout,
  sibling of this repo's grandparent directory)
- `FETCHCONTENT_SOURCE_DIR_KLEIDIAI` → `../../../.tools/kleidiai-src/kleidiai-v1.32.0`
  (KleidiAI v1.32.0 sources, MD5 of the source tarball
  `05f1953428ffe8e8c7f585dc6d508887`)

Cloning this branch alone is therefore not enough to build; place a
llama.cpp checkout and the KleidiAI sources at the paths above first.
Alternatively set the `LLAMA_CPP_DIR` environment variable to a llama.cpp
checkout (CI does this; KleidiAI is then fetched by CMake). Without any
llama.cpp checkout the APK still builds, but without the translation engine.
Build with the standalone Gradle (the repo has no wrapper):

```
JAVA_HOME=$HOME/workspace/.tools/jdk \
~/workspace/.tools/gradle-dist/gradle-9.3.1/bin/gradle :app:assembleDebug
```

## Notes

- `abiFilters` is **arm64-v8a only** by design (target devices are modern
  64-bit phones; x86 emulators and 32-bit devices are not supported).
- Native libraries are linked with `-Wl,-z,max-page-size=16384` for
  16 KB page-size devices (e.g. POCO F7); do not remove that flag.
- The llama.cpp JNI bridge (`app/src/main/cpp/`, `com.koike.llama`) is kept
  byte-identical with the canonical copy in `~/workspace/shared-llama-jni/`
  (outside this repo). The standalone translate app still carries an older
  v0.7-generation copy (no streaming, ubatch 512, no KleidiAI) — see the
  2026-11-07 code-review follow-up notes.
- Versions: v4–v9 shipped as versionCode 1 / "1.0" by mistake. From the
  2026-11-07 fix pass: versionCode 10, versionName "1.0-plamo-v10";
  increment versionCode on every shipped build.

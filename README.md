# Huli: English Hawaiian Document Scanner

Huli is an Android app for scanning books from a tripod, built for English and ʻōlelo Hawaiʻi, including the ʻokina and kahakō. You set the phone over an open book, turn pages, and the app captures each page when a green border says it is ready. It reads the text on the phone, fixes Hawaiian marks, flags the words it is unsure about, and exports a searchable PDF, a Word file, or both.

Huli is a modified version of [MakeACopy](https://github.com/egdels/makeacopy) by Christian Kierdorf, licensed under the Apache License 2.0. It is not affiliated with or endorsed by the MakeACopy project. The original README is kept in [UPSTREAM_README.md](UPSTREAM_README.md).

## Book Mode

All new code lives in `app/src/main/java/de/schliweb/makeacopy/bookmode`. Open it from the book icon on the camera screen.

1. **New book.** Title, language, whether the book is printed with ʻokina and kahakō, and page layout.
2. **Setup shot.** Detect the page, drag the corners and spine, then lock focus, exposure and white balance.
3. **Capture.** The border is red while a page turns, yellow while it settles, and green when ready. Shoot with the on-screen button, a volume key, a Bluetooth remote, or auto capture.
4. **Processing.** Runs in the background: crop, spine split, curve flattening, deskew, on-device OCR with the PaddleOCR latin model, Hawaiian normalization, page numbers and word flags.
5. **Review.** Page grid with badges, a warning when printed page numbers skip, and a queue of flagged words with ʻ ā ē ī ō ū buttons.
6. **Export.** Searchable PDF and Word (.docx).

The design is described in `BOOK_MODE_SPEC.md` (kept outside this repo).

## Building

Requirements: JDK 21, Android SDK 36, and the prebuilt native libraries from upstream.

1. Download the `native-libs` artifact from a successful upstream [Build Release](https://github.com/egdels/makeacopy/actions/workflows/build-release.yml) run, for example with `gh run download <run-id> -R egdels/makeacopy -n native-libs`.
2. Copy `src/main/jniLibs/arm64-v8a` into `app/src/main/jniLibs/` and the ONNX Runtime jar into `app/libs/`. Both folders are gitignored and must not be committed.
3. Build and test the paddle flavor:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assemblePaddleDebug -PABIS=arm64-v8a
./gradlew :app:testPaddleDebugUnitTest -PABIS=arm64-v8a
```

The APK lands in `app/build/outputs/apk/paddle/debug/`.

## Pulling upstream updates

Book Mode is kept in its own package, with only small hooks in upstream files, so upstream changes merge cleanly:

```bash
git remote add upstream https://github.com/egdels/makeacopy.git
git fetch upstream
git merge upstream/main
```

## Licenses

- App code: Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
- Hawaiian word list (`assets/bookmode/haw.txt.gz`): derived from the [Hawaiian Corpus Project](https://github.com/dohliam/hawaiian-corpus) frequency list, CC0 1.0.
- English word list (`assets/bookmode/eng.txt.gz`): derived from [FrequencyWords](https://github.com/hermitdave/FrequencyWords), CC BY-SA 4.0.

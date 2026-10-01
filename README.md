# Huli: English Hawaiian Document Scanner

Huli is an Android app for scanning books from a tripod, built for English and ʻōlelo Hawaiʻi, including the ʻokina and kahakō. You set the phone over an open book, turn pages, and the app captures each page when a green border says it is ready. It reads the text on the phone, fixes Hawaiian marks, flags the words it is unsure about, and exports a searchable PDF, a Word file, or both.

Huli is a modified version of [MakeACopy](https://github.com/egdels/makeacopy) by Christian Kierdorf, licensed under the Apache License 2.0. It is not affiliated with or endorsed by the MakeACopy project. The original README is kept in [UPSTREAM_README.md](UPSTREAM_README.md).

## Book Mode

All new code lives in `app/src/main/java/de/schliweb/makeacopy/bookmode`. Huli opens on a dashboard of your books, and Book Mode is the only way to scan. The About screen in the app has the full setup and scanning instructions.

1. **New book.** Scan the title page to fill in the title and author, then pick the language, whether the book is printed with ʻokina and kahakō, and single page or two-page spread.
2. **Setup shot.** Detect the page, drag the corners and spine, then lock focus, exposure and white balance.
3. **Capture.** The border is red while a page turns, yellow while it settles, and green when ready. Shoot with the on-screen button, a volume key, a Bluetooth remote, or auto capture.
4. **Processing.** Runs in the background: crop, spine split, curve flattening, deskew, on-device OCR with Tesseract's best English and Māori models (Māori shares Hawaiian's kahakō vowels), Hawaiian normalization, page numbers and word flags.
5. **Review.** Page grid with badges and a warning when printed page numbers skip. Tap a page to read its OCR text or see the photo with word boxes, and tap any word to fix it. Check words walks through every doubtful word with ʻ ā ē ī ō ū buttons.
6. **Export.** Searchable PDF and Word (.docx).

The design is described in `BOOK_MODE_SPEC.md` (kept outside this repo).

## Building

Huli is built from MakeACopy's `standard` flavor, which uses Tesseract for OCR. Requirements: JDK 21, Android SDK 36, and the prebuilt native libraries from upstream (OpenCV and ONNX Runtime, used for page detection).

1. Download the `native-libs` artifact from a successful upstream [Build Release](https://github.com/egdels/makeacopy/actions/workflows/build-release.yml) run, for example with `gh run download <run-id> -R egdels/makeacopy -n native-libs`.
2. Copy `src/main/jniLibs/arm64-v8a` into `app/src/main/jniLibs/` and the ONNX Runtime jar into `app/libs/`. Both folders are gitignored and must not be committed.
3. Build and test:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleStandardDebug -PABIS=arm64-v8a
./gradlew :app:testStandardDebugUnitTest -PABIS=arm64-v8a
```

The APK lands in `app/build/outputs/apk/standard/debug/`. Upstream's instrumented-test assets are left out of debug builds unless you pass `-PwithTestAssets`.

To check OCR quality on a phone that already has scanned books, install the debug and test APKs and run the on-device check. It reads every processed page and writes the text to the app's `cache/ocr_check` folder:

```bash
./gradlew :app:assembleStandardDebugAndroidTest -PABIS=arm64-v8a
adb install -r app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk
adb shell am instrument -w -e class de.schliweb.makeacopy.bookmode.BookOcrDeviceCheck com.olagon.huli.test/androidx.test.runner.AndroidJUnitRunner
```

## Pulling upstream updates

Book Mode is kept in its own package, with only small hooks in upstream files, so upstream changes merge cleanly:

```bash
git remote add upstream https://github.com/egdels/makeacopy.git
git fetch upstream
git merge upstream/main
```

## License

Huli is free and open source.

- **Huli's own code is MIT licensed** ([LICENSE-MIT](LICENSE-MIT)). That is every file with the header `SPDX-License-Identifier: MIT`: all of Book Mode, its tests, layouts, strings and drawables, and this README.
- **Code from MakeACopy stays under the Apache License 2.0** ([LICENSE](LICENSE)), including the upstream files Huli modified. Apache 2.0 code can't be relicensed, so the repository is a mix of the two. Both licenses are permissive.
- **Tesseract language models** (`app/src/huli/assets/tessdata`) come from [tessdata_best](https://github.com/tesseract-ocr/tessdata_best), Apache License 2.0.
- **Word lists keep their own licenses.** The Hawaiian list (`assets/bookmode/haw.txt.gz`) is derived from the [Hawaiian Corpus Project](https://github.com/dohliam/hawaiian-corpus) frequency list (CC0 1.0). The English list (`assets/bookmode/eng.txt.gz`) is derived from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (CC BY-SA 4.0).

[NOTICE](NOTICE) lists the attributions and every upstream file Huli changed.

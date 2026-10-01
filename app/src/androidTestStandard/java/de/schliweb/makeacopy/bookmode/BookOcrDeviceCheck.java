/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.process.BookProcessor;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Manual on-device check: reads every processed page of every book on the phone with the Book
 * Mode OCR settings and writes the text to cache/ocr_check. Skips when there are no books.
 * Run with: adb shell am instrument -w -e class de.schliweb.makeacopy.bookmode.BookOcrDeviceCheck ...
 */
@RunWith(AndroidJUnit4.class)
public class BookOcrDeviceCheck {
  private static final String TAG = "HuliOcrCheck";

  @Test
  public void readAllBookPages() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File books = new File(ctx.getFilesDir(), "books");
    File[] dirs = books.listFiles();
    assumeTrue("no books on this device", dirs != null && dirs.length > 0);
    File out = new File(ctx.getCacheDir(), "ocr_check");
    if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);
    for (File book : dirs) {
      File[] pages = new File(book, "pages").listFiles((d, n) -> n.endsWith(".jpg"));
      if (pages == null) continue;
      for (File page : pages) {
        Bitmap bmp = BitmapFactory.decodeFile(page.getAbsolutePath());
        if (bmp == null) continue;
        long t0 = System.currentTimeMillis();
        OCRHelper ocr = BookProcessor.openOcr(ctx, BookEntity.LANG_BOTH);
        OCRHelper.OcrResultWords res;
        try {
          res = ocr.runOcrWithRetry(bmp);
        } finally {
          ocr.close();
          bmp.recycle();
        }
        long ms = System.currentTimeMillis() - t0;
        String name = book.getName() + "_" + page.getName().replace(".jpg", "");
        try (FileOutputStream fos = new FileOutputStream(new File(out, name + ".txt"))) {
          fos.write((res.text == null ? "" : res.text).getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, name + " ms=" + ms + " words=" + (res.words == null ? 0 : res.words.size()) + " meanConf=" + res.meanConfidence);
      }
    }
  }
}

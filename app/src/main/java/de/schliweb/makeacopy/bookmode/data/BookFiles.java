/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.data;

import android.content.Context;
import java.io.File;

/** Storage layout: files/books/&lt;bookId&gt;/{shots,pages,ocr,export}. */
public final class BookFiles {
  private BookFiles() {}

  public static File bookDir(Context ctx, long bookId) {
    return new File(new File(ctx.getFilesDir(), "books"), String.valueOf(bookId));
  }

  public static File dir(Context ctx, long bookId, String sub) {
    File d = new File(bookDir(ctx, bookId), sub);
    if (!d.exists() && !d.mkdirs()) {
      // best effort; callers get an IOException on write
    }
    return d;
  }

  public static long sizeOf(File f) {
    if (f == null || !f.exists()) return 0;
    if (f.isFile()) return f.length();
    long total = 0;
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) total += sizeOf(k);
    return total;
  }

  public static void deleteRecursively(File f) {
    if (f == null || !f.exists()) return;
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) deleteRecursively(k);
    if (!f.delete()) {
      // ignore
    }
  }

  public static String human(long bytes) {
    if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
    if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
  }
}

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
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Book Mode word lists, shipped as assets/bookmode/{eng,haw}.txt.gz. Kept separate from the
 * upstream DictionaryManager because the paddle flavor excludes assets/dictionaries entirely.
 */
public final class BookDictionaries {
  private static final String TAG = "BookDictionaries";
  private static Set<String> eng;
  private static Set<String> haw;

  private BookDictionaries() {}

  public static synchronized Set<String> eng(Context ctx) {
    if (eng == null) eng = load(ctx, "bookmode/eng.txt.gz");
    return eng;
  }

  public static synchronized Set<String> haw(Context ctx) {
    if (haw == null) haw = load(ctx, "bookmode/haw.txt.gz");
    return haw;
  }

  private static Set<String> load(Context ctx, String asset) {
    // AAPT stores .gz assets decompressed as .txt; try that first, then the gzip file.
    String plain = asset.endsWith(".gz") ? asset.substring(0, asset.length() - 3) : asset;
    try (InputStream is = ctx.getAssets().open(plain)) {
      return read(is, plain);
    } catch (IOException e) {
      Log.d(TAG, "no plain asset " + plain + ", trying gzip");
    }
    try (InputStream is = ctx.getAssets().open(asset)) {
      return read(new GZIPInputStream(is), asset);
    } catch (IOException e) {
      Log.w(TAG, "No word list " + asset + ": " + e.getMessage());
      return Collections.emptySet();
    }
  }

  private static Set<String> read(InputStream in, String asset) throws IOException {
    Set<String> words = new HashSet<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        String w = line.trim();
        if (w.isEmpty() || w.startsWith("#")) continue;
        words.add(Normalizer.normalize(w, Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
      }
    }
    Log.i(TAG, asset + ": " + words.size() + " words");
    return words;
  }
}

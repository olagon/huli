/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.process;

/** Finds the gutter shadow: the darkest vertical band near the expected spine position. */
public final class SpineRefiner {
  private SpineRefiner() {}

  /**
   * @param columnBrightness mean brightness per image column (0..255)
   * @param expectedX saved spine column
   * @param searchFrac how far to search on each side, as a fraction of the width (spec: 0.05)
   * @return refined spine column
   */
  public static int refine(double[] columnBrightness, int expectedX, double searchFrac) {
    int w = columnBrightness.length;
    if (w < 8) return expectedX;
    int radius = Math.max(1, (int) Math.round(w * searchFrac));
    int band = Math.max(3, w / 100);
    int lo = Math.max(band / 2, expectedX - radius);
    int hi = Math.min(w - 1 - band / 2, expectedX + radius);
    if (lo > hi) return Math.max(0, Math.min(w - 1, expectedX));
    int best = expectedX;
    double bestVal = Double.MAX_VALUE;
    for (int x = lo; x <= hi; x++) {
      double sum = 0;
      for (int k = -band / 2; k <= band / 2; k++) sum += columnBrightness[x + k];
      double mean = sum / (2 * (band / 2) + 1);
      if (mean < bestVal) {
        bestVal = mean;
        best = x;
      }
    }
    return best;
  }
}

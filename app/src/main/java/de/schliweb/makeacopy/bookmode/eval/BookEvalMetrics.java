/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.eval;

import de.schliweb.makeacopy.bookmode.hawaiian.HawaiianNormalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Book Mode accuracy metrics (spec section 14): ʻokina and kahakō precision/recall, flag catch rate
 * and flag rate. Word alignment ignores diacritics and case so that a missed kahakō still counts
 * as an aligned word.
 */
public final class BookEvalMetrics {

  public record DiacriticScore(
      double okinaPrecision, double okinaRecall, double kahakoPrecision, double kahakoRecall) {}

  private BookEvalMetrics() {}

  public static List<String> words(String text) {
    String t = HawaiianNormalizer.nfc(text).trim();
    return t.isEmpty() ? List.of() : Arrays.asList(t.split("\\s+"));
  }

  static String key(String w) {
    String core = HawaiianNormalizer.coreOf(w).toLowerCase(Locale.ROOT);
    return HawaiianNormalizer.stripMacrons(core).replace("ʻ", "").replace("'", "").replace("‘", "").replace("’", "");
  }

  public static DiacriticScore diacritics(String groundTruth, String hypothesis) {
    List<String> gt = words(groundTruth);
    List<String> hyp = words(hypothesis);
    List<int[]> pairs = WordAlign.align(gt, hyp, (a, b) -> key(a).equals(key(b)));
    int okTp = 0, okFp = 0, okFn = 0, kaTp = 0, kaFp = 0, kaFn = 0;
    boolean[] gtUsed = new boolean[gt.size()];
    boolean[] hypUsed = new boolean[hyp.size()];
    for (int[] p : pairs) {
      gtUsed[p[0]] = true;
      hypUsed[p[1]] = true;
      int[] g = counts(gt.get(p[0]));
      int[] h = counts(hyp.get(p[1]));
      okTp += Math.min(g[0], h[0]);
      okFp += Math.max(0, h[0] - g[0]);
      okFn += Math.max(0, g[0] - h[0]);
      kaTp += Math.min(g[1], h[1]);
      kaFp += Math.max(0, h[1] - g[1]);
      kaFn += Math.max(0, g[1] - h[1]);
    }
    for (int i = 0; i < gt.size(); i++) {
      if (!gtUsed[i]) {
        int[] g = counts(gt.get(i));
        okFn += g[0];
        kaFn += g[1];
      }
    }
    for (int j = 0; j < hyp.size(); j++) {
      if (!hypUsed[j]) {
        int[] h = counts(hyp.get(j));
        okFp += h[0];
        kaFp += h[1];
      }
    }
    return new DiacriticScore(ratio(okTp, okTp + okFp), ratio(okTp, okTp + okFn), ratio(kaTp, kaTp + kaFp), ratio(kaTp, kaTp + kaFn));
  }

  /** {ʻokina count, kahakō count} in a word. */
  static int[] counts(String w) {
    int ok = 0;
    int ka = 0;
    for (int i = 0; i < w.length(); i++) {
      char c = w.charAt(i);
      if (c == HawaiianNormalizer.OKINA) ok++;
      else if (!HawaiianNormalizer.stripMacrons(String.valueOf(c)).equals(String.valueOf(c))) ka++;
    }
    return new int[] {ok, ka};
  }

  /**
   * Share of remaining errors (hypothesis words that differ from the ground truth, plus words that
   * do not align at all) that were flagged.
   */
  public static double flagCatchRate(String groundTruth, String hypothesis, Set<Integer> flaggedHypIndices) {
    List<String> gt = words(groundTruth);
    List<String> hyp = words(hypothesis);
    List<int[]> pairs = WordAlign.align(gt, hyp, (a, b) -> key(a).equals(key(b)));
    boolean[] hypAligned = new boolean[hyp.size()];
    int errors = 0;
    int caught = 0;
    for (int[] p : pairs) {
      hypAligned[p[1]] = true;
      if (!HawaiianNormalizer.coreOf(gt.get(p[0])).equals(HawaiianNormalizer.coreOf(hyp.get(p[1])))) {
        errors++;
        if (flaggedHypIndices.contains(p[1])) caught++;
      }
    }
    for (int j = 0; j < hyp.size(); j++) {
      if (!hypAligned[j]) {
        errors++;
        if (flaggedHypIndices.contains(j)) caught++;
      }
    }
    return errors == 0 ? 1.0 : (double) caught / errors;
  }

  public static double flagRate(int flagged, int total) {
    return total == 0 ? 0.0 : (double) flagged / total;
  }

  private static double ratio(int a, int b) {
    return b == 0 ? 1.0 : (double) a / b;
  }
}

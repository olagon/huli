/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;

/** Longest-common-subsequence word alignment. Returns index pairs {i, j} of matched words. */
public final class WordAlign {
  private WordAlign() {}

  public static List<int[]> align(List<String> a, List<String> b, BiPredicate<String, String> eq) {
    int n = a.size();
    int m = b.size();
    int[][] dp = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        dp[i][j] = eq.test(a.get(i), b.get(j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }
    List<int[]> pairs = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < n && j < m) {
      if (eq.test(a.get(i), b.get(j))) {
        pairs.add(new int[] {i, j});
        i++;
        j++;
      } else if (dp[i + 1][j] >= dp[i][j + 1]) {
        i++;
      } else {
        j++;
      }
    }
    return Collections.unmodifiableList(pairs);
  }
}

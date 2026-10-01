/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.process;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reads the printed page number from the top or bottom lines of a page. */
public final class PageNumberExtractor {
  private static final Pattern ARABIC = Pattern.compile("^\\d{1,4}$");
  private static final Pattern ROMAN = Pattern.compile("^[ivxlc]{1,7}$");

  private PageNumberExtractor() {}

  /**
   * @param edgeLines candidate lines in priority order (e.g. bottom line, top line, second bottom,
   *     second top), each as a list of word tokens
   * @return the page number string, or null
   */
  public static String extract(List<List<String>> edgeLines) {
    // Pass 1: a line that is only the number.
    for (List<String> line : edgeLines) {
      if (line != null && line.size() == 1) {
        String n = numberToken(line.get(0));
        if (n != null) return n;
      }
    }
    // Pass 2: short line (running header) with the number at one end.
    for (List<String> line : edgeLines) {
      if (line == null || line.isEmpty() || line.size() > 6) continue;
      String first = numberToken(line.get(0));
      String last = numberToken(line.get(line.size() - 1));
      if (first != null && last == null) return first;
      if (last != null && first == null) return last;
    }
    return null;
  }

  static String numberToken(String token) {
    if (token == null) return null;
    String t = token.trim();
    while (!t.isEmpty() && !Character.isLetterOrDigit(t.charAt(t.length() - 1))) {
      t = t.substring(0, t.length() - 1);
    }
    while (!t.isEmpty() && !Character.isLetterOrDigit(t.charAt(0))) t = t.substring(1);
    if (t.isEmpty()) return null;
    if (ARABIC.matcher(t).matches()) return t;
    String lower = t.toLowerCase(Locale.ROOT);
    if (ROMAN.matcher(lower).matches() && !lower.equals("i")) return lower;
    return null;
  }

  /** Numeric value for ordering checks; roman numerals are converted, otherwise -1. */
  public static int toInt(String n) {
    if (n == null) return -1;
    if (ARABIC.matcher(n).matches()) return Integer.parseInt(n);
    int total = 0;
    int prev = 0;
    for (int i = n.length() - 1; i >= 0; i--) {
      int v =
          switch (n.charAt(i)) {
            case 'i' -> 1;
            case 'v' -> 5;
            case 'x' -> 10;
            case 'l' -> 50;
            case 'c' -> 100;
            default -> 0;
          };
      if (v == 0) return -1;
      total += v < prev ? -v : v;
      prev = v;
    }
    return total;
  }
}

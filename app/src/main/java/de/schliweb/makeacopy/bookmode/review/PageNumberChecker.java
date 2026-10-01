/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.review;

import de.schliweb.makeacopy.bookmode.process.PageNumberExtractor;
import java.util.ArrayList;
import java.util.List;

/** Compares printed page numbers in order and reports skips and repeats. */
public final class PageNumberChecker {

  public enum Kind {
    SKIP,
    REPEAT
  }

  /** {@code afterPageIndex} is the list index of the page before the gap. */
  public record Gap(int afterPageIndex, String from, String to, Kind kind) {}

  private PageNumberChecker() {}

  /** @param printed printed page numbers in page order; null entries are pages without a number */
  public static List<Gap> check(List<String> printed) {
    List<Gap> gaps = new ArrayList<>();
    int prevIdx = -1;
    int prevVal = -1;
    String prevStr = null;
    for (int i = 0; i < printed.size(); i++) {
      String s = printed.get(i);
      int v = PageNumberExtractor.toInt(s);
      if (v < 0) continue;
      if (prevIdx >= 0 && sameSystem(prevStr, s)) {
        int expected = prevVal + (i - prevIdx);
        if (v > expected) gaps.add(new Gap(prevIdx, prevStr, s, Kind.SKIP));
        else if (v < expected) gaps.add(new Gap(prevIdx, prevStr, s, Kind.REPEAT));
      }
      prevIdx = i;
      prevVal = v;
      prevStr = s;
    }
    return gaps;
  }

  private static boolean sameSystem(String a, String b) {
    return Character.isDigit(a.charAt(0)) == Character.isDigit(b.charAt(0));
  }
}

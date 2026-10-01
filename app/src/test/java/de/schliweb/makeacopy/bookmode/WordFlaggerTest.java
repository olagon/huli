/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode;

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.bookmode.process.SpineRefiner;
import de.schliweb.makeacopy.bookmode.process.WordFlagger;
import de.schliweb.makeacopy.bookmode.process.WordFlagger.Flag;
import de.schliweb.makeacopy.bookmode.process.WordFlagger.Token;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class WordFlaggerTest {
  private static final Set<String> ENG =
      new HashSet<>(Arrays.asList("the", "king", "went", "to", "see", "it", "well", "known", "book", "dont", "intersection", "cleave"));
  private static final Set<String> HAW = new HashSet<>(Arrays.asList("aloha", "\u02BB\u0101ina", "m\u0101lama"));

  @Test
  public void flagsLowConfidenceAndUnknownWords() {
    List<Token> t = Arrays.asList(
        new Token(1, "The", 0.99f),
        new Token(2, "king", 0.4f), // known but very unsure → flagged
        new Token(3, "went", 0.7f), // known, moderately sure → not flagged
        new Token(4, "to", 0.95f),
        new Token(5, "Kailua", 0.95f), // name mid-sentence
        new Token(6, "\u02BB\u0101ina.", 0.95f),
        new Token(7, "Xyzzy", 0.95f), // sentence start, so not a name
        new Token(8, "1842", 0.95f),
        new Token(9, "well-known", 0.95f),
        new Token(10, "malama", 0.95f),
        new Token(11, "zork", 0.7f), // unknown and unsure → low confidence
        new Token(12, "book\u2014it", 0.95f),
        new Token(13, "kings", 0.95f),
        new Token(14, "don\u2019t", 0.95f),
        new Token(15, "intersections", 0.95f),
        new Token(16, "cleaving", 0.95f));
    List<Flag> flags = WordFlagger.flag(t, ENG, HAW, 0.8, w -> w.equals("malama") ? "m\u0101lama" : null);
    assertEquals(flags.toString(), 4, flags.size());
    assertEquals(2, flags.get(0).wordId());
    assertEquals(WordFlagger.LOW_CONF, flags.get(0).reason());
    assertEquals(7, flags.get(1).wordId());
    assertEquals(WordFlagger.UNKNOWN_WORD, flags.get(1).reason());
    assertEquals(10, flags.get(2).wordId());
    assertEquals("m\u0101lama", flags.get(2).suggestion());
    assertEquals(11, flags.get(3).wordId());
    assertEquals(WordFlagger.LOW_CONF, flags.get(3).reason());
  }

  @Test
  public void noDictionaryMeansNoUnknownFlags() {
    List<Flag> flags = WordFlagger.flag(List.of(new Token(1, "zzz", 0.9f)), null, null, 0.8, null);
    assertEquals(0, flags.size());
  }

  @Test
  public void spineRefinerFindsDarkestBand() {
    double[] cols = new double[200];
    Arrays.fill(cols, 220);
    for (int x = 104; x <= 108; x++) cols[x] = 60; // gutter shadow slightly right of centre
    int c = SpineRefiner.refine(cols, 100, 0.05);
    assertEquals(true, c >= 104 && c <= 108);
    // Outside the search window the shadow is ignored.
    Arrays.fill(cols, 220);
    for (int x = 150; x <= 154; x++) cols[x] = 60;
    int r = SpineRefiner.refine(cols, 100, 0.05);
    assertEquals(true, r >= 90 && r <= 110);
  }
}

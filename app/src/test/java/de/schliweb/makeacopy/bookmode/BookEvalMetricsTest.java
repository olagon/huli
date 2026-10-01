/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode;

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.bookmode.eval.BookEvalMetrics;
import de.schliweb.makeacopy.bookmode.eval.BookEvalMetrics.DiacriticScore;
import java.util.Set;
import org.junit.Test;

public class BookEvalMetricsTest {
  @Test
  public void diacriticPrecisionRecall() {
    String gt = "He ʻāina maikaʻi kēia";
    String hyp = "He ʻaina maika'i keia"; // lost 2 kahakō, lost 1 ʻokina (apostrophe is not ʻokina)
    DiacriticScore s = BookEvalMetrics.diacritics(gt, hyp);
    assertEquals(1.0, s.okinaPrecision(), 1e-9);
    assertEquals(0.5, s.okinaRecall(), 1e-9);
    assertEquals(1.0, s.kahakoPrecision(), 1e-9);
    assertEquals(0.0, s.kahakoRecall(), 1e-9);
  }

  @Test
  public void flagCatchRate() {
    String gt = "the king went to Kailua today";
    String hyp = "the kimg went to Kailua todav";
    assertEquals(0.5, BookEvalMetrics.flagCatchRate(gt, hyp, Set.of(1)), 1e-9);
    assertEquals(1.0, BookEvalMetrics.flagCatchRate(gt, hyp, Set.of(1, 5)), 1e-9);
    assertEquals(1.0, BookEvalMetrics.flagCatchRate(gt, gt, Set.of()), 1e-9);
    assertEquals(0.1, BookEvalMetrics.flagRate(1, 10), 1e-9);
  }
}

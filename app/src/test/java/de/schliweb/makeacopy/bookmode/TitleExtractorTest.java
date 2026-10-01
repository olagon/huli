/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import de.schliweb.makeacopy.bookmode.process.TitleExtractor;
import de.schliweb.makeacopy.bookmode.process.TitleExtractor.Line;
import de.schliweb.makeacopy.bookmode.process.TitleExtractor.Result;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class TitleExtractorTest {
  @Test
  public void titleSubtitleAndByLine() {
    List<Line> lines = Arrays.asList(
        new Line("WHEN NO THING", 90, 300),
        new Line("WORKS", 90, 410),
        new Line("A Guide for Turbulent Times", 40, 540),
        new Line("by Norma Wong", 35, 900),
        new Line("Copyright © 2024", 15, 1500),
        new Line("Penguin Press", 20, 1700));
    Result r = TitleExtractor.extract(lines);
    assertEquals("When No Thing Works: A Guide for Turbulent Times", r.title());
    assertEquals("Norma Wong", r.author());
  }

  @Test
  public void hawaiianTitleAndNameWithoutBy() {
    List<Line> lines = Arrays.asList(
        new Line("NĀ MOʻOLELO O KA ʻĀINA", 80, 200),
        new Line("MARY KAWENA PŪKUʻI", 45, 800),
        new Line("University of Hawaiʻi Press", 25, 1600));
    Result r = TitleExtractor.extract(lines);
    assertEquals("Nā Moʻolelo o ka ʻĀina", r.title());
    assertEquals("Mary Kawena Pūkuʻi", r.author());
  }

  @Test
  public void mixedCaseIsKeptAndNoAuthor() {
    Result r = TitleExtractor.extract(List.of(new Line("Praise for", 40, 100), new Line("Radical Dharma", 70, 200)));
    assertEquals("Radical Dharma", r.title());
    assertNull(r.author());
  }

  @Test
  public void nothingUsable() {
    assertNull(TitleExtractor.extract(List.of(new Line("12", 30, 100), new Line("ISBN 978-0", 20, 200))));
  }
}

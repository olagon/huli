package de.schliweb.makeacopy.bookmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.bookmode.process.PageNumberExtractor;
import de.schliweb.makeacopy.bookmode.review.PageNumberChecker;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PageNumberTest {
  @Test
  public void extractsNumberOnlyLineFirst() {
    assertEquals("46", PageNumberExtractor.extract(Arrays.asList(Arrays.asList("46"), Arrays.asList("12", "CHAPTER", "ONE"))));
    assertEquals("12", PageNumberExtractor.extract(Arrays.asList(Arrays.asList("A", "long", "line", "of", "body", "text", "here"), Arrays.asList("12", "CHAPTER", "ONE"))));
    assertEquals("xiv", PageNumberExtractor.extract(List.of(List.of("XIV"))));
    assertNull(PageNumberExtractor.extract(List.of(List.of("Chapter", "One"))));
    assertNull(PageNumberExtractor.extract(List.of(List.of("1", "of", "2"))));
  }

  @Test
  public void romanToInt() {
    assertEquals(14, PageNumberExtractor.toInt("xiv"));
    assertEquals(9, PageNumberExtractor.toInt("ix"));
    assertEquals(46, PageNumberExtractor.toInt("46"));
    assertEquals(-1, PageNumberExtractor.toInt(null));
  }

  @Test
  public void findsSkipsAndRepeats() {
    List<PageNumberChecker.Gap> gaps = PageNumberChecker.check(Arrays.asList("45", "46", "49", null, "51", "51"));
    assertEquals(2, gaps.size());
    assertEquals(PageNumberChecker.Kind.SKIP, gaps.get(0).kind());
    assertEquals("46", gaps.get(0).from());
    assertEquals("49", gaps.get(0).to());
    assertEquals(1, gaps.get(0).afterPageIndex());
    assertEquals(PageNumberChecker.Kind.REPEAT, gaps.get(1).kind());
  }

  @Test
  public void nullsAreCounted() {
    assertTrue(PageNumberChecker.check(Arrays.asList("1", null, "3", null, null, "6")).isEmpty());
    assertTrue(PageNumberChecker.check(Arrays.asList("xii", "1", "2")).isEmpty()); // front matter switch
  }
}

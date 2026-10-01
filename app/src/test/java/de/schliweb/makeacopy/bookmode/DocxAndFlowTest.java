/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.bookmode.export.BookTextFlow;
import de.schliweb.makeacopy.bookmode.export.BookTextFlow.Line;
import de.schliweb.makeacopy.bookmode.export.BookTextFlow.Options;
import de.schliweb.makeacopy.bookmode.export.BookTextFlow.Page;
import de.schliweb.makeacopy.bookmode.export.DocxWriter;
import de.schliweb.makeacopy.bookmode.export.DocxWriter.Para;
import de.schliweb.makeacopy.bookmode.export.DocxWriter.Style;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;

public class DocxAndFlowTest {

  @Test
  public void docxHasAllPartsAndParses() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    List<Para> paras = Arrays.asList(
        new Para(Style.TITLE, "Nā Moʻolelo & Stories <test>"),
        new Para(Style.HEADING1, "Mokuna I"),
        new Para(Style.NORMAL, "He ʻāina maikaʻi kēia."),
        new Para(Style.PAGE_BREAK, ""),
        new Para(Style.PAGE_MARKER, "[p. 2]"),
        new Para(Style.FOOTNOTE, "1 A footnote."));
    DocxWriter.write(bos, "Title ʻ", "Olin", "Arial", paras);
    Set<String> names = new HashSet<>();
    try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      ZipEntry e;
      while ((e = zin.getNextEntry()) != null) {
        names.add(e.getName());
        byte[] data = zin.readAllBytes();
        if (e.getName().endsWith(".xml") || e.getName().endsWith(".rels")) {
          DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
          f.setNamespaceAware(true);
          try (InputStream in = new ByteArrayInputStream(data)) {
            f.newDocumentBuilder().parse(in); // throws on malformed XML
          }
          if (e.getName().equals("word/document.xml")) {
            String xml = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xml.contains("ʻāina"));
            assertTrue(xml.contains("&amp; Stories &lt;test&gt;"));
            assertTrue(xml.contains("w:type=\"page\""));
            assertTrue(xml.contains("xml:space=\"preserve\""));
          }
        }
      }
    }
    for (String n : new String[] {"[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels", "docProps/core.xml", "docProps/app.xml"}) {
      assertTrue(n, names.contains(n));
    }
  }

  private static Line ln(String text, float top, float h) {
    return new Line(text, top, top + h, 100, h);
  }

  @Test
  public void flowJoinsHyphensParagraphsAndDropsHeaders() {
    Set<String> dict = new HashSet<>(Arrays.asList("mālama", "kanaka"));
    List<Page> pages = new ArrayList<>();
    for (int p = 0; p < 3; p++) {
      List<Line> lines = new ArrayList<>();
      lines.add(ln("THE BOOK TITLE", 50, 18));
      if (p == 0) lines.add(ln("Chapter One", 150, 40));
      lines.add(ln("He kanaka mā-", 300, 20));
      lines.add(ln("lama i ka ʻāina a me ka", 330, 20));
      lines.add(ln("poʻe", 360, 20));
      lines.add(ln("A new paragraph starts here and the page ends without a", 450, 20));
      for (int k = 0; k < 8; k++) lines.add(ln("filler body line number " + k, 480 + 30 * k, 20));
      lines.add(ln("1 A footnote.", 1500, 15));
      lines.add(ln(String.valueOf(10 + p), 1600, 18));
      pages.add(new Page(lines, String.valueOf(10 + p), 1700));
    }
    List<Para> out = BookTextFlow.build(pages, new Options(true, false, false), dict::contains);
    List<String> texts = new ArrayList<>();
    for (Para pa : out) texts.add(pa.style() + ":" + pa.text());
    String all = String.join("\n", texts);
    assertTrue(all, texts.get(0).startsWith("HEADING1:Chapter One"));
    assertTrue(all, all.contains("NORMAL:He kanaka mālama i ka ʻāina a me ka poʻe"));
    assertTrue(all, !all.contains("THE BOOK TITLE"));
    assertTrue(all, !all.contains("NORMAL:10"));
    assertTrue(all, all.contains("FOOTNOTE:1 A footnote."));
    // Paragraph carried across the page break? Page 1 starts with "He kanaka", uppercase → no join.
    long normals = out.stream().filter(x -> x.style() == Style.NORMAL).count();
    assertEquals(6, normals);
  }

  @Test
  public void unknownHyphenKeepsHyphen() {
    StringBuilder sb = new StringBuilder("some wor-");
    BookTextFlow.appendLineForTest(sb, "dish thing", s -> false);
    assertEquals("some wor-dish thing", sb.toString());
    sb = new StringBuilder("ka mā-");
    BookTextFlow.appendLineForTest(sb, "lama", s -> s.equals("mālama"));
    assertEquals("ka mālama", sb.toString());
  }
}

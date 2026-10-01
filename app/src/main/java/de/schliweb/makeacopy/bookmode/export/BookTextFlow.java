/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.export;

import de.schliweb.makeacopy.bookmode.export.DocxWriter.Para;
import de.schliweb.makeacopy.bookmode.export.DocxWriter.Style;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Turns OCR lines of many pages into a flowing list of Word paragraphs. */
public final class BookTextFlow {

  /** One OCR line. {@code height} is the median word height of the line in page pixels. */
  public record Line(String text, float top, float bottom, float left, float height) {}

  public record Page(List<Line> lines, String printedPageNumber, int pageHeightPx) {}

  public record Options(boolean dropHeaders, boolean pageMarkers, boolean keepPageBreaks) {}

  private BookTextFlow() {}

  public static List<Para> build(List<Page> pages, Options opts, Predicate<String> inDict) {
    Map<String, Integer> edgeCounts = new HashMap<>();
    if (opts.dropHeaders()) {
      for (Page p : pages) {
        if (p.lines().isEmpty()) continue;
        for (String key : edgeKeys(p)) edgeCounts.merge(key, 1, Integer::sum);
      }
    }
    List<Para> out = new ArrayList<>();
    StringBuilder open = null; // paragraph carried across a page break
    for (int pi = 0; pi < pages.size(); pi++) {
      Page page = pages.get(pi);
      List<Line> lines = new ArrayList<>(page.lines());
      lines.sort((a, b) -> Float.compare(a.top(), b.top()));
      if (opts.dropHeaders()) lines = dropHeaders(lines, page, edgeCounts, pages.size());
      if (opts.keepPageBreaks() && pi > 0) {
        flush(out, open);
        open = null;
        out.add(new Para(Style.PAGE_BREAK, ""));
      }
      if (opts.pageMarkers()) {
        flush(out, open);
        open = null;
        String n = page.printedPageNumber() != null ? page.printedPageNumber() : "?";
        out.add(new Para(Style.PAGE_MARKER, "[p. " + n + "]"));
      }
      if (lines.isEmpty()) continue;

      float body = medianHeight(lines);
      float gap = medianGap(lines);
      float leftMedian = medianLeft(lines);
      int footStart = footnoteStart(lines, body, page.pageHeightPx());

      List<Para> footnotes = new ArrayList<>();
      for (int i = 0; i < lines.size(); i++) {
        Line ln = lines.get(i);
        String text = ln.text().trim();
        if (text.isEmpty()) continue;
        if (i >= footStart) {
          footnotes.add(new Para(Style.FOOTNOTE, text));
          continue;
        }
        boolean heading = wordCount(text) <= 12 && ln.height() >= 1.4f * body;
        if (heading) {
          flush(out, open);
          open = null;
          out.add(new Para(ln.height() >= 1.8f * body ? Style.HEADING1 : Style.HEADING2, text));
          continue;
        }
        boolean newPara =
            open == null
                || (i > 0 && ln.top() - lines.get(i - 1).bottom() > 1.5f * gap && i > 0 && i - 1 >= 0 && !isContinuation(open, text))
                || (ln.left() - leftMedian > 1.2f * body && !startsLower(text));
        if (i == 0 && open != null) {
          // Cross-page join only when the previous page ended mid-sentence.
          newPara = !isContinuation(open, text);
        }
        if (newPara) {
          flush(out, open);
          open = new StringBuilder(text);
        } else {
          appendLine(open, text, inDict);
        }
      }
      if (!footnotes.isEmpty()) {
        flush(out, open);
        open = null;
        out.addAll(footnotes);
      }
    }
    flush(out, open);
    return out;
  }

  private static void flush(List<Para> out, StringBuilder open) {
    if (open != null && open.length() > 0) out.add(new Para(Style.NORMAL, open.toString()));
  }

  /** Test hook for {@link #appendLine}. */
  public static void appendLineForTest(StringBuilder open, String text, Predicate<String> inDict) {
    appendLine(open, text, inDict);
  }

  /** Joins a line to the open paragraph, resolving a line-end hyphen with the dictionary. */
  static void appendLine(StringBuilder open, String text, Predicate<String> inDict) {
    if (open.length() == 0) {
      open.append(text);
      return;
    }
    if (open.charAt(open.length() - 1) == '-' && startsLower(text)) {
      int ws = open.lastIndexOf(" ");
      String prev = open.substring(ws + 1, open.length() - 1);
      int sp = text.indexOf(' ');
      String next = sp < 0 ? text : text.substring(0, sp);
      String joined = java.text.Normalizer.normalize((prev + next).toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFC).replaceAll("[^\\p{L}\\p{M}\u02BB']", "");
      if (inDict != null && inDict.test(joined)) {
        open.setLength(open.length() - 1); // drop the hyphen
        open.append(text);
        return;
      }
      open.append(text); // keep the hyphen, no space
      return;
    }
    open.append(' ').append(text);
  }

  private static boolean isContinuation(StringBuilder open, String nextText) {
    if (open == null || open.length() == 0) return false;
    char last = open.charAt(open.length() - 1);
    boolean closed = ".!?:\"”’'".indexOf(last) >= 0;
    return !closed && (startsLower(nextText) || last == '-' || last == ',');
  }

  private static boolean startsLower(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isLetter(c)) return Character.isLowerCase(c);
      if (c == 'ʻ') continue;
      if (!Character.isWhitespace(c)) return false;
    }
    return false;
  }

  static int wordCount(String text) {
    return text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
  }

  private static List<String> edgeKeys(Page p) {
    List<Line> ls = new ArrayList<>(p.lines());
    ls.sort((a, b) -> Float.compare(a.top(), b.top()));
    List<String> keys = new ArrayList<>(2);
    keys.add("T:" + edgeKey(ls.get(0).text()));
    if (ls.size() > 1) keys.add("B:" + edgeKey(ls.get(ls.size() - 1).text()));
    return keys;
  }

  private static String edgeKey(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("[\\d\\p{Punct}]+", "").trim();
  }

  private static List<Line> dropHeaders(List<Line> lines, Page page, Map<String, Integer> counts, int pageCount) {
    if (lines.isEmpty()) return lines;
    List<Line> out = new ArrayList<>(lines);
    String num = page.printedPageNumber();
    // Bottom edge first so indices stay valid.
    if (out.size() > 1 && isHeaderLine(out.get(out.size() - 1), num, "B:", counts, pageCount)) {
      out.remove(out.size() - 1);
    }
    if (!out.isEmpty() && isHeaderLine(out.get(0), num, "T:", counts, pageCount)) out.remove(0);
    return out;
  }

  private static boolean isHeaderLine(Line ln, String num, String side, Map<String, Integer> counts, int pageCount) {
    String text = ln.text().trim();
    int words = wordCount(text);
    if (words == 0 || words > 8) return false;
    if (num != null) {
      for (String tok : text.split("\\s+")) {
        if (tok.replaceAll("\\p{Punct}", "").equalsIgnoreCase(num)) return true;
      }
    }
    String key = edgeKey(text);
    if (key.isEmpty()) return words <= 2; // only digits/punctuation, e.g. a stray page number
    int c = counts.getOrDefault(side + key, 0);
    return pageCount >= 3 && c >= 3;
  }

  private static float medianHeight(List<Line> lines) {
    float[] h = new float[lines.size()];
    for (int i = 0; i < h.length; i++) h[i] = Math.max(1f, lines.get(i).height());
    Arrays.sort(h);
    return h[h.length / 2];
  }

  private static float medianLeft(List<Line> lines) {
    float[] l = new float[lines.size()];
    for (int i = 0; i < l.length; i++) l[i] = lines.get(i).left();
    Arrays.sort(l);
    return l[l.length / 2];
  }

  private static float medianGap(List<Line> lines) {
    if (lines.size() < 2) return medianHeight(lines);
    float[] g = new float[lines.size() - 1];
    for (int i = 1; i < lines.size(); i++) g[i - 1] = Math.max(0f, lines.get(i).top() - lines.get(i - 1).bottom());
    Arrays.sort(g);
    float m = g[g.length / 2];
    return m <= 0f ? medianHeight(lines) * 0.3f : m;
  }

  /** Index of the first footnote line, or lines.size() when there is none. */
  private static int footnoteStart(List<Line> lines, float body, int pageHeightPx) {
    if (pageHeightPx <= 0) return lines.size();
    for (int i = Math.max(1, lines.size() / 2); i < lines.size(); i++) {
      Line ln = lines.get(i);
      String t = ln.text().trim();
      boolean small = ln.height() <= 0.85f * body;
      boolean low = ln.top() >= 0.75f * pageHeightPx;
      boolean marker = !t.isEmpty() && (Character.isDigit(t.charAt(0)) || t.charAt(0) == '*' || t.charAt(0) == '†');
      if (small && low && marker) {
        return i;
      }
    }
    return lines.size();
  }
}

/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.process;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Guesses a book's title and author from the OCR lines of its title page. Plain Java. */
public final class TitleExtractor {

  /** One OCR line: text, median word height and top edge, in image pixels. */
  public record Line(String text, float height, float top) {}

  public record Result(String title, String author) {}

  private static final Pattern META =
      Pattern.compile(
          "(?i)\\b(press|publish\\w*|books|isbn|copyright|inc|ltd|llc|university|edition|printed|"
              + "library|reserved|imprint|volume|vol)\\b|©");
  private static final Pattern BY = Pattern.compile("(?i)^by\\s+(.+)$");
  private static final Set<String> SMALL =
      Set.of(
          "a", "an", "the", "and", "or", "nor", "but", "of", "in", "on", "at", "to", "for", "by", "with",
          "from", "as",
          // Hawaiian articles and particles that stay lowercase inside a title
          "ka", "ke", "o", "me");
  /** Lowercase words allowed inside a personal name. */
  private static final Set<String> NAME_PARTICLES = Set.of("de", "da", "di", "del", "van", "von", "der", "la", "le");

  private TitleExtractor() {}

  public static Result extract(List<Line> raw) {
    List<Line> lines = new ArrayList<>();
    for (Line l : raw) {
      String t = l.text() == null ? "" : l.text().trim();
      if (letters(t) < 2 || META.matcher(t).find()) continue;
      lines.add(new Line(t, l.height(), l.top()));
    }
    if (lines.isEmpty()) return null;
    lines.sort(Comparator.comparingDouble(Line::top));

    int tallest = 0;
    for (int i = 1; i < lines.size(); i++) if (lines.get(i).height() > lines.get(tallest).height()) tallest = i;
    float maxH = lines.get(tallest).height();

    // The title is the tallest line plus touching lines of nearly the same size.
    int from = tallest;
    int to = tallest;
    while (from > 0 && sameTier(lines.get(from - 1), lines.get(from), maxH)) from--;
    while (to < lines.size() - 1 && sameTier(lines.get(to + 1), lines.get(to), maxH)) to++;
    StringBuilder title = new StringBuilder();
    for (int i = from; i <= to; i++) {
      if (title.length() > 0) title.append(' ');
      title.append(lines.get(i).text());
    }
    // A clearly smaller line right below the title is a subtitle, unless it names the author.
    String subtitle = null;
    int used = to;
    if (to + 1 < lines.size()) {
      Line next = lines.get(to + 1);
      boolean smaller = next.height() >= 0.4f * maxH && next.height() < 0.75f * maxH;
      boolean close = next.top() - (lines.get(to).top() + lines.get(to).height()) <= 2.0f * maxH;
      if (smaller && close && !BY.matcher(next.text()).matches() && !looksLikeName(next.text())) {
        subtitle = next.text();
        used = to + 1;
      }
    }

    String author = null;
    for (Line l : lines) {
      Matcher m = BY.matcher(l.text());
      if (m.matches()) {
        author = m.group(1);
        break;
      }
    }
    if (author == null) {
      Line best = null;
      for (int i = 0; i < lines.size(); i++) {
        if (i >= from && i <= used) continue;
        Line l = lines.get(i);
        if (looksLikeName(l.text()) && (best == null || l.height() > best.height())) best = l;
      }
      if (best != null) author = best.text();
    }
    String fullTitle = tidy(title.toString(), true) + (subtitle == null ? "" : ": " + tidy(subtitle, true));
    return new Result(fullTitle, author == null ? null : tidy(author, false));
  }

  private static boolean sameTier(Line candidate, Line neighbour, float maxH) {
    float gap = Math.abs(candidate.top() - neighbour.top()) - Math.min(candidate.height(), neighbour.height());
    return candidate.height() >= 0.75f * maxH && gap <= 1.5f * maxH;
  }

  /** Two to five capitalized words, no digits. */
  static boolean looksLikeName(String text) {
    String t = text.trim().replaceAll("[,.]$", "");
    if (t.matches(".*\\d.*")) return false;
    String[] words = t.split("\\s+");
    if (words.length < 2 || words.length > 5) return false;
    for (String w : words) {
      String c = w.replaceAll("^[^\\p{L}]+", "");
      if (c.isEmpty()) return false;
      if (!Character.isUpperCase(c.charAt(0)) && !NAME_PARTICLES.contains(c)) return false;
    }
    return true;
  }

  private static int letters(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) n++;
    return n;
  }

  /** Trims stray punctuation and turns ALL-CAPS text into Title Case. */
  static String tidy(String s, boolean isTitle) {
    String t = s.trim().replaceAll("^[\\p{Punct}—–\\s]+", "").replaceAll("[,;\\s]+$", "").replaceAll("\\s+", " ");
    if (!isAllCaps(t)) return t;
    String[] words = t.toLowerCase(Locale.ROOT).split(" ");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < words.length; i++) {
      String w = words[i];
      boolean edge = i == 0 || i == words.length - 1 || (i > 0 && words[i - 1].endsWith(":"));
      if (!isTitle || edge || !SMALL.contains(w)) w = capitalize(w);
      if (sb.length() > 0) sb.append(' ');
      sb.append(w);
    }
    return sb.toString();
  }

  private static boolean isAllCaps(String s) {
    int letters = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isLetter(c)) {
        letters++;
        if (Character.isLowerCase(c)) return false;
      }
    }
    return letters > 3;
  }

  /** Capitalizes the first letter, skipping a leading ʻokina or other marks. */
  private static String capitalize(String w) {
    for (int i = 0; i < w.length(); i++) {
      if (Character.isLetter(w.charAt(i)) && w.charAt(i) != 'ʻ') {
        return w.substring(0, i) + Character.toUpperCase(w.charAt(i)) + w.substring(i + 1);
      }
    }
    return w;
  }
}

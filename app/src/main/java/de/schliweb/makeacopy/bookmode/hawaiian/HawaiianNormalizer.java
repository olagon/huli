/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.hawaiian;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rule-based fixer for ʻokina and kahakō look-alikes produced by the OCR model. Plain Java, no
 * Android dependencies. See BOOK_MODE_SPEC.md section 9 for the rules.
 */
public final class HawaiianNormalizer {

  public enum DiacriticsMode {
    WITH,
    WITHOUT,
    UNSURE;

    public static DiacriticsMode from(String s) {
      if (s == null) return UNSURE;
      try {
        return valueOf(s);
      } catch (IllegalArgumentException e) {
        return UNSURE;
      }
    }
  }

  public static final char OKINA = 'ʻ';
  public static final String FLAG_LEADING_MARK = "LEADING_MARK";

  private static final String LOOKALIKES = "‘’'`´";
  private static final String VOWELS = "aeiouāēīōūAEIOUĀĒĪŌŪ";
  private static final String HAW_LETTERS = "aeiouhklmnpwāēīōūAEIOUHKLMNPWĀĒĪŌŪ";
  private static final Map<Character, Character> KAHAKO_LOOKALIKE = new HashMap<>();
  private static final Map<Character, Character> STRIP_MACRON = new HashMap<>();

  static {
    String[] rows = {"aàáâäãā", "eèéêëē", "iìíîïī", "oòóôöõō", "uùúûüū"};
    for (String row : rows) {
      char target = row.charAt(row.length() - 1);
      char base = row.charAt(0);
      for (int i = 1; i < row.length() - 1; i++) {
        KAHAKO_LOOKALIKE.put(row.charAt(i), target);
        KAHAKO_LOOKALIKE.put(Character.toUpperCase(row.charAt(i)), Character.toUpperCase(target));
      }
      STRIP_MACRON.put(target, base);
      STRIP_MACRON.put(Character.toUpperCase(target), Character.toUpperCase(base));
    }
  }

  public record Change(String before, String after, String rule) {}

  public static final class WordResult {
    public final String original;
    public final String text;
    public final List<Change> changes;
    /** Non-null when the word should be flagged for review (e.g. an ambiguous leading mark). */
    public final String flag;

    WordResult(String original, String text, List<Change> changes, String flag) {
      this.original = original;
      this.text = text;
      this.changes = changes;
      this.flag = flag;
    }

    public boolean changed() {
      return !original.equals(text);
    }
  }

  private final Set<String> dict;
  private final DiacriticsMode mode;
  private final Map<String, String> kahakoIndex = new HashMap<>();

  /**
   * @param hawDict lowercase NFC Hawaiian words with real ʻokina (U+02BB); may be null or empty,
   *     then all dictionary-dependent rules are skipped
   */
  public HawaiianNormalizer(Set<String> hawDict, DiacriticsMode mode) {
    this.dict = (hawDict == null || hawDict.isEmpty()) ? null : hawDict;
    this.mode = mode == null ? DiacriticsMode.UNSURE : mode;
    if (this.dict != null && this.mode == DiacriticsMode.WITH) {
      for (String w : this.dict) {
        String stripped = stripMacrons(w);
        if (!stripped.equals(w)) {
          // Keep the shortest candidate when several dictionary words collapse to the same form.
          String prev = kahakoIndex.get(stripped);
          if (prev == null || w.length() < prev.length()) kahakoIndex.put(stripped, w);
        }
      }
    }
  }

  public static String nfc(String s) {
    return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFC);
  }

  public static String stripMacrons(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      Character r = STRIP_MACRON.get(c);
      sb.append(r == null ? c : r);
    }
    return sb.toString();
  }

  static boolean isLookalike(char c) {
    return LOOKALIKES.indexOf(c) >= 0;
  }

  static boolean isVowel(char c) {
    return VOWELS.indexOf(c) >= 0;
  }

  static boolean isHawLetter(char c) {
    return HAW_LETTERS.indexOf(c) >= 0;
  }

  /** True when the word uses only Hawaiian letters, kahakō (or look-alikes) and ʻokina marks. */
  public static boolean isHawaiianLetterWord(String core) {
    if (core == null || core.isEmpty()) return false;
    boolean letter = false;
    for (int i = 0; i < core.length(); i++) {
      char c = core.charAt(i);
      if (isHawLetter(c)) {
        letter = true;
      } else if (c == OKINA || isLookalike(c) || KAHAKO_LOOKALIKE.containsKey(c)) {
        // allowed marks
      } else {
        return false;
      }
    }
    return letter;
  }

  /** Normalizes a sequence of tokens (one page, in reading order), sentence-aware. */
  public List<WordResult> normalizeWords(List<String> tokens) {
    List<WordResult> out = new ArrayList<>(tokens.size());
    for (int i = 0; i < tokens.size(); i++) {
      out.add(normalizeWord(tokens.get(i), closingQuoteLater(tokens, i)));
    }
    return out;
  }

  private static boolean closingQuoteLater(List<String> tokens, int i) {
    for (int j = i; j < tokens.size(); j++) {
      String t = nfc(tokens.get(j));
      if (t.isEmpty()) continue;
      int end = t.length();
      while (end > 0 && !Character.isLetterOrDigit(t.charAt(end - 1))) end--;
      String suffix = t.substring(end);
      for (int k = 0; k < suffix.length(); k++) if (isLookalike(suffix.charAt(k))) return true;
      if (j > i && t.length() > 1 && isLookalike(t.charAt(0))) return true;
      if (suffix.indexOf('.') >= 0 || suffix.indexOf('!') >= 0 || suffix.indexOf('?') >= 0) {
        return false;
      }
    }
    return false;
  }

  /**
   * Normalizes one token. {@code closingQuoteLater} says whether a matching closing quote mark
   * appears later in the same sentence (then a leading mark is a quote, not an ʻokina).
   */
  public WordResult normalizeWord(String token, boolean closingQuoteLater) {
    String original = token == null ? "" : token;
    List<Change> changes = new ArrayList<>();
    String s = nfc(original);
    if (!s.equals(original)) changes.add(new Change(original, s, "nfc"));
    if (mode == DiacriticsMode.WITHOUT || s.isEmpty()) return done(original, s, changes, null);

    int len = s.length();
    int start = 0;
    while (start < len) {
      char c = s.charAt(start);
      if (Character.isLetterOrDigit(c)) break;
      if (isLookalike(c) && start + 1 < len && Character.isLetter(s.charAt(start + 1))) break;
      start++;
    }
    int end = len;
    while (end > start && !Character.isLetterOrDigit(s.charAt(end - 1))) end--;
    if (end <= start) return done(original, s, changes, null);
    String prefix = s.substring(0, start);
    String suffix = s.substring(end);
    String core = s.substring(start, end);
    if (!isHawaiianLetterWord(core)) return done(original, s, changes, null);

    // Kahakō look-alikes (à á â ä ã → ā).
    StringBuilder sb = new StringBuilder(core.length());
    for (int i = 0; i < core.length(); i++) {
      char c = core.charAt(i);
      Character r = KAHAKO_LOOKALIKE.get(c);
      sb.append(r == null ? c : r);
    }
    String mapped = sb.toString();
    if (!mapped.equals(core)) {
      changes.add(new Change(core, mapped, "kahako_lookalike"));
      core = mapped;
    }

    // ʻokina inside the word: mark between two letters, followed by a vowel.
    char[] cs = core.toCharArray();
    boolean internal = false;
    for (int i = 1; i < cs.length - 1; i++) {
      if (isLookalike(cs[i]) && isHawLetter(cs[i - 1]) && isVowel(cs[i + 1])) {
        cs[i] = OKINA;
        internal = true;
      }
    }
    if (internal) {
      String fixed = new String(cs);
      changes.add(new Change(core, fixed, "okina_internal"));
      core = fixed;
    }

    // ʻokina at the start of the word: only with dictionary evidence and no closing quote.
    String flag = null;
    if (core.length() > 1 && isLookalike(core.charAt(0)) && isVowel(core.charAt(1))) {
      String rest = core.substring(1);
      String candidate = OKINA + rest;
      boolean inDict =
          dict != null
              && dict.contains(candidate.toLowerCase(Locale.ROOT))
              && !dict.contains(rest.toLowerCase(Locale.ROOT));
      if (inDict && !closingQuoteLater) {
        changes.add(new Change(core, candidate, "okina_leading"));
        core = candidate;
      } else if (!closingQuoteLater) {
        flag = FLAG_LEADING_MARK;
      }
    }
    return done(original, prefix + core + suffix, changes, flag);
  }

  private static WordResult done(String original, String text, List<Change> changes, String flag) {
    return new WordResult(original, text, Collections.unmodifiableList(changes), flag);
  }

  /**
   * For a word that is not in the dictionary, returns a dictionary form that differs only by
   * kahakō, or null. Only for books printed with diacritics; never applied automatically.
   */
  public String suggestKahako(String token) {
    if (mode != DiacriticsMode.WITH || dict == null || token == null) return null;
    String core = coreOf(nfc(token));
    if (core.isEmpty()) return null;
    String lower = core.toLowerCase(Locale.ROOT);
    if (dict.contains(lower)) return null;
    String candidate = kahakoIndex.get(stripMacrons(lower));
    if (candidate == null || candidate.equals(lower)) return null;
    if (Character.isUpperCase(core.charAt(0)) || (core.charAt(0) == OKINA && core.length() > 1 && Character.isUpperCase(core.charAt(1)))) {
      int idx = candidate.charAt(0) == OKINA ? 1 : 0;
      if (candidate.length() > idx) {
        candidate = candidate.substring(0, idx) + Character.toUpperCase(candidate.charAt(idx)) + candidate.substring(idx + 1);
      }
    }
    return candidate;
  }

  /** Strips surrounding punctuation (keeps a leading real ʻokina). */
  public static String coreOf(String s) {
    if (s == null) return "";
    int start = 0;
    int len = s.length();
    while (start < len) {
      char c = s.charAt(start);
      if (Character.isLetterOrDigit(c) || c == OKINA) break;
      start++;
    }
    int end = len;
    while (end > start && !Character.isLetterOrDigit(s.charAt(end - 1))) end--;
    return end <= start ? "" : s.substring(start, end);
  }
}

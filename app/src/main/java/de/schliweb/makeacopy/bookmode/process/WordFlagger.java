/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.process;

import de.schliweb.makeacopy.bookmode.hawaiian.HawaiianNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Decides which words need a human look. */
public final class WordFlagger {
  public static final String LOW_CONF = "LOW_CONF";
  public static final String UNKNOWN_WORD = "UNKNOWN_WORD";
  public static final String LEADING_MARK = HawaiianNormalizer.FLAG_LEADING_MARK;
  public static final String AUTO_FIXED = "AUTO_FIXED";

  private static final Pattern NUMBER = Pattern.compile("^[\\d.,:;%/()\\-]+$|^[ivxlcdm]{1,8}$");

  public record Token(int id, String text, float confidence) {}

  public record Flag(int wordId, String reason, String suggestion) {}

  private WordFlagger() {}

  /**
   * @param tokens words in reading order; confidence 0..1
   * @param eng English dictionary (lowercase) or null
   * @param haw Hawaiian dictionary (lowercase, NFC, real ʻokina) or null
   * @param minConf confidence threshold (spec: 0.80)
   * @param suggest optional kahakō suggester for unknown words
   */
  public static List<Flag> flag(
      List<Token> tokens,
      Set<String> eng,
      Set<String> haw,
      double minConf,
      Function<String, String> suggest) {
    List<Flag> out = new ArrayList<>();
    boolean hasDict = (eng != null && !eng.isEmpty()) || (haw != null && !haw.isEmpty());
    boolean sentenceStart = true;
    for (Token t : tokens) {
      String core = HawaiianNormalizer.coreOf(t.text());
      if (core.isEmpty()) continue;
      boolean endsSentence = t.text().matches(".*[.!?:]\\W*$");
      if (t.confidence() < minConf) {
        out.add(new Flag(t.id(), LOW_CONF, suggest == null ? null : suggest.apply(core)));
      } else if (hasDict && !isKnown(core, eng, haw, sentenceStart)) {
        out.add(new Flag(t.id(), UNKNOWN_WORD, suggest == null ? null : suggest.apply(core)));
      }
      sentenceStart = endsSentence;
    }
    return out;
  }

  static boolean isKnown(String core, Set<String> eng, Set<String> haw, boolean sentenceStart) {
    String lower = core.toLowerCase(Locale.ROOT);
    if (NUMBER.matcher(lower).matches()) return true;
    if (core.endsWith("-")) return true; // line-end hyphenation, cannot judge
    if (core.length() == 1) return true;
    if (inDict(lower, eng, haw)) return true;
    if (lower.endsWith("'s") || lower.endsWith("’s")) {
      if (inDict(lower.substring(0, lower.length() - 2), eng, haw)) return true;
    }
    int idx = lower.indexOf('-');
    if (idx > 0 && idx < lower.length() - 1) {
      if (inDict(lower.substring(0, idx), eng, haw) && inDict(lower.substring(idx + 1), eng, haw)) {
        return true;
      }
    }
    // Capitalized word in the middle of a sentence: treat as a name.
    char first = core.charAt(0) == HawaiianNormalizer.OKINA && core.length() > 1 ? core.charAt(1) : core.charAt(0);
    return Character.isUpperCase(first) && !sentenceStart;
  }

  private static boolean inDict(String lower, Set<String> eng, Set<String> haw) {
    return (eng != null && eng.contains(lower)) || (haw != null && haw.contains(lower));
  }
}

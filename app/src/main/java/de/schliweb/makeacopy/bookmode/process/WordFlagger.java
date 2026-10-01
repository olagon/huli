/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
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
  /** Below this, even a dictionary word is flagged. */
  public static final double HARD_MIN_CONF = 0.5;

  private static final Pattern DASH_SPLIT = Pattern.compile("[\u2014\u2013/]|(?<=\\p{L})-(?=\\p{L})");
  // ponytail: a handful of English endings, not a stemmer. Swap in Hunspell affixes if the
  // unknown-word flags are still noisy on real books.
  private static final String[][] SUFFIXES = {
    {"ies", "y"}, {"ied", "y"}, {"es", ""}, {"s", ""}, {"ed", ""}, {"ed", "e"}, {"ing", ""},
    {"ing", "e"}, {"ly", ""}, {"ity", ""}, {"ness", ""}, {"er", ""}, {"est", ""}
  };
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
      boolean known = hasDict && isKnown(core, eng, haw, sentenceStart);
      // ponytail: Paddle often gives every word on a line the same confidence, so one smudge
      // drags down a whole line. A dictionary word only needs a look when it is very unsure.
      boolean lowConf = t.confidence() < Math.min(HARD_MIN_CONF, minConf) || (t.confidence() < minConf && !known);
      if (lowConf) {
        out.add(new Flag(t.id(), LOW_CONF, suggest == null ? null : suggest.apply(core)));
      } else if (hasDict && !known) {
        out.add(new Flag(t.id(), UNKNOWN_WORD, suggest == null ? null : suggest.apply(core)));
      }
      sentenceStart = endsSentence;
    }
    return out;
  }

  static boolean isKnown(String core, Set<String> eng, Set<String> haw, boolean sentenceStart) {
    String lower = core.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'');
    if (NUMBER.matcher(lower).matches()) return true;
    if (core.endsWith("-")) return true; // line-end hyphenation, cannot judge
    if (core.length() == 1) return true;
    if (known(lower, eng, haw)) return true;
    // Words joined by a dash, en dash, slash or hyphen: every part must be known.
    String[] parts = DASH_SPLIT.split(lower);
    if (parts.length > 1) {
      boolean all = true;
      for (String part : parts) {
        String p = HawaiianNormalizer.coreOf(part);
        if (!p.isEmpty() && !p.matches("\\d+") && !known(p, eng, haw)) all = false;
      }
      if (all) return true;
    }
    // Capitalized word in the middle of a sentence: treat as a name.
    char first = core.charAt(0) == HawaiianNormalizer.OKINA && core.length() > 1 ? core.charAt(1) : core.charAt(0);
    return Character.isUpperCase(first) && !sentenceStart;
  }

  /** Dictionary lookup with apostrophe folding and light English suffix stripping. */
  static boolean known(String lower, Set<String> eng, Set<String> haw) {
    if (inDict(lower, eng, haw)) return true;
    if (lower.endsWith("'s") && inDict(lower.substring(0, lower.length() - 2), eng, haw)) return true;
    String noApos = lower.replace("'", "");
    if (!noApos.equals(lower) && inDict(noApos, eng, haw)) return true;
    if (eng == null) return false;
    for (String[] rule : SUFFIXES) {
      String suf = rule[0];
      if (lower.length() > suf.length() + 2 && lower.endsWith(suf)) {
        String stem = lower.substring(0, lower.length() - suf.length()) + rule[1];
        if (eng.contains(stem)) return true;
      }
    }
    return false;
  }

  private static boolean inDict(String lower, Set<String> eng, Set<String> haw) {
    return (eng != null && eng.contains(lower)) || (haw != null && haw.contains(lower));
  }
}

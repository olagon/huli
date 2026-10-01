package de.schliweb.makeacopy.bookmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.bookmode.hawaiian.HawaiianNormalizer;
import de.schliweb.makeacopy.bookmode.hawaiian.HawaiianNormalizer.DiacriticsMode;
import de.schliweb.makeacopy.bookmode.hawaiian.HawaiianNormalizer.WordResult;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class HawaiianNormalizerTest {
  private static final Set<String> DICT =
      new HashSet<>(Arrays.asList("ʻāina", "aloha", "mālama", "hawaiʻi", "puʻu", "kāne", "ʻohana"));

  private static String one(String in, DiacriticsMode mode) {
    return new HawaiianNormalizer(DICT, mode).normalizeWord(in, false).text;
  }

  @Test
  public void internalOkina() {
    assertEquals("Hawaiʻi", one("Hawai'i", DiacriticsMode.WITH));
    assertEquals("puʻu", one("pu‘u", DiacriticsMode.WITH));
    assertEquals("puʻu,", one("pu’u,", DiacriticsMode.UNSURE));
  }

  @Test
  public void englishContractionsUntouched() {
    assertEquals("Hawaii's", one("Hawaii's", DiacriticsMode.WITH));
    assertEquals("don't", one("don't", DiacriticsMode.WITH));
    assertEquals("café", one("café", DiacriticsMode.WITH));
  }

  @Test
  public void kahakoLookalikes() {
    assertEquals("mālama", one("mälama", DiacriticsMode.WITH));
    assertEquals("MĀLAMA", one("MÄLAMA", DiacriticsMode.WITH));
    assertEquals("ā", one("ā", DiacriticsMode.WITH)); // combining macron → NFC
  }

  @Test
  public void leadingOkinaNeedsDictionary() {
    assertEquals("ʻāina", one("‘āina", DiacriticsMode.WITH));
    // "aloha" is a dictionary word, so a leading mark stays a quote (and is flagged).
    WordResult r = new HawaiianNormalizer(DICT, DiacriticsMode.WITH).normalizeWord("‘aloha", false);
    assertEquals("‘aloha", r.text);
    assertEquals(HawaiianNormalizer.FLAG_LEADING_MARK, r.flag);
    // Without a dictionary nothing is converted.
    assertEquals("‘āina", new HawaiianNormalizer(null, DiacriticsMode.WITH).normalizeWord("‘āina", false).text);
  }

  @Test
  public void quotedSentenceUnchanged() {
    List<String> in = Arrays.asList("‘Aloha,’", "she", "said");
    List<WordResult> out = new HawaiianNormalizer(DICT, DiacriticsMode.WITH).normalizeWords(in);
    assertEquals("‘Aloha,’", out.get(0).text);
    assertNull(out.get(0).flag);
  }

  @Test
  public void withoutDiacriticsNeverChanges() {
    assertEquals("Hawai'i", one("Hawai'i", DiacriticsMode.WITHOUT));
    assertEquals("mälama", one("mälama", DiacriticsMode.WITHOUT));
    assertNull(new HawaiianNormalizer(DICT, DiacriticsMode.WITHOUT).suggestKahako("malama"));
  }

  @Test
  public void kahakoSuggestionsOnlyWhenPrintedWithDiacritics() {
    HawaiianNormalizer with = new HawaiianNormalizer(DICT, DiacriticsMode.WITH);
    assertEquals("mālama", with.suggestKahako("malama"));
    assertEquals("Mālama", with.suggestKahako("Malama"));
    assertEquals("Kāne", with.suggestKahako("Kane,"));
    assertNull(with.suggestKahako("aloha"));
    assertNull(new HawaiianNormalizer(DICT, DiacriticsMode.UNSURE).suggestKahako("malama"));
  }

  @Test
  public void changesAreLogged() {
    WordResult r = new HawaiianNormalizer(DICT, DiacriticsMode.WITH).normalizeWord("mälam'a", false);
    assertTrue(r.changed());
    assertEquals(2, r.changes.size());
    assertEquals("kahako_lookalike", r.changes.get(0).rule());
    assertEquals("okina_internal", r.changes.get(1).rule());
    assertNotNull(r.original);
  }
}

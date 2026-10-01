/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.review;

import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookPageEntity;
import de.schliweb.makeacopy.bookmode.data.WordFlagEntity;
import de.schliweb.makeacopy.bookmode.process.WordFlagger;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.store.OcrJsonStore;
import java.io.File;

/** Writes a word correction into a page's final OCR file. */
public final class PageEdits {
  private PageEdits() {}

  public static void setWordText(BookDao dao, long pageId, int wordId, String text) {
    BookPageEntity p = dao.getPage(pageId);
    if (p == null || p.finalOcrPath == null) return;
    File file = new File(p.finalOcrPath);
    OcrDoc doc = OcrJsonStore.load(file);
    if (doc == null) return;
    for (OcrDoc.Word w : doc.words) {
      if (w.id == wordId) {
        w.t = text;
        w.e = true;
        break;
      }
    }
    OcrJsonStore.save(file, doc);
  }

  /** Corrects a word and resolves any open flags on it. */
  public static void correct(BookDao dao, long pageId, int wordId, String text) {
    setWordText(dao, pageId, wordId, text);
    for (WordFlagEntity f : dao.flagsForWord(pageId, wordId)) {
      if (f.resolved || WordFlagger.AUTO_FIXED.equals(f.reason)) continue;
      f.resolved = true;
      f.resolvedText = text;
      dao.updateFlag(f);
    }
  }
}

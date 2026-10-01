/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.process;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;
import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookDictionaries;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.data.BookFiles;
import de.schliweb.makeacopy.bookmode.data.BookPageEntity;
import de.schliweb.makeacopy.bookmode.data.BookShotEntity;
import de.schliweb.makeacopy.bookmode.data.CaptureProfile;
import de.schliweb.makeacopy.bookmode.data.WordFlagEntity;
import de.schliweb.makeacopy.bookmode.hawaiian.HawaiianNormalizer;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDocUtils;
import de.schliweb.makeacopy.ui.ocr.review.store.OcrJsonStore;
import de.schliweb.makeacopy.utils.image.DewarpModel;
import de.schliweb.makeacopy.utils.image.DocumentCleanupOptions;
import de.schliweb.makeacopy.utils.image.DocumentCleanupProcessor;
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.opencv.core.Point;

/**
 * Turns one shot into one or two pages: crop + warp, spine split, curve flattening, deskew,
 * cleanup, on-device OCR with the latin model, Hawaiian normalization, page number and word flags.
 */
public final class BookProcessor {
  private static final String TAG = "BookProcessor";
  /** Quad expansion so the dark cloth around the page stays visible for the curve estimator. */
  private static final double MARGIN = 0.04;
  public static final double MIN_CONF = 0.80;
  public static final double ATTENTION_FLAG_SHARE = 0.15;

  private final Context ctx;
  private final BookDao dao;

  public BookProcessor(Context ctx) {
    this.ctx = ctx.getApplicationContext();
    this.dao = BookDatabase.get(ctx).dao();
  }

  public void processShot(BookEntity book, BookShotEntity shot) throws Exception {
    OpenCVUtils.init(ctx);
    CaptureProfile profile = CaptureProfile.fromJson(book.captureProfileJson);
    Bitmap shotBmp = ImageLoader.decode(ctx, shot.filePath, null);
    if (shotBmp == null) throw new IOException("Cannot decode " + shot.filePath);

    Point[] corners = expandedCorners(profile.quad, shotBmp.getWidth(), shotBmp.getHeight());
    Bitmap warped = OpenCVUtils.applyPerspectiveCorrection(shotBmp, corners);
    if (warped != shotBmp) shotBmp.recycle();
    warped = rotate(warped, profile.rotationDeg);

    int w = warped.getWidth();
    int h = warped.getHeight();
    double f = MARGIN / (1 + 2 * MARGIN);
    int ix0 = (int) Math.round(w * f), iy0 = (int) Math.round(h * f);
    int ix1 = (int) Math.round(w * (1 - f)), iy1 = (int) Math.round(h * (1 - f));

    // Remove pages from an earlier run of this shot (reprocess / retake).
    for (BookPageEntity old : dao.pagesForShot(shot.id)) {
      dao.deleteFlagsForPage(old.id);
      deleteQuietly(old.imagePath);
      deleteQuietly(old.phoneOcrPath);
      deleteQuietly(old.finalOcrPath);
    }
    dao.deletePagesForShot(shot.id);

    List<int[]> halves = new ArrayList<>(); // {x0, x1}
    List<String> sides = new ArrayList<>();
    if (book.isSpread()) {
      int expected = ix0 + Math.round(profile.spineX * (ix1 - ix0));
      double[] cols = Deskew.columnBrightness(warped, ix0, ix1, iy0 + (iy1 - iy0) / 5, iy1 - (iy1 - iy0) / 5);
      int spine = ix0 + SpineRefiner.refine(cols, expected - ix0, 0.05);
      boolean skipLeft = book.firstShotRightOnly && shot.seq == 0;
      if (!skipLeft) {
        halves.add(new int[] {ix0, spine});
        sides.add(BookPageEntity.SIDE_LEFT);
      }
      halves.add(new int[] {spine, ix1});
      sides.add(BookPageEntity.SIDE_RIGHT);
    } else {
      halves.add(new int[] {ix0, ix1});
      sides.add(BookPageEntity.SIDE_SINGLE);
    }

    Set<String> eng = BookDictionaries.eng(ctx);
    Set<String> haw = BookDictionaries.haw(ctx);
    HawaiianNormalizer.DiacriticsMode mode = HawaiianNormalizer.DiacriticsMode.from(book.diacriticsMode);
    boolean hawaiian = !BookEntity.LANG_ENG.equals(book.languageMode);
    HawaiianNormalizer normalizer = new HawaiianNormalizer(hawaiian ? haw : null, hawaiian ? mode : HawaiianNormalizer.DiacriticsMode.WITHOUT);
    boolean dewarp = BookEntity.DEWARP_AUTO.equals(book.dewarpMode);

    try (OCRHelper ocr = new OCRHelper(ctx)) {
      ocr.setLanguage("latin"); // the en model cannot output kahakō (spec section 9)
      ocr.setPaddleHighQualityDetectionEnabled(true); // detector at 1920 px instead of 1536
      for (int i = 0; i < halves.size(); i++) {
        int[] hx = halves.get(i);
        Point[] hc = {new Point(hx[0], iy0), new Point(hx[1], iy0), new Point(hx[1], iy1), new Point(hx[0], iy1)};
        Bitmap flat = Bitmap.createBitmap(warped, hx[0], iy0, hx[1] - hx[0], iy1 - iy0);
        Bitmap curved = dewarp ? tryDewarp(warped, hc) : null;
        Candidate best = evaluate(flat, ocr);
        boolean usedDewarp = false;
        if (curved != null) {
          // ponytail: the edge tracer needs a dark background; on a light table it can bend the
          // text into waves. Reading both versions and keeping the better one costs one extra OCR
          // pass per curved page. Replace with a profile sanity check if processing gets too slow.
          Candidate c = evaluate(curved, ocr);
          if (c.score() > best.score()) {
            best.bitmap().recycle();
            best = c;
            usedDewarp = true;
          } else {
            c.bitmap().recycle();
          }
        }
        Bitmap page = best.bitmap();
        OCRHelper.OcrResultWords res = best.res();
        String side = sides.get(i);
        String base = "shot" + shot.id + "_" + side.toLowerCase(java.util.Locale.ROOT);
        File img = new File(BookFiles.dir(ctx, book.id, "pages"), base + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(img)) {
          page.compress(Bitmap.CompressFormat.JPEG, 95, fos);
        }
        OcrDoc phoneDoc = OcrDocBuilder.build(res, page.getWidth(), page.getHeight());
        page.recycle();
        File phoneJson = new File(BookFiles.dir(ctx, book.id, "ocr"), base + ".phone.json");
        OcrJsonStore.save(phoneJson, phoneDoc);

        BookPageEntity pe = new BookPageEntity();
        pe.bookId = book.id;
        pe.shotId = shot.id;
        pe.side = side;
        pe.pageIndex = pageIndex(book, shot.seq, side);
        pe.imagePath = img.getAbsolutePath();
        pe.phoneOcrPath = phoneJson.getAbsolutePath();
        pe.dewarpUsed = usedDewarp;
        pe.meanConfidence = res.meanConfidence == null ? 0f : (res.meanConfidence > 1 ? res.meanConfidence / 100f : res.meanConfidence);
        pe.wordCount = phoneDoc.words.size();

        // Hawaiian normalizer on a copy → final doc + auto-fixed list.
        OcrDoc finalDoc = OcrDocUtils.deepCopy(phoneDoc);
        List<WordFlagEntity> flags = new ArrayList<>();
        List<HawaiianNormalizer.WordResult> norm = normalizer.normalizeWords(OcrDocBuilder.tokens(finalDoc.words));
        for (int k = 0; k < norm.size(); k++) {
          HawaiianNormalizer.WordResult wr = norm.get(k);
          OcrDoc.Word word = finalDoc.words.get(k);
          if (wr.changed()) {
            word.t = wr.text;
            word.e = true;
            WordFlagEntity fl = new WordFlagEntity();
            fl.wordIndex = word.id;
            fl.reason = WordFlagger.AUTO_FIXED;
            fl.phoneText = wr.original;
            fl.resolvedText = wr.text;
            fl.suggestion = rules(wr);
            fl.resolved = true;
            flags.add(fl);
          }
          if (wr.flag != null) {
            WordFlagEntity fl = new WordFlagEntity();
            fl.wordIndex = word.id;
            fl.reason = wr.flag;
            fl.phoneText = word.t;
            fl.suggestion = HawaiianNormalizer.OKINA + HawaiianNormalizer.coreOf(word.t).substring(1);
            flags.add(fl);
          }
        }
        // Printed page number from the edge lines.
        List<List<OcrDoc.Word>> lines = OcrDocBuilder.lines(finalDoc);
        List<List<String>> edges = new ArrayList<>();
        if (!lines.isEmpty()) {
          edges.add(OcrDocBuilder.tokens(lines.get(lines.size() - 1)));
          edges.add(OcrDocBuilder.tokens(lines.get(0)));
          if (lines.size() > 2) {
            edges.add(OcrDocBuilder.tokens(lines.get(lines.size() - 2)));
            edges.add(OcrDocBuilder.tokens(lines.get(1)));
          }
        }
        pe.printedPageNumber = PageNumberExtractor.extract(edges);

        // Word flags.
        List<WordFlagger.Token> toks = new ArrayList<>();
        for (OcrDoc.Word word : finalDoc.words) toks.add(new WordFlagger.Token(word.id, word.t, word.c));
        List<WordFlagger.Flag> wf = WordFlagger.flag(toks, eng, hawaiian ? haw : null, MIN_CONF, normalizer::suggestKahako);
        for (WordFlagger.Flag flg : wf) {
          WordFlagEntity fl = new WordFlagEntity();
          fl.wordIndex = flg.wordId();
          fl.reason = flg.reason();
          fl.phoneText = finalDoc.words.get(flg.wordId() - 1).t;
          fl.suggestion = flg.suggestion();
          flags.add(fl);
        }
        File finalJson = new File(BookFiles.dir(ctx, book.id, "ocr"), base + ".final.json");
        OcrJsonStore.save(finalJson, finalDoc);
        pe.finalOcrPath = finalJson.getAbsolutePath();
        pe.flaggedCount = wf.size();
        boolean blurry = shot.sharpnessRatio < 0.7f;
        boolean manyFlags = pe.wordCount > 0 && wf.size() > ATTENTION_FLAG_SHARE * pe.wordCount;
        pe.status = (blurry || manyFlags) ? BookPageEntity.NEEDS_ATTENTION : BookPageEntity.OK;
        if (dao.getShot(shot.id) == null) {
          // The shot was undone or retaken while it was being processed: drop its pages.
          deleteQuietly(img.getAbsolutePath());
          deleteQuietly(phoneJson.getAbsolutePath());
          deleteQuietly(finalJson.getAbsolutePath());
          Log.i(TAG, "shot " + shot.id + " was deleted during processing; pages discarded");
          continue;
        }
        long pageId = dao.insertPage(pe);
        for (WordFlagEntity fl : flags) fl.pageId = pageId;
        if (!flags.isEmpty()) dao.insertFlags(flags);
        Log.i(TAG, "shot " + shot.seq + " " + side + ": words=" + pe.wordCount + " flags=" + wf.size() + " page#=" + pe.printedPageNumber + " dewarp=" + usedDewarp);
      }
    } finally {
      warped.recycle();
    }
  }

  private record Candidate(Bitmap bitmap, OCRHelper.OcrResultWords res, double score) {}

  /** Deskews, cleans up and reads one candidate page. Takes ownership of {@code page}. */
  private Candidate evaluate(Bitmap page, OCRHelper ocr) {
    double angle = Deskew.estimateAngleDeg(page);
    if (Math.abs(angle) >= 0.2) {
      Bitmap r = Deskew.rotate(page, angle);
      page.recycle();
      page = r;
    }
    Bitmap cleaned = DocumentCleanupProcessor.apply(ctx, page, DocumentCleanupOptions.natural());
    if (cleaned != null && cleaned != page) {
      page.recycle();
      page = cleaned;
    }
    OCRHelper.OcrResultWords res = ocr.runOcrWithWords(page);
    return new Candidate(page, res, score(res));
  }

  /** Sum of word confidences (0..1 each): rewards both more words and surer words. */
  static double score(OCRHelper.OcrResultWords res) {
    if (res == null || res.words == null) return 0;
    double s = 0;
    for (de.schliweb.makeacopy.utils.ocr.RecognizedWord w : res.words) {
      float c = w.getConfidence();
      s += c > 1f ? c / 100.0 : c;
    }
    return s;
  }

  /** Curve-flattened crop of one half, or null when no edge estimate is available. */
  private static Bitmap tryDewarp(Bitmap warped, Point[] hc) {
    try {
      double[][] profiles = OpenCVUtils.estimateDewarpEdgeProfiles(warped, hc);
      if (profiles == null || profiles.length != 2 || (profiles[0] == null && profiles[1] == null)) return null;
      DewarpModel model = DewarpModel.fromOnCurveMidpoints(hc, mid(hc[0], hc[1], profiles[0]), mid(hc[3], hc[2], profiles[1]));
      if (model == null) return null;
      model = model.withEdgeProfiles(profiles[0], profiles[1]);
      Bitmap d = OpenCVUtils.applyDewarp(warped, model, OpenCVUtils.WarpMode.AUTO_PROJECTIVE, null);
      return (d != null && d != warped) ? d : null;
    } catch (Throwable t) {
      Log.w(TAG, "dewarp failed, using flat crop: " + t.getMessage());
      return null;
    }
  }

  private static String rules(HawaiianNormalizer.WordResult wr) {
    StringBuilder sb = new StringBuilder();
    for (HawaiianNormalizer.Change c : wr.changes) {
      if (sb.length() > 0) sb.append(',');
      sb.append(c.rule());
    }
    return sb.toString();
  }

  /** Display order: spread shot k → pages 2k and 2k+1 (shifted by one for right-only first shots). */
  public static int pageIndex(BookEntity book, int seq, String side) {
    if (!book.isSpread()) return seq;
    int base = seq * 2 + (BookPageEntity.SIDE_RIGHT.equals(side) ? 1 : 0);
    return book.firstShotRightOnly ? base - 1 : base;
  }

  static Point[] expandedCorners(float[] q, int w, int h) {
    double cx = 0, cy = 0;
    for (int i = 0; i < 4; i++) {
      cx += q[i * 2] * w;
      cy += q[i * 2 + 1] * h;
    }
    cx /= 4;
    cy /= 4;
    Point[] out = new Point[4];
    for (int i = 0; i < 4; i++) {
      double x = q[i * 2] * w, y = q[i * 2 + 1] * h;
      out[i] = new Point(clamp(cx + (x - cx) * (1 + 2 * MARGIN), 0, w - 1), clamp(cy + (y - cy) * (1 + 2 * MARGIN), 0, h - 1));
    }
    return out;
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /** Point on the traced edge at t=0.5 (chord midpoint plus normal offset), see DewarpModel. */
  private static Point mid(Point a, Point b, double[] profile) {
    double dx = b.x - a.x, dy = b.y - a.y;
    double len = Math.hypot(dx, dy);
    double off = profile == null ? 0 : DewarpModel.sampleProfile(profile, 0.5) * len;
    double nx = len < 1e-9 ? 0 : -dy / len, ny = len < 1e-9 ? 0 : dx / len;
    return new Point(a.x + 0.5 * dx + nx * off, a.y + 0.5 * dy + ny * off);
  }

  static Bitmap rotate(Bitmap src, int deg) {
    int d = ((deg % 360) + 360) % 360;
    if (d == 0) return src;
    Matrix m = new Matrix();
    m.postRotate(d);
    Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    if (out != src) src.recycle();
    return out;
  }

  private static void deleteQuietly(String path) {
    if (path == null) return;
    File f = new File(path);
    if (f.exists() && !f.delete()) Log.w(TAG, "could not delete " + path);
  }
}

/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.process;

import android.graphics.RectF;
import de.schliweb.makeacopy.bookmode.export.BookTextFlow;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.utils.ocr.LineGrouping;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Converts OCR words into the existing OcrDoc JSON model (with lines) and back. */
public final class OcrDocBuilder {
  private OcrDocBuilder() {}

  public static OcrDoc build(OCRHelper.OcrResultWords result, int imageW, int imageH) {
    OcrDoc doc = new OcrDoc();
    doc.imageSize = new OcrDoc.ImageSize(imageW, imageH);
    List<RecognizedWord> words = result == null || result.words == null ? List.of() : result.words;
    int n = words.size();
    float[] l = new float[n], t = new float[n], r = new float[n], b = new float[n];
    List<OcrDoc.Word> ws = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      RecognizedWord rw = words.get(i);
      RectF box = rw.getBoundingBox();
      l[i] = box.left;
      t[i] = box.top;
      r[i] = box.right;
      b[i] = box.bottom;
      OcrDoc.Word w = new OcrDoc.Word();
      w.id = i + 1;
      w.t = rw.getText() == null ? "" : rw.getText();
      w.b[0] = Math.max(0, Math.round(box.left));
      w.b[1] = Math.max(0, Math.round(box.top));
      w.b[2] = Math.max(0, Math.round(box.width()));
      w.b[3] = Math.max(0, Math.round(box.height()));
      float c = rw.getConfidence();
      if (c > 1f) c = c / 100f;
      w.c = Math.max(0f, Math.min(1f, c));
      w.k = 1;
      ws.add(w);
    }
    List<int[]> groups = n == 0 ? List.of() : LineGrouping.groupIntoLines(l, t, r, b);
    // Order lines top to bottom and words left to right inside a line.
    List<int[]> ordered = new ArrayList<>(groups);
    ordered.sort(Comparator.comparingDouble(g -> meanTop(g, t)));
    int lineId = 1;
    List<OcrDoc.Word> inOrder = new ArrayList<>(n);
    for (int[] g : ordered) {
      int[] idx = g.clone();
      Integer[] boxed = Arrays.stream(idx).boxed().toArray(Integer[]::new);
      Arrays.sort(boxed, Comparator.comparingDouble(i -> l[i]));
      OcrDoc.Line line = new OcrDoc.Line();
      line.id = lineId;
      line.w = new int[boxed.length];
      float minL = Float.MAX_VALUE, minT = Float.MAX_VALUE, maxR = 0, maxB = 0;
      for (int k = 0; k < boxed.length; k++) {
        OcrDoc.Word w = ws.get(boxed[k]);
        w.l = lineId;
        line.w[k] = w.id;
        inOrder.add(w);
        minL = Math.min(minL, l[boxed[k]]);
        minT = Math.min(minT, t[boxed[k]]);
        maxR = Math.max(maxR, r[boxed[k]]);
        maxB = Math.max(maxB, b[boxed[k]]);
      }
      line.b = new int[] {Math.round(minL), Math.round(minT), Math.round(maxR - minL), Math.round(maxB - minT)};
      doc.lines.add(line);
      lineId++;
    }
    // Keep words in reading order; renumber ids so id == reading position + 1.
    for (int i = 0; i < inOrder.size(); i++) inOrder.get(i).id = i + 1;
    for (OcrDoc.Line line : doc.lines) {
      for (int k = 0; k < line.w.length; k++) {
        // map old index → new id: words were added to inOrder in the same order as line.w
      }
    }
    int pos = 0;
    for (OcrDoc.Line line : doc.lines) {
      for (int k = 0; k < line.w.length; k++) line.w[k] = inOrder.get(pos++).id;
    }
    doc.words = inOrder;
    OcrDoc.Block block = new OcrDoc.Block();
    block.id = 1;
    block.l = new int[doc.lines.size()];
    for (int i = 0; i < doc.lines.size(); i++) block.l[i] = doc.lines.get(i).id;
    block.b = new int[] {0, 0, imageW, imageH};
    doc.blocks.add(block);
    return doc;
  }

  private static double meanTop(int[] g, float[] tops) {
    double s = 0;
    for (int i : g) s += tops[i];
    return g.length == 0 ? 0 : s / g.length;
  }

  /** Words of each line in reading order. */
  public static List<List<OcrDoc.Word>> lines(OcrDoc doc) {
    List<List<OcrDoc.Word>> out = new ArrayList<>();
    if (doc == null) return out;
    java.util.Map<Integer, OcrDoc.Word> byId = new java.util.HashMap<>();
    for (OcrDoc.Word w : doc.words) byId.put(w.id, w);
    if (doc.lines.isEmpty()) {
      out.add(new ArrayList<>(doc.words));
      return out;
    }
    for (OcrDoc.Line line : doc.lines) {
      List<OcrDoc.Word> ws = new ArrayList<>();
      if (line.w != null) for (int id : line.w) if (byId.get(id) != null) ws.add(byId.get(id));
      if (!ws.isEmpty()) out.add(ws);
    }
    return out;
  }

  public static String text(List<OcrDoc.Word> ws) {
    StringBuilder sb = new StringBuilder();
    for (OcrDoc.Word w : ws) {
      if (sb.length() > 0) sb.append(' ');
      sb.append(w.t == null ? "" : w.t);
    }
    return sb.toString();
  }

  public static List<String> tokens(List<OcrDoc.Word> ws) {
    List<String> out = new ArrayList<>(ws.size());
    for (OcrDoc.Word w : ws) out.add(w.t == null ? "" : w.t);
    return out;
  }

  public static List<BookTextFlow.Line> toFlowLines(OcrDoc doc) {
    List<BookTextFlow.Line> out = new ArrayList<>();
    for (List<OcrDoc.Word> ws : lines(doc)) {
      float top = Float.MAX_VALUE, bottom = 0, left = Float.MAX_VALUE;
      float[] hs = new float[ws.size()];
      for (int i = 0; i < ws.size(); i++) {
        OcrDoc.Word w = ws.get(i);
        top = Math.min(top, w.b[1]);
        bottom = Math.max(bottom, w.b[1] + w.b[3]);
        left = Math.min(left, w.b[0]);
        hs[i] = w.b[3];
      }
      Arrays.sort(hs);
      out.add(new BookTextFlow.Line(text(ws), top, bottom, left, hs.length == 0 ? 0 : hs[hs.length / 2]));
    }
    return out;
  }

  public static List<RecognizedWord> toRecognizedWords(OcrDoc doc) {
    List<RecognizedWord> out = new ArrayList<>();
    if (doc == null) return out;
    for (OcrDoc.Word w : doc.words) {
      if (w.t == null || w.t.isEmpty()) continue;
      RectF box = new RectF(w.b[0], w.b[1], w.b[0] + w.b[2], w.b[1] + w.b[3]);
      RecognizedWord rw = new RecognizedWord(w.t, box, w.c * 100f, w.lang);
      rw.setLineId(w.l);
      rw.setBlockId(w.k);
      out.add(rw);
    }
    return out;
  }
}

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

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Small-angle deskew using the projection-profile variance of a binarized, downscaled copy. */
final class Deskew {
  private Deskew() {}

  static double estimateAngleDeg(Bitmap page) {
    Mat rgba = new Mat();
    Utils.bitmapToMat(page, rgba);
    Mat gray = new Mat();
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
    rgba.release();
    double scale = 600.0 / Math.max(gray.cols(), gray.rows());
    Mat small = new Mat();
    if (scale < 1) Imgproc.resize(gray, small, new Size(Math.max(1, gray.cols() * scale), Math.max(1, gray.rows() * scale)), 0, 0, Imgproc.INTER_AREA);
    else small = gray.clone();
    gray.release();
    Mat bin = new Mat();
    Imgproc.threshold(small, bin, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);
    small.release();
    double bestAngle = 0;
    double bestScore = -1;
    Point c = new Point(bin.cols() / 2.0, bin.rows() / 2.0);
    for (double a = -3.0; a <= 3.0001; a += 0.25) {
      Mat rot = Imgproc.getRotationMatrix2D(c, a, 1.0);
      Mat r = new Mat();
      Imgproc.warpAffine(bin, r, rot, bin.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, new Scalar(0));
      Mat rows = new Mat();
      Core.reduce(r, rows, 1, Core.REDUCE_SUM, CvType.CV_32F);
      double score = variance(rows);
      if (score > bestScore) {
        bestScore = score;
        bestAngle = a;
      }
      rot.release();
      r.release();
      rows.release();
    }
    bin.release();
    return bestAngle;
  }

  private static double variance(Mat col) {
    int n = col.rows();
    if (n == 0) return 0;
    float[] v = new float[n];
    col.get(0, 0, v);
    double mean = 0;
    for (float f : v) mean += f;
    mean /= n;
    double var = 0;
    for (float f : v) var += (f - mean) * (f - mean);
    return var / n;
  }

  static Bitmap rotate(Bitmap src, double angleDeg) {
    Mat m = new Mat();
    Utils.bitmapToMat(src, m);
    Mat rot = Imgproc.getRotationMatrix2D(new Point(m.cols() / 2.0, m.rows() / 2.0), angleDeg, 1.0);
    Mat out = new Mat();
    Imgproc.warpAffine(m, out, rot, m.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
    Bitmap bmp = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888);
    Utils.matToBitmap(out, bmp);
    m.release();
    rot.release();
    out.release();
    return bmp;
  }

  /** Mean brightness of each column over the middle rows, for the spine search. */
  static double[] columnBrightness(Bitmap spread, int x0, int x1, int y0, int y1) {
    Mat rgba = new Mat();
    Utils.bitmapToMat(spread, rgba);
    Mat gray = new Mat();
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
    rgba.release();
    Mat sub = gray.submat(new org.opencv.core.Rect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)));
    Mat cols = new Mat();
    Core.reduce(sub, cols, 0, Core.REDUCE_AVG, CvType.CV_32F);
    float[] v = new float[cols.cols()];
    cols.get(0, 0, v);
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) out[i] = v[i];
    sub.release();
    cols.release();
    gray.release();
    return out;
  }
}

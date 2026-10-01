/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.capture;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

/** Motion, sharpness, brightness and paper fraction inside the page area of a small gray frame. */
final class FrameMetrics {
  record Values(double motion, double laplacianVar, double brightness, double paperFraction) {}

  private Mat prev;

  synchronized Values measure(Mat gray, Rect roi, double paperBrightness) {
    Rect r = clamp(roi, gray.cols(), gray.rows());
    Mat region = gray.submat(r);
    double motion = 0;
    if (prev != null && prev.cols() == region.cols() && prev.rows() == region.rows()) {
      Mat diff = new Mat();
      Core.absdiff(region, prev, diff);
      motion = Core.mean(diff).val[0];
      diff.release();
    }
    Mat lap = new Mat();
    Imgproc.Laplacian(region, lap, CvType.CV_64F);
    MatOfDouble mean = new MatOfDouble();
    MatOfDouble std = new MatOfDouble();
    Core.meanStdDev(lap, mean, std);
    double sigma = std.get(0, 0)[0];
    lap.release();
    mean.release();
    std.release();
    double brightness = Core.mean(region).val[0];
    double paper = 1.0;
    if (paperBrightness > 0) {
      Mat bin = new Mat();
      Imgproc.threshold(region, bin, 0.6 * paperBrightness, 255, Imgproc.THRESH_BINARY);
      paper = Core.countNonZero(bin) / (double) (bin.rows() * bin.cols());
      bin.release();
    }
    if (prev != null) prev.release();
    prev = region.clone();
    region.release();
    return new Values(motion, sigma * sigma, brightness, paper);
  }

  synchronized void reset() {
    if (prev != null) prev.release();
    prev = null;
  }

  static Rect clamp(Rect roi, int w, int h) {
    int x = Math.max(0, Math.min(w - 2, roi.x));
    int y = Math.max(0, Math.min(h - 2, roi.y));
    int rw = Math.max(2, Math.min(w - x, roi.width));
    int rh = Math.max(2, Math.min(h - y, roi.height));
    return new Rect(x, y, rw, rh);
  }

  /** Laplacian variance of a gray Mat region (same formula as the live meter). */
  static double laplacianVar(Mat gray, Rect roi) {
    Mat region = gray.submat(clamp(roi, gray.cols(), gray.rows()));
    Mat lap = new Mat();
    Imgproc.Laplacian(region, lap, CvType.CV_64F);
    MatOfDouble mean = new MatOfDouble();
    MatOfDouble std = new MatOfDouble();
    Core.meanStdDev(lap, mean, std);
    double s = std.get(0, 0)[0];
    region.release();
    lap.release();
    mean.release();
    std.release();
    return s * s;
  }
}

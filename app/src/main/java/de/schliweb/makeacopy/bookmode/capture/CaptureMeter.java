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

import android.graphics.Bitmap;
import androidx.camera.core.ImageProxy;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

/** Public face of the frame metrics for the setup and capture screens. */
public final class CaptureMeter {
  public static final int ANALYSIS_LONG_EDGE = 320;

  public record Values(double motion, double laplacianVar, double brightness, double paperFraction) {}

  private final FrameMetrics metrics = new FrameMetrics();

  public Mat gray(ImageProxy image) {
    return YuvGray.toSmallUprightGray(image, ANALYSIS_LONG_EDGE);
  }

  public Values measure(Mat gray, Rect roi, double paperBrightness) {
    FrameMetrics.Values v = metrics.measure(gray, roi, paperBrightness);
    return new Values(v.motion(), v.laplacianVar(), v.brightness(), v.paperFraction());
  }

  public void reset() {
    metrics.reset();
  }

  /** Bounding rect of the page quad in a frame of the given size. */
  public Rect roi(float[] quad, int w, int h) {
    float minX = 1, minY = 1, maxX = 0, maxY = 0;
    for (int i = 0; i < 4; i++) {
      minX = Math.min(minX, quad[i * 2]);
      maxX = Math.max(maxX, quad[i * 2]);
      minY = Math.min(minY, quad[i * 2 + 1]);
      maxY = Math.max(maxY, quad[i * 2 + 1]);
    }
    int x = Math.round(minX * w), y = Math.round(minY * h);
    return FrameMetrics.clamp(new Rect(x, y, Math.round((maxX - minX) * w), Math.round((maxY - minY) * h)), w, h);
  }

  /** Laplacian variance inside the quad after scaling the image to a fixed width. */
  public static double sharpnessAtWidth(Bitmap src, float[] quad, int width) {
    float s = width / (float) src.getWidth();
    Bitmap scaled = s < 1f ? Bitmap.createScaledBitmap(src, width, Math.max(1, Math.round(src.getHeight() * s)), true) : src;
    Mat rgba = new Mat();
    Utils.bitmapToMat(scaled, rgba);
    Mat gray = new Mat();
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
    rgba.release();
    Rect roi = new CaptureMeter().roi(quad, gray.cols(), gray.rows());
    double v = FrameMetrics.laplacianVar(gray, roi);
    gray.release();
    if (scaled != src) scaled.recycle();
    return v;
  }
}

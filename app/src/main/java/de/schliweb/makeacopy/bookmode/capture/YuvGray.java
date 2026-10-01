/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.capture;

import android.graphics.Rect;
import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Converts the Y plane of an analysis frame into a small, upright, grayscale Mat. */
final class YuvGray {
  private YuvGray() {}

  static Mat toSmallUprightGray(ImageProxy image, int maxLong) {
    ImageProxy.PlaneProxy plane = image.getPlanes()[0];
    ByteBuffer buf = plane.getBuffer();
    int rowStride = plane.getRowStride();
    int pixelStride = plane.getPixelStride();
    int w = image.getWidth();
    int h = image.getHeight();
    Mat full = new Mat(h, w, CvType.CV_8UC1);
    if (pixelStride == 1) {
      byte[] row = new byte[w];
      for (int y = 0; y < h; y++) {
        buf.position(y * rowStride);
        buf.get(row, 0, w);
        full.put(y, 0, row);
      }
    } else {
      byte[] row = new byte[w];
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) row[x] = buf.get(y * rowStride + x * pixelStride);
        full.put(y, 0, row);
      }
    }
    Mat src = full;
    Rect crop = image.getCropRect();
    if (crop != null && crop.width() > 0 && crop.height() > 0 && (crop.width() < w || crop.height() < h)) {
      src = full.submat(new org.opencv.core.Rect(crop.left, crop.top, crop.width(), crop.height()));
    }
    double scale = maxLong / (double) Math.max(src.cols(), src.rows());
    Mat small = new Mat();
    if (scale < 1.0) {
      Imgproc.resize(src, small, new Size(Math.max(1, Math.round(src.cols() * scale)), Math.max(1, Math.round(src.rows() * scale))), 0, 0, Imgproc.INTER_AREA);
    } else {
      src.copyTo(small);
    }
    if (src != full) src.release();
    full.release();
    int rot = ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
    if (rot == 0) return small;
    Mat out = new Mat();
    int code = rot == 90 ? Core.ROTATE_90_CLOCKWISE : rot == 180 ? Core.ROTATE_180 : Core.ROTATE_90_COUNTERCLOCKWISE;
    Core.rotate(small, out, code);
    small.release();
    return out;
  }
}

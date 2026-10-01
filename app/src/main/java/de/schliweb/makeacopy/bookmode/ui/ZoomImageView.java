/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/** ImageView with pinch zoom, pan, double-tap zoom, and taps reported in image coordinates. */
public class ZoomImageView extends AppCompatImageView {
  public interface OnImageTapListener {
    void onImageTap(float imageX, float imageY);
  }

  private static final float MAX_ZOOM = 6f;
  private final Matrix matrix = new Matrix();
  private final float[] values = new float[9];
  private float fitScale = 1f;
  private OnImageTapListener tapListener;
  private final ScaleGestureDetector scaler;
  private final GestureDetector gestures;

  public ZoomImageView(Context ctx) {
    this(ctx, null);
  }

  public ZoomImageView(Context ctx, @Nullable AttributeSet attrs) {
    super(ctx, attrs);
    setScaleType(ScaleType.MATRIX);
    scaler =
        new ScaleGestureDetector(
            ctx,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
              }
            });
    gestures =
        new GestureDetector(
            ctx,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                matrix.postTranslate(-dx, -dy);
                apply();
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                float target = currentScale() > fitScale * 1.5f ? fitScale : fitScale * 2.5f;
                zoomBy(target / currentScale(), e.getX(), e.getY());
                return true;
              }

              @Override
              public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener == null) return false;
                Matrix inverse = new Matrix();
                if (!matrix.invert(inverse)) return false;
                float[] pt = {e.getX(), e.getY()};
                inverse.mapPoints(pt);
                tapListener.onImageTap(pt[0], pt[1]);
                return true;
              }
            });
  }

  public void setOnImageTapListener(OnImageTapListener l) {
    tapListener = l;
  }

  @Override
  public void setImageDrawable(@Nullable Drawable d) {
    super.setImageDrawable(d);
    fit();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    fit();
  }

  private void fit() {
    Drawable d = getDrawable();
    if (d == null || getWidth() == 0 || getHeight() == 0) return;
    float dw = d.getIntrinsicWidth();
    float dh = d.getIntrinsicHeight();
    fitScale = Math.min(getWidth() / dw, getHeight() / dh);
    matrix.reset();
    matrix.postScale(fitScale, fitScale);
    matrix.postTranslate((getWidth() - dw * fitScale) / 2f, (getHeight() - dh * fitScale) / 2f);
    setImageMatrix(matrix);
  }

  private float currentScale() {
    matrix.getValues(values);
    return values[Matrix.MSCALE_X];
  }

  private void zoomBy(float factor, float fx, float fy) {
    float target = Math.max(fitScale, Math.min(fitScale * MAX_ZOOM, currentScale() * factor));
    float f = target / currentScale();
    matrix.postScale(f, f, fx, fy);
    apply();
  }

  /** Keeps the image on screen: centred when smaller than the view, edges clamped when larger. */
  private void apply() {
    Drawable d = getDrawable();
    if (d == null) return;
    RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
    matrix.mapRect(r);
    float dx = 0;
    float dy = 0;
    if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
    else if (r.left > 0) dx = -r.left;
    else if (r.right < getWidth()) dx = getWidth() - r.right;
    if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
    else if (r.top > 0) dy = -r.top;
    else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
    matrix.postTranslate(dx, dy);
    setImageMatrix(matrix);
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    scaler.onTouchEvent(ev);
    gestures.onTouchEvent(ev);
    if (currentScale() > fitScale * 1.01f && getParent() != null) {
      getParent().requestDisallowInterceptTouchEvent(true);
    }
    if (ev.getActionMasked() == MotionEvent.ACTION_UP) performClick();
    return true;
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }
}

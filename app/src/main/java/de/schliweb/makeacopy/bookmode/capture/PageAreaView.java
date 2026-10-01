/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;

/**
 * Draws the fixed page area (quad + optional spine line) over a FIT_CENTER preview and lets the
 * user drag the corners and the spine during setup. Coordinates are fractions of the camera frame.
 */
public class PageAreaView extends View {
  private final float[] quad = {0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f};
  private float spineX = -1f;
  private float frameAspect = 3f / 4f; // width / height of the upright frame
  private boolean editable;
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint spinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF content = new RectF();
  private final Path path = new Path();
  private int dragIdx = -1;
  private final float density;

  public PageAreaView(Context ctx) {
    this(ctx, null);
  }

  public PageAreaView(Context ctx, @Nullable AttributeSet attrs) {
    super(ctx, attrs);
    density = ctx.getResources().getDisplayMetrics().density;
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(6 * density);
    stroke.setColor(Color.RED);
    handle.setStyle(Paint.Style.FILL);
    handle.setColor(0xCCFFFFFF);
    spinePaint.setStyle(Paint.Style.STROKE);
    spinePaint.setStrokeWidth(3 * density);
    spinePaint.setColor(0xFF00E5FF);
    spinePaint.setPathEffect(new android.graphics.DashPathEffect(new float[] {12 * density, 8 * density}, 0));
  }

  public void setFrameSize(int w, int h) {
    if (w > 0 && h > 0) {
      frameAspect = w / (float) h;
      invalidate();
    }
  }

  public void setQuad(float[] q) {
    if (q != null && q.length == 8) System.arraycopy(q, 0, quad, 0, 8);
    invalidate();
  }

  public float[] getQuad() {
    return quad.clone();
  }

  public void setSpineX(float x) {
    spineX = x;
    invalidate();
  }

  public float getSpineX() {
    return spineX;
  }

  public void setEditable(boolean e) {
    editable = e;
    invalidate();
  }

  public void setBorderColor(int color) {
    stroke.setColor(color);
    invalidate();
  }

  /** Content rect of a FIT_CENTER frame with the current aspect inside this view. */
  public RectF contentRect() {
    float vw = getWidth();
    float vh = getHeight();
    if (vw <= 0 || vh <= 0) return new RectF(0, 0, 1, 1);
    float cw = vw;
    float ch = vw / frameAspect;
    if (ch > vh) {
      ch = vh;
      cw = vh * frameAspect;
    }
    float l = (vw - cw) / 2f;
    float t = (vh - ch) / 2f;
    content.set(l, t, l + cw, t + ch);
    return content;
  }

  public PointF toView(float nx, float ny) {
    RectF c = contentRect();
    return new PointF(c.left + nx * c.width(), c.top + ny * c.height());
  }

  public float[] toNormalized(float vx, float vy) {
    RectF c = contentRect();
    return new float[] {clamp01((vx - c.left) / c.width()), clamp01((vy - c.top) / c.height())};
  }

  private static float clamp01(float v) {
    return Math.max(0f, Math.min(1f, v));
  }

  /** Point on the top (t=0) or bottom (t=1) edge at the spine fraction. */
  private PointF spinePoint(boolean top) {
    float ax = top ? quad[0] : quad[6];
    float ay = top ? quad[1] : quad[7];
    float bx = top ? quad[2] : quad[4];
    float by = top ? quad[3] : quad[5];
    return toView(ax + spineX * (bx - ax), ay + spineX * (by - ay));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    path.reset();
    for (int i = 0; i < 4; i++) {
      PointF p = toView(quad[i * 2], quad[i * 2 + 1]);
      if (i == 0) path.moveTo(p.x, p.y);
      else path.lineTo(p.x, p.y);
    }
    path.close();
    canvas.drawPath(path, stroke);
    if (spineX >= 0f) {
      PointF a = spinePoint(true);
      PointF b = spinePoint(false);
      canvas.drawLine(a.x, a.y, b.x, b.y, spinePaint);
      if (editable) {
        canvas.drawCircle((a.x + b.x) / 2f, (a.y + b.y) / 2f, 14 * density, handle);
      }
    }
    if (editable) {
      for (int i = 0; i < 4; i++) {
        PointF p = toView(quad[i * 2], quad[i * 2 + 1]);
        canvas.drawCircle(p.x, p.y, 14 * density, handle);
      }
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    if (!editable) return false;
    float x = ev.getX();
    float y = ev.getY();
    switch (ev.getActionMasked()) {
      case MotionEvent.ACTION_DOWN -> {
        dragIdx = nearestHandle(x, y);
        if (dragIdx >= 0) getParent().requestDisallowInterceptTouchEvent(true);
        return dragIdx >= 0;
      }
      case MotionEvent.ACTION_MOVE -> {
        if (dragIdx < 0) return false;
        if (dragIdx < 4) {
          float[] n = toNormalized(x, y);
          quad[dragIdx * 2] = n[0];
          quad[dragIdx * 2 + 1] = n[1];
        } else {
          // Project the touch onto the top edge to get the spine fraction.
          PointF tl = toView(quad[0], quad[1]);
          PointF tr = toView(quad[2], quad[3]);
          float dx = tr.x - tl.x;
          float dy = tr.y - tl.y;
          float len2 = dx * dx + dy * dy;
          float t = len2 <= 0 ? 0.5f : ((x - tl.x) * dx + (y - tl.y) * dy) / len2;
          spineX = Math.max(0.1f, Math.min(0.9f, t));
        }
        invalidate();
        return true;
      }
      case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        boolean was = dragIdx >= 0;
        dragIdx = -1;
        if (was) performClick();
        return was;
      }
      default -> {
        return false;
      }
    }
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  private int nearestHandle(float x, float y) {
    float best = 40 * density;
    int idx = -1;
    for (int i = 0; i < 4; i++) {
      PointF p = toView(quad[i * 2], quad[i * 2 + 1]);
      float d = (float) Math.hypot(p.x - x, p.y - y);
      if (d < best) {
        best = d;
        idx = i;
      }
    }
    if (spineX >= 0f) {
      PointF a = spinePoint(true);
      PointF b = spinePoint(false);
      float d = (float) Math.hypot((a.x + b.x) / 2f - x, (a.y + b.y) / 2f - y);
      if (d < best) idx = 4;
    }
    return idx;
  }
}

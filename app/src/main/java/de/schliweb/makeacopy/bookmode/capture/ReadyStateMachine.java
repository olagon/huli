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

/**
 * Decides the capture-border state from per-frame metrics. Plain Java so it can be unit tested with
 * scripted sequences. Thresholds come from the book's capture profile.
 */
public final class ReadyStateMachine {

  public enum State {
    TURNING,
    SETTLING,
    READY,
    CAPTURED
  }

  public static final class Params {
    public double stillThreshold = 1.0;
    public double turnThreshold = 12.0;
    public double sharpRatio = 0.85;
    public double paperMin = 0.55;
    public long settleMs = 300;
    public long autoHoldMs = 500;
  }

  private final Params p;
  private State state = State.SETTLING;
  private boolean armed = true;
  private long stillSince = -1;
  private long readySince = -1;

  public ReadyStateMachine(Params params) {
    this.p = params == null ? new Params() : params;
  }

  public Params params() {
    return p;
  }

  public State state() {
    return state;
  }

  public boolean isArmed() {
    return armed;
  }

  /**
   * @param motion mean absolute frame difference inside the page area
   * @param sharpness Laplacian variance divided by the baseline (1.0 = as sharp as at setup)
   * @param paperFraction share of page-area pixels that look like paper
   */
  public State update(double motion, double sharpness, double paperFraction, long nowMs) {
    if (motion > p.turnThreshold) {
      armed = true;
      stillSince = -1;
      readySince = -1;
      state = State.TURNING;
      return state;
    }
    if (state == State.CAPTURED) {
      return state; // wait for a page turn
    }
    if (motion > p.stillThreshold) {
      stillSince = -1;
      readySince = -1;
      state = State.SETTLING;
      return state;
    }
    if (stillSince < 0) stillSince = nowMs;
    boolean settled = nowMs - stillSince >= p.settleMs;
    boolean ok = settled && sharpness >= p.sharpRatio && paperFraction >= p.paperMin && armed;
    if (ok) {
      if (readySince < 0) readySince = nowMs;
      state = State.READY;
    } else {
      readySince = -1;
      state = State.SETTLING;
    }
    return state;
  }

  /** True when the border has been green long enough for auto capture. */
  public boolean shouldAutoCapture(long nowMs) {
    return state == State.READY && readySince >= 0 && nowMs - readySince >= p.autoHoldMs;
  }

  /** Call after a shot was taken. The machine stays CAPTURED until a page turn is seen. */
  public void onCaptured() {
    state = State.CAPTURED;
    armed = false;
    stillSince = -1;
    readySince = -1;
  }

  /** Re-arms without a page turn (e.g. after an undo or when the user confirms a same-page shot). */
  public void arm() {
    armed = true;
    if (state == State.CAPTURED) state = State.SETTLING;
  }
}

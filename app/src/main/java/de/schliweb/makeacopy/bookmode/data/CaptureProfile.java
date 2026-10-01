/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.data;

import com.google.gson.Gson;
import de.schliweb.makeacopy.bookmode.capture.ReadyStateMachine;

/** Per-book camera setup: fixed page area, locked-camera baselines and ready-border thresholds. */
public class CaptureProfile {
  private static final Gson GSON = new Gson();

  /** Page corners TL,TR,BR,BL as fractions (0..1) of the upright camera frame. */
  public float[] quad = {0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f};
  /** Spine position as a fraction along the top edge (TL → TR); only used for spreads. */
  public float spineX = 0.5f;
  /** Upright analysis frame size the quad was defined against (aspect only matters). */
  public int frameW = 3;
  public int frameH = 4;
  /** Extra rotation (0/90/180/270, clockwise) that makes the warped page upright. */
  public int rotationDeg;

  public double baselineSharpness;
  public double noiseFloor;
  public double paperBrightness;
  /** Laplacian variance of the setup still at the fixed 1000 px width, for the soft-shot check. */
  public double shotBaselineSharpness;

  public double stillThreshold = 1.0;
  public double turnThreshold = 12.0;
  public double sharpRatio = 0.85;
  public double paperMin = 0.55;
  public long settleMs = 300;
  public long autoHoldMs = 500;
  public long keyDebounceMs = 800;
  public boolean autoCapture;

  public void applyBaselines(double sharp, double noise, double paper) {
    baselineSharpness = sharp;
    noiseFloor = noise;
    paperBrightness = paper;
    stillThreshold = Math.max(1.0, 2.5 * noise);
    turnThreshold = Math.max(12.0, 10.0 * noise);
  }

  public ReadyStateMachine.Params toParams() {
    ReadyStateMachine.Params p = new ReadyStateMachine.Params();
    p.stillThreshold = stillThreshold;
    p.turnThreshold = turnThreshold;
    p.sharpRatio = sharpRatio;
    p.paperMin = paperMin;
    p.settleMs = settleMs;
    p.autoHoldMs = autoHoldMs;
    return p;
  }

  public String toJson() {
    return GSON.toJson(this);
  }

  public static CaptureProfile fromJson(String json) {
    if (json == null || json.isEmpty()) return new CaptureProfile();
    try {
      CaptureProfile p = GSON.fromJson(json, CaptureProfile.class);
      return p == null ? new CaptureProfile() : p;
    } catch (RuntimeException e) {
      return new CaptureProfile();
    }
  }
}

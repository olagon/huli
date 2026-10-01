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

import android.content.Context;
import android.hardware.camera2.CaptureRequest;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Small CameraX wrapper for Book Mode: 4:3 preview, 4:3 low-res analysis stream, 4:3 full-res
 * capture, plus focus/exposure/white-balance locking. All three use cases share the sensor's 4:3
 * field of view, so page-area fractions measured on the analysis frame apply to the captured JPEG.
 */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
public final class BookCamera {
  private static final String TAG = "BookCamera";

  private final Fragment fragment;
  private final PreviewView previewView;
  private ProcessCameraProvider provider;
  private Camera camera;
  private ImageCapture imageCapture;
  private ImageAnalysis analysis;
  private ExecutorService analysisExecutor;

  public BookCamera(Fragment fragment, PreviewView previewView) {
    this.fragment = fragment;
    this.previewView = previewView;
  }

  public void start(ImageAnalysis.Analyzer analyzer, Runnable onBound) {
    Context ctx = fragment.requireContext();
    ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(ctx);
    fut.addListener(
        () -> {
          try {
            provider = fut.get();
            bind(analyzer);
            if (onBound != null) onBound.run();
          } catch (Exception e) {
            Log.e(TAG, "Camera start failed", e);
          }
        },
        ContextCompat.getMainExecutor(ctx));
  }

  private void bind(ImageAnalysis.Analyzer analyzer) {
    if (provider == null || !fragment.isAdded()) return;
    previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
    previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
    int rotation = Surface.ROTATION_0;
    AspectRatioStrategy ar43 = new AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO);

    Preview preview =
        new Preview.Builder()
            .setResolutionSelector(new ResolutionSelector.Builder().setAspectRatioStrategy(ar43).build())
            .setTargetRotation(rotation)
            .build();
    preview.setSurfaceProvider(previewView.getSurfaceProvider());

    analysis =
        new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .setResolutionSelector(
                new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ar43)
                    .setResolutionStrategy(new ResolutionStrategy(new android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
            .build();
    if (analysisExecutor == null) analysisExecutor = Executors.newSingleThreadExecutor();
    analysis.setAnalyzer(analysisExecutor, analyzer);

    imageCapture =
        new ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(rotation)
            .setJpegQuality(95)
            .setResolutionSelector(
                new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ar43)
                    .setResolutionStrategy(new ResolutionStrategy(new android.util.Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
            .build();

    provider.unbindAll();
    camera = provider.bindToLifecycle(fragment.getViewLifecycleOwner(), CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture);
  }

  public boolean isBound() {
    return camera != null;
  }

  /** Locks AF on the frame centre, then locks AE and AWB. */
  public void lockAll(Consumer<Boolean> done) {
    if (camera == null) {
      done.accept(false);
      return;
    }
    // Sensor-oriented factory: works before the PreviewView is laid out, and the centre is the
    // same point in every orientation. The PreviewView factory was rejected on the Galaxy Z Fold.
    MeteringPoint pt = new SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, 0.15f);
    FocusMeteringAction action =
        new FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB)
            .disableAutoCancel()
            .build();
    ListenableFuture<FocusMeteringResult> fut = camera.getCameraControl().startFocusAndMetering(action);
    fut.addListener(
        () -> {
          boolean ok = false;
          try {
            ok = fut.get().isFocusSuccessful();
          } catch (Exception e) {
            Log.w(TAG, "focus lock: " + e.getMessage());
          }
          try {
            Camera2CameraControl.from(camera.getCameraControl())
                .setCaptureRequestOptions(
                    new CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                        .build());
          } catch (Exception e) {
            Log.w(TAG, "AE/AWB lock: " + e.getMessage());
          }
          done.accept(ok);
        },
        ContextCompat.getMainExecutor(fragment.requireContext()));
  }

  public void takePicture(File out, Consumer<Boolean> done) {
    if (imageCapture == null) {
      done.accept(false);
      return;
    }
    ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(out).build();
    imageCapture.takePicture(
        opts,
        ContextCompat.getMainExecutor(fragment.requireContext()),
        new ImageCapture.OnImageSavedCallback() {
          @Override
          public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
            done.accept(true);
          }

          @Override
          public void onError(@NonNull ImageCaptureException e) {
            Log.e(TAG, "capture failed", e);
            done.accept(false);
          }
        });
  }

  public void stop() {
    try {
      if (provider != null) provider.unbindAll();
    } catch (Exception ignore) {
      // best effort
    }
    camera = null;
    if (analysisExecutor != null) {
      analysisExecutor.shutdownNow();
      analysisExecutor = null;
    }
  }
}

/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.capture.BookCamera;
import de.schliweb.makeacopy.bookmode.capture.PageAreaView;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.data.BookFiles;
import de.schliweb.makeacopy.bookmode.data.CaptureProfile;
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.core.Point;

/** Setup shot: find the page area, drag corners and spine, lock the camera, measure baselines. */
public class BookSetupShotFragment extends Fragment {
  private static final String TAG = "BookSetupShot";
  private static final int SAMPLES = 12;

  private BookCamera camera;
  private PreviewView preview;
  private PageAreaView area;
  private ImageView still;
  private ImageView thumb;
  private TextView instructions;
  private Button detect;
  private Button rotate;
  private Button confirm;
  private BookEntity book;
  private CaptureProfile profile;
  private Bitmap stillBitmap;
  private Bitmap stillSmall;
  private int rotationDeg;
  private int frameW;
  private int frameH;
  private volatile boolean measuring;
  private final List<double[]> samples = new ArrayList<>(); // {lapVar, motion, brightness}
  private final de.schliweb.makeacopy.bookmode.capture.CaptureMeter meter = new de.schliweb.makeacopy.bookmode.capture.CaptureMeter();
  private ActivityResultLauncher<String> cameraPermission;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_book_setup_shot, container, false);
  }

  @Override
  public void onCreate(@Nullable Bundle saved) {
    super.onCreate(saved);
    cameraPermission =
        registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
              if (granted) startCamera();
              else Toast.makeText(requireContext(), R.string.msg_camera_permission_required, Toast.LENGTH_LONG).show();
            });
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    long bookId = requireArguments().getLong(BookArgs.BOOK_ID);
    book = BookDatabase.get(requireContext()).dao().getBook(bookId);
    profile = CaptureProfile.fromJson(book.captureProfileJson);
    preview = v.findViewById(R.id.book_preview);
    area = v.findViewById(R.id.book_page_area);
    still = v.findViewById(R.id.book_still);
    thumb = v.findViewById(R.id.book_warp_thumb);
    instructions = v.findViewById(R.id.book_setup_instructions);
    detect = v.findViewById(R.id.button_book_detect);
    rotate = v.findViewById(R.id.button_book_rotate);
    confirm = v.findViewById(R.id.button_book_confirm_area);
    area.setQuad(profile.quad);
    area.setSpineX(book.isSpread() ? profile.spineX : -1f);
    area.setBorderColor(0xFFFFC107);
    detect.setOnClickListener(x -> detectPage());
    rotate.setOnClickListener(
        x -> {
          rotationDeg = (rotationDeg + 90) % 360;
          updateThumb();
        });
    confirm.setOnClickListener(x -> lockAndMeasure());
    de.schliweb.makeacopy.utils.ui.UIUtils.applyBottomBarInsets(v.findViewById(R.id.book_setup_shot_buttons));
    camera = new BookCamera(this, preview);
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
    else cameraPermission.launch(Manifest.permission.CAMERA);
  }

  private void startCamera() {
    OpenCVUtils.init(requireContext().getApplicationContext());
    camera.start(this::analyze, null);
  }

  private void analyze(@NonNull ImageProxy image) {
    try {
      if (!measuring && frameW > 0) return;
      Mat gray = meter.gray(image);
      if (frameW == 0) {
        frameW = gray.cols();
        frameH = gray.rows();
        requireView().post(() -> area.setFrameSize(frameW, frameH));
      }
      if (measuring) {
        org.opencv.core.Rect roi = meter.roi(area.getQuad(), gray.cols(), gray.rows());
        de.schliweb.makeacopy.bookmode.capture.CaptureMeter.Values val = meter.measure(gray, roi, 0);
        samples.add(new double[] {val.laplacianVar(), val.motion(), val.brightness()});
        if (samples.size() >= SAMPLES + 1) {
          measuring = false;
          requireView().post(this::finishSetup);
        }
      }
      gray.release();
    } catch (Throwable t) {
      Log.w(TAG, "analyze: " + t.getMessage());
    } finally {
      image.close();
    }
  }

  private void detectPage() {
    detect.setEnabled(false);
    File tmp = new File(BookFiles.dir(requireContext(), book.id, "shots"), "setup.jpg");
    camera.takePicture(
        tmp,
        ok -> {
          if (!ok || !isAdded()) {
            detect.setEnabled(true);
            return;
          }
          BookDatabase.io(
              () -> {
                Bitmap full = ImageLoader.decode(requireContext(), tmp.getAbsolutePath(), null);
                if (full == null) return;
                Bitmap small = scale(full, 1200);
                Point[] det = null;
                try {
                  OpenCVUtils.DetectionResult r = OpenCVUtils.detectDocumentCornersResult(requireContext(), small);
                  if (r != null) det = r.corners();
                } catch (Throwable t) {
                  Log.w(TAG, "corner detection failed: " + t.getMessage());
                }
                float[] quad = null;
                if (det != null && det.length == 4 && det[0] != null && det[1] != null && det[2] != null && det[3] != null) {
                  quad = new float[8];
                  for (int i = 0; i < 4; i++) {
                    quad[i * 2] = (float) (det[i].x / small.getWidth());
                    quad[i * 2 + 1] = (float) (det[i].y / small.getHeight());
                  }
                }
                double shotSharp = de.schliweb.makeacopy.bookmode.capture.CaptureMeter.sharpnessAtWidth(full, quad != null ? quad : area.getQuad(), 1000);
                final float[] q = quad;
                requireView()
                    .post(
                        () -> {
                          if (!isAdded()) return;
                          if (stillBitmap != null) stillBitmap.recycle();
                          stillBitmap = full;
                          stillSmall = small;
                          profile.shotBaselineSharpness = shotSharp;
                          still.setImageBitmap(full);
                          still.setVisibility(View.VISIBLE);
                          if (q != null) area.setQuad(q);
                          area.setEditable(true);
                          area.setFrameSize(full.getWidth(), full.getHeight());
                          instructions.setText(R.string.book_setup_adjust);
                          rotate.setVisibility(View.VISIBLE);
                          confirm.setVisibility(View.VISIBLE);
                          thumb.setVisibility(View.VISIBLE);
                          detect.setEnabled(true);
                          updateThumb();
                          area.setOnTouchListener(
                              (vv, ev) -> {
                                boolean handled = area.onTouchEvent(ev);
                                if (handled && ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) updateThumb();
                                return handled;
                              });
                        });
              });
        });
  }

  private void updateThumb() {
    if (stillSmall == null) return;
    float[] q = area.getQuad();
    Point[] pts = new Point[4];
    for (int i = 0; i < 4; i++) pts[i] = new Point(q[i * 2] * stillSmall.getWidth(), q[i * 2 + 1] * stillSmall.getHeight());
    try {
      Bitmap warped = OpenCVUtils.applyPerspectiveCorrection(stillSmall, pts);
      Bitmap shown = rotationDeg == 0 ? warped : rotateBitmap(warped, rotationDeg);
      thumb.setImageBitmap(shown);
    } catch (Throwable t) {
      Log.w(TAG, "thumb: " + t.getMessage());
    }
  }

  private static Bitmap rotateBitmap(Bitmap src, int deg) {
    android.graphics.Matrix m = new android.graphics.Matrix();
    m.postRotate(deg);
    return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
  }

  private static Bitmap scale(Bitmap src, int maxLong) {
    int w = src.getWidth();
    int h = src.getHeight();
    float s = maxLong / (float) Math.max(w, h);
    if (s >= 1f) return src;
    return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
  }

  private void lockAndMeasure() {
    confirm.setEnabled(false);
    detect.setEnabled(false);
    area.setEditable(false);
    still.setVisibility(View.GONE);
    instructions.setText(R.string.book_measuring);
    camera.lockAll(
        ok -> {
          Log.i(TAG, "lock result " + ok);
          samples.clear();
          meter.reset();
          measuring = true;
        });
  }

  private void finishSetup() {
    if (!isAdded()) return;
    List<double[]> s = new ArrayList<>(samples);
    if (!s.isEmpty()) s.remove(0); // first frame has no motion reference
    if (s.size() < 3) {
      Toast.makeText(requireContext(), R.string.book_setup_failed, Toast.LENGTH_SHORT).show();
      confirm.setEnabled(true);
      detect.setEnabled(true);
      return;
    }
    profile.applyBaselines(median(s, 0), median(s, 1), median(s, 2));
    profile.quad = area.getQuad();
    profile.spineX = book.isSpread() ? area.getSpineX() : 0.5f;
    profile.frameW = frameW > 0 ? frameW : 3;
    profile.frameH = frameH > 0 ? frameH : 4;
    profile.rotationDeg = rotationDeg;
    book.captureProfileJson = profile.toJson();
    book.status = "CAPTURING";
    BookDatabase.get(requireContext()).dao().updateBook(book);
    Log.i(TAG, "profile " + book.captureProfileJson);
    Bundle args = new Bundle();
    args.putLong(BookArgs.BOOK_ID, book.id);
    args.putString(BookArgs.MODE, "append");
    NavOptions opts = new NavOptions.Builder().setPopUpTo(R.id.navigation_book_list, false).build();
    Navigation.findNavController(requireView()).navigate(R.id.navigation_book_capture, args, opts);
  }

  private static double median(List<double[]> s, int idx) {
    double[] v = new double[s.size()];
    for (int i = 0; i < v.length; i++) v[i] = s.get(i)[idx];
    Arrays.sort(v);
    return v[v.length / 2];
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    measuring = false;
    if (camera != null) camera.stop();
    meter.reset();
    if (stillBitmap != null) stillBitmap.recycle();
    stillBitmap = null;
    stillSmall = null;
  }
}

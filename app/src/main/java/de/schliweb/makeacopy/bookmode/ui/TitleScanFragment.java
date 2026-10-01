/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import androidx.lifecycle.SavedStateHandle;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.fragment.NavHostFragment;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.capture.BookCamera;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookDictionaries;
import de.schliweb.makeacopy.bookmode.hawaiian.HawaiianNormalizer;
import de.schliweb.makeacopy.bookmode.process.OcrDocBuilder;
import de.schliweb.makeacopy.bookmode.process.TitleExtractor;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Photographs a title page and reads the title and author from it. */
public class TitleScanFragment extends Fragment {
  private static final String TAG = "TitleScan";
  static final String RESULT_TITLE = "title_scan_title";
  static final String RESULT_AUTHOR = "title_scan_author";
  static final String RESULT_COVER = "title_scan_cover";
  private static final int OCR_LONG_EDGE = 2400;

  private BookCamera camera;
  private TextView hint;
  private Button scan;
  private ActivityResultLauncher<String> permission;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_title_scan, container, false);
  }

  @Override
  public void onCreate(@Nullable Bundle saved) {
    super.onCreate(saved);
    permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
      if (ok) startCamera();
      else Toast.makeText(requireContext(), R.string.msg_camera_permission_required, Toast.LENGTH_LONG).show();
    });
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    BookUi.fitSystemBars(v);
    hint = v.findViewById(R.id.title_hint);
    scan = v.findViewById(R.id.title_capture);
    v.findViewById(R.id.title_cancel).setOnClickListener(x -> NavHostFragment.findNavController(this).navigateUp());
    scan.setOnClickListener(x -> capture());
    camera = new BookCamera(this, (PreviewView) v.findViewById(R.id.title_preview));
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
    else permission.launch(Manifest.permission.CAMERA);
  }

  private void startCamera() {
    camera.start(ImageProxy::close, null);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (camera != null) camera.stop();
  }

  private void capture() {
    scan.setEnabled(false);
    hint.setText(R.string.title_scan_reading);
    Context app = requireContext().getApplicationContext();
    File shot = new File(app.getCacheDir(), "title_page.jpg");
    camera.takePicture(shot, ok -> {
      if (!ok) {
        failed();
        return;
      }
      BookDatabase.io(() -> {
        TitleExtractor.Result r = null;
        File cover = new File(app.getCacheDir(), "title_cover.jpg");
        try {
          r = read(app, shot, cover);
        } catch (Throwable t) {
          Log.w(TAG, "title scan failed", t);
        }
        TitleExtractor.Result result = r;
        hint.post(() -> {
          if (!isAdded()) return;
          if (result == null || result.title() == null || result.title().isEmpty()) {
            failed();
            return;
          }
          NavBackStackEntry prevEntry = NavHostFragment.findNavController(this).getPreviousBackStackEntry();
          if (prevEntry != null) {
            SavedStateHandle h = prevEntry.getSavedStateHandle();
            h.set(RESULT_TITLE, result.title());
            h.set(RESULT_AUTHOR, result.author() == null ? "" : result.author());
            h.set(RESULT_COVER, cover.getAbsolutePath());
          }
          NavHostFragment.findNavController(this).navigateUp();
        });
      });
    });
  }

  private void failed() {
    if (!isAdded()) return;
    scan.setEnabled(true);
    hint.setText(R.string.title_scan_failed);
  }

  private static TitleExtractor.Result read(Context ctx, File shot, File coverOut) throws Exception {
    OpenCVUtils.init(ctx);
    Bitmap full = ImageLoader.decode(ctx, shot.getAbsolutePath(), null);
    if (full == null) return null;
    float s = OCR_LONG_EDGE / (float) Math.max(full.getWidth(), full.getHeight());
    Bitmap bmp = s < 1f ? Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * s), Math.round(full.getHeight() * s), true) : full;
    if (bmp != full) full.recycle();
    try (FileOutputStream fos = new FileOutputStream(coverOut)) {
      Bitmap thumb = Bitmap.createScaledBitmap(bmp, Math.max(1, bmp.getWidth() / 3), Math.max(1, bmp.getHeight() / 3), true);
      thumb.compress(Bitmap.CompressFormat.JPEG, 85, fos);
      thumb.recycle();
    }
    OcrDoc doc;
    try (OCRHelper ocr = new OCRHelper(ctx)) {
      ocr.setLanguage("latin");
      ocr.setPaddleHighQualityDetectionEnabled(true);
      doc = OcrDocBuilder.build(ocr.runOcrWithWords(bmp), bmp.getWidth(), bmp.getHeight());
    } finally {
      bmp.recycle();
    }
    List<TitleExtractor.Line> lines = new ArrayList<>();
    for (List<OcrDoc.Word> ws : OcrDocBuilder.lines(doc)) {
      float[] hs = new float[ws.size()];
      float top = Float.MAX_VALUE;
      for (int i = 0; i < ws.size(); i++) {
        hs[i] = ws.get(i).b[3];
        top = Math.min(top, ws.get(i).b[1]);
      }
      Arrays.sort(hs);
      lines.add(new TitleExtractor.Line(OcrDocBuilder.text(ws), hs[hs.length / 2], top));
    }
    TitleExtractor.Result r = TitleExtractor.extract(lines);
    if (r == null) return null;
    HawaiianNormalizer n = new HawaiianNormalizer(BookDictionaries.haw(ctx), HawaiianNormalizer.DiacriticsMode.UNSURE);
    return new TitleExtractor.Result(normalize(n, r.title()), r.author() == null ? null : normalize(n, r.author()));
  }

  private static String normalize(HawaiianNormalizer n, String s) {
    StringBuilder sb = new StringBuilder();
    for (HawaiianNormalizer.WordResult w : n.normalizeWords(Arrays.asList(s.split(" ")))) {
      if (sb.length() > 0) sb.append(' ');
      sb.append(w.text);
    }
    return sb.toString();
  }
}

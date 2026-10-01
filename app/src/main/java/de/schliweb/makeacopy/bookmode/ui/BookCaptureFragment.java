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
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.capture.BookCamera;
import de.schliweb.makeacopy.bookmode.capture.CaptureMeter;
import de.schliweb.makeacopy.bookmode.capture.PageAreaView;
import de.schliweb.makeacopy.bookmode.capture.ReadyStateMachine;
import de.schliweb.makeacopy.bookmode.capture.ReadyStateMachine.State;
import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.data.BookFiles;
import de.schliweb.makeacopy.bookmode.data.BookPageEntity;
import de.schliweb.makeacopy.bookmode.data.BookShotEntity;
import de.schliweb.makeacopy.bookmode.data.CaptureProfile;
import de.schliweb.makeacopy.bookmode.process.BookProcessWorker;
import de.schliweb.makeacopy.bookmode.process.BookProcessor;
import de.schliweb.makeacopy.utils.image.ImageDecodeUtils;
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.ui.HapticsUtils;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

/** Capture screen with the red/yellow/green ready border (spec section 7). */
public class BookCaptureFragment extends Fragment {
  private static final String TAG = "BookCapture";
  private static final long ANALYSIS_INTERVAL_MS = 100;

  private BookCamera camera;
  private PageAreaView area;
  private TextView stateLabel;
  private TextView counter;
  private TextView softBanner;
  private ImageView thumb;
  private Button autoButton;
  private Button pauseButton;
  private View root;

  private BookDao dao;
  private BookEntity book;
  private CaptureProfile profile;
  private ReadyStateMachine sm;
  private final CaptureMeter meter = new CaptureMeter();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Deque<Long> sessionShots = new ArrayDeque<>();

  private String mode = "append";
  private int insertSeq = -1;
  private long replaceShotId = -1;
  private boolean insertShifted;

  private volatile boolean paused;
  private volatile boolean capturing;
  private volatile boolean auto;
  private volatile long lastAnalysis;
  private long lastKey;
  private State shownState;
  private ActivityResultLauncher<String> permission;
  private ActivityResultLauncher<String> notifPermission;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_book_capture, container, false);
  }

  @Override
  public void onCreate(@Nullable Bundle saved) {
    super.onCreate(saved);
    permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
      if (ok) startCamera();
      else Toast.makeText(requireContext(), R.string.msg_camera_permission_required, Toast.LENGTH_LONG).show();
    });
    notifPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {});
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    root = v;
    Bundle args = requireArguments();
    dao = BookDatabase.get(requireContext()).dao();
    book = dao.getBook(args.getLong(BookArgs.BOOK_ID));
    profile = CaptureProfile.fromJson(book.captureProfileJson);
    mode = args.getString(BookArgs.MODE, "append");
    insertSeq = args.getInt(BookArgs.SEQ, -1);
    replaceShotId = args.getLong(BookArgs.SHOT_ID, -1);
    sm = new ReadyStateMachine(profile.toParams());
    auto = profile.autoCapture;

    PreviewView preview = v.findViewById(R.id.book_capture_preview);
    area = v.findViewById(R.id.book_capture_area);
    stateLabel = v.findViewById(R.id.book_capture_state);
    counter = v.findViewById(R.id.book_capture_counter);
    softBanner = v.findViewById(R.id.book_capture_soft);
    thumb = v.findViewById(R.id.book_capture_thumb);
    autoButton = v.findViewById(R.id.button_book_auto);
    pauseButton = v.findViewById(R.id.button_book_pause);
    area.setQuad(profile.quad);
    area.setSpineX(book.isSpread() ? profile.spineX : -1f);
    area.setFrameSize(profile.frameW, profile.frameH);

    v.findViewById(R.id.button_book_capture).setOnClickListener(x -> capture(false));
    v.findViewById(R.id.button_book_undo).setOnClickListener(x -> undo());
    v.findViewById(R.id.button_book_finish).setOnClickListener(x -> finish());
    v.findViewById(R.id.button_book_retake).setOnClickListener(x -> retake());
    pauseButton.setOnClickListener(x -> {
      paused = !paused;
      pauseButton.setText(paused ? R.string.book_resume : R.string.book_pause);
    });
    autoButton.setOnClickListener(x -> {
      auto = !auto;
      profile.autoCapture = auto;
      saveProfile();
      renderAuto();
    });
    renderAuto();
    stateLabel.setOnLongClickListener(x -> {
      showThresholds();
      return true;
    });
    de.schliweb.makeacopy.utils.ui.UIUtils.applyBottomBarInsets(v.findViewById(R.id.book_capture_buttons));
    v.setFocusableInTouchMode(true);
    v.setOnKeyListener(this::onKey);
    updateCounter();

    camera = new BookCamera(this, preview);
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
    else permission.launch(Manifest.permission.CAMERA);
    if (Build.VERSION.SDK_INT >= 33
        && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }
  }

  private void startCamera() {
    OpenCVUtils.init(requireContext().getApplicationContext());
    camera.start(this::analyze, () -> {
      // Re-lock exposure/white balance/focus on the page centre: locks do not survive a rebind.
      camera.lockAll(ok -> Log.i(TAG, "relock " + ok));
    });
  }

  @Override
  public void onResume() {
    super.onResume();
    requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (root != null) root.requestFocus();
  }

  @Override
  public void onPause() {
    requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (camera != null) camera.stop();
    meter.reset();
  }

  // ---- frame analysis ----

  private void analyze(@NonNull ImageProxy image) {
    try {
      long now = System.currentTimeMillis();
      if (paused || now - lastAnalysis < ANALYSIS_INTERVAL_MS) return;
      lastAnalysis = now;
      Mat gray = meter.gray(image);
      Rect roi = meter.roi(profile.quad, gray.cols(), gray.rows());
      CaptureMeter.Values v = meter.measure(gray, roi, profile.paperBrightness);
      gray.release();
      double sharp = profile.baselineSharpness > 0 ? v.laplacianVar() / profile.baselineSharpness : 1.0;
      State s = sm.update(v.motion(), sharp, v.paperFraction(), now);
      boolean fire = auto && !capturing && sm.shouldAutoCapture(now);
      main.post(() -> {
        render(s);
        if (fire) capture(true);
      });
    } catch (Throwable t) {
      Log.w(TAG, "analyze: " + t.getMessage());
    } finally {
      image.close();
    }
  }

  private void render(State s) {
    if (!isAdded() || s == shownState) return;
    shownState = s;
    int color;
    int label;
    switch (s) {
      case TURNING -> {
        color = Color.RED;
        label = R.string.book_capture_state_turning;
      }
      case READY -> {
        color = 0xFF00E676;
        label = R.string.book_capture_state_ready;
      }
      case CAPTURED -> {
        color = Color.GRAY;
        label = R.string.book_capture_state_captured;
      }
      default -> {
        color = 0xFFFFC107;
        label = R.string.book_capture_state_settling;
      }
    }
    area.setBorderColor(color);
    stateLabel.setText(label);
    stateLabel.setBackgroundColor(color & 0xB0FFFFFF | 0xB0000000);
  }

  private void renderAuto() {
    autoButton.setText(getString(R.string.book_auto) + (auto ? " ✓" : ""));
  }

  // ---- capture ----

  private boolean onKey(View v, int code, KeyEvent ev) {
    boolean shutterKey =
        code == KeyEvent.KEYCODE_VOLUME_UP
            || code == KeyEvent.KEYCODE_VOLUME_DOWN
            || code == KeyEvent.KEYCODE_CAMERA
            || code == KeyEvent.KEYCODE_HEADSETHOOK
            || code == KeyEvent.KEYCODE_ENTER
            || code == KeyEvent.KEYCODE_DPAD_CENTER;
    if (!shutterKey) return false;
    if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
    long now = System.currentTimeMillis();
    if (now - lastKey < profile.keyDebounceMs) return true;
    lastKey = now;
    capture(false);
    return true;
  }

  private void capture(boolean force) {
    if (capturing || !camera.isBound()) return;
    if (!force && !sm.isArmed()) {
      new MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.book_same_page_title)
          .setMessage(R.string.book_same_page_msg)
          .setNegativeButton(R.string.book_cancel, null)
          .setPositiveButton(R.string.book_ok, (d, w) -> capture(true))
          .show();
      return;
    }
    capturing = true;
    File out = new File(BookFiles.dir(requireContext(), book.id, "shots"), "shot_" + System.currentTimeMillis() + ".jpg");
    camera.takePicture(out, ok -> {
      capturing = false;
      if (!ok || !isAdded()) return;
      onShotSaved(out);
    });
  }

  private void onShotSaved(File file) {
    sm.onCaptured();
    shownState = null;
    area.setBorderColor(Color.WHITE);
    main.postDelayed(() -> render(sm.state()), 150);
    HapticsUtils.vibrateOneShot(getContext(), 30L);
    softBanner.setVisibility(View.GONE);
    BookDatabase.io(() -> {
      BookShotEntity shot = new BookShotEntity();
      shot.bookId = book.id;
      shot.filePath = file.getAbsolutePath();
      shot.capturedAt = System.currentTimeMillis();
      switch (mode) {
        case "insert" -> {
          if (!insertShifted) {
            dao.shiftShots(book.id, insertSeq);
            dao.shiftPages(book.id, BookProcessor.pageIndex(book, insertSeq, BookPageEntity.SIDE_LEFT), book.isSpread() ? 2 : 1);
            insertShifted = true;
          }
          shot.seq = insertSeq;
        }
        case "replace" -> {
          BookShotEntity old = dao.getShot(replaceShotId);
          shot.seq = old == null ? dao.maxSeq(book.id) + 1 : old.seq;
          if (old != null) deleteShot(old);
        }
        default -> shot.seq = dao.maxSeq(book.id) + 1;
      }
      shot.id = dao.insertShot(shot);
      sessionShots.push(shot.id);
      // Soft-shot check on the saved image at a fixed width.
      float ratio = 1f;
      try {
        Bitmap bmp = ImageLoader.decode(requireContext(), file.getAbsolutePath(), null);
        if (bmp != null) {
          double var = CaptureMeter.sharpnessAtWidth(bmp, profile.quad, 1000);
          bmp.recycle();
          if (profile.shotBaselineSharpness > 0) ratio = (float) (var / profile.shotBaselineSharpness);
        }
      } catch (Throwable t) {
        Log.w(TAG, "sharpness check: " + t.getMessage());
      }
      shot.sharpnessRatio = ratio;
      dao.updateShot(shot);
      BookProcessWorker.enqueue(requireContext().getApplicationContext(), book.id);
      final float r = ratio;
      final Bitmap small = ImageDecodeUtils.decodeSampled(file.getAbsolutePath(), 240, 320);
      main.post(() -> {
        if (!isAdded()) return;
        thumb.setImageBitmap(small);
        softBanner.setVisibility(r < 0.7f ? View.VISIBLE : View.GONE);
        updateCounter();
        if (!"append".equals(mode) && r >= 0.7f) finish();
      });
    });
  }

  private void deleteShot(BookShotEntity s) {
    for (BookPageEntity p : dao.pagesForShot(s.id)) {
      dao.deleteFlagsForPage(p.id);
      delete(p.imagePath);
      delete(p.phoneOcrPath);
      delete(p.finalOcrPath);
    }
    dao.deletePagesForShot(s.id);
    delete(s.filePath);
    dao.deleteShot(s.id);
  }

  private static void delete(String path) {
    if (path != null && !new File(path).delete()) {
      // ignore
    }
  }

  private void undo() {
    Long id = sessionShots.poll();
    if (id == null) return;
    BookDatabase.io(() -> {
      BookShotEntity s = dao.getShot(id);
      if (s != null) deleteShot(s);
      main.post(() -> {
        if (!isAdded()) return;
        sm.arm();
        shownState = null;
        thumb.setImageBitmap(null);
        softBanner.setVisibility(View.GONE);
        updateCounter();
      });
    });
  }

  private void retake() {
    Long id = sessionShots.poll();
    if (id == null) return;
    BookDatabase.io(() -> {
      BookShotEntity s = dao.getShot(id);
      if (s != null) deleteShot(s);
      main.post(() -> {
        if (!isAdded()) return;
        softBanner.setVisibility(View.GONE);
        if ("replace".equals(mode)) replaceShotId = -1;
        if ("insert".equals(mode)) insertShifted = true;
        capture(true);
      });
    });
  }

  private void finish() {
    Bundle args = new Bundle();
    args.putLong(BookArgs.BOOK_ID, book.id);
    NavOptions opts = new NavOptions.Builder().setPopUpTo(R.id.navigation_book_list, false).build();
    Navigation.findNavController(requireView()).navigate(R.id.navigation_book_grid, args, opts);
  }

  private void updateCounter() {
    int seq;
    if ("insert".equals(mode) && !insertShifted) seq = insertSeq;
    else if ("replace".equals(mode) && replaceShotId >= 0) {
      BookShotEntity old = dao.getShot(replaceShotId);
      seq = old == null ? dao.maxSeq(book.id) + 1 : old.seq;
    } else seq = dao.maxSeq(book.id) + 1;
    if (!book.isSpread()) {
      counter.setText(getString(R.string.book_page_next, seq + 1));
    } else if (book.firstShotRightOnly && seq == 0) {
      counter.setText(getString(R.string.book_page_next, 1));
    } else {
      int left = BookProcessor.pageIndex(book, seq, BookPageEntity.SIDE_LEFT) + 1;
      counter.setText(getString(R.string.book_pages_next, left, left + 1));
    }
  }

  private void saveProfile() {
    book.captureProfileJson = profile.toJson();
    BookDatabase.io(() -> dao.updateBook(book));
  }

  /** Hidden debug screen: long-press the state label. */
  private void showThresholds() {
    LinearLayout box = new LinearLayout(requireContext());
    box.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (16 * getResources().getDisplayMetrics().density);
    box.setPadding(pad, pad, pad, 0);
    String[] names = {"stillThreshold", "turnThreshold", "sharpRatio", "paperMin", "settleMs", "autoHoldMs", "keyDebounceMs"};
    double[] values = {profile.stillThreshold, profile.turnThreshold, profile.sharpRatio, profile.paperMin, profile.settleMs, profile.autoHoldMs, profile.keyDebounceMs};
    EditText[] fields = new EditText[names.length];
    for (int i = 0; i < names.length; i++) {
      EditText e = new EditText(requireContext());
      e.setHint(names[i]);
      e.setText(String.valueOf(values[i]));
      e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
      box.addView(e);
      fields[i] = e;
    }
    TextView info = new TextView(requireContext());
    info.setText("baseline=" + (int) profile.baselineSharpness + " noise=" + String.format(java.util.Locale.US, "%.2f", profile.noiseFloor) + " paper=" + (int) profile.paperBrightness);
    box.addView(info);
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.book_thresholds)
        .setView(box)
        .setNegativeButton(R.string.book_cancel, null)
        .setPositiveButton(R.string.book_ok, (d, w) -> {
          try {
            profile.stillThreshold = Double.parseDouble(fields[0].getText().toString());
            profile.turnThreshold = Double.parseDouble(fields[1].getText().toString());
            profile.sharpRatio = Double.parseDouble(fields[2].getText().toString());
            profile.paperMin = Double.parseDouble(fields[3].getText().toString());
            profile.settleMs = (long) Double.parseDouble(fields[4].getText().toString());
            profile.autoHoldMs = (long) Double.parseDouble(fields[5].getText().toString());
            profile.keyDebounceMs = (long) Double.parseDouble(fields[6].getText().toString());
          } catch (NumberFormatException ignore) {
            return;
          }
          sm = new ReadyStateMachine(profile.toParams());
          saveProfile();
          Log.i(TAG, "thresholds " + profile.toJson());
        })
        .show();
  }
}

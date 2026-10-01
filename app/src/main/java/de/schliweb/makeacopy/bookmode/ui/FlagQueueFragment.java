/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookPageEntity;
import de.schliweb.makeacopy.bookmode.data.WordFlagEntity;
import de.schliweb.makeacopy.bookmode.process.WordFlagger;
import de.schliweb.makeacopy.bookmode.review.PageEdits;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.store.OcrJsonStore;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** One flagged word at a time, with the word's line as image context (spec section 11). */
public class FlagQueueFragment extends Fragment {
  private static final String TAG = "FlagQueue";

  private BookDao dao;
  private long bookId;
  private List<WordFlagEntity> flags;
  private int pos;
  private int total;
  private final Deque<WordFlagEntity> accepted = new ArrayDeque<>();

  private ImageView image;
  private TextView progress;
  private TextView reason;
  private Button phoneButton;
  private Button suggestionButton;
  private EditText edit;
  private View editorGroup;
  private TextView done;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_book_flags, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    BookUi.toolbar(this, v, getString(R.string.book_check_words));
    dao = BookDatabase.get(requireContext()).dao();
    bookId = requireArguments().getLong(BookArgs.BOOK_ID);
    image = v.findViewById(R.id.flag_image);
    progress = v.findViewById(R.id.flag_progress);
    reason = v.findViewById(R.id.flag_reason);
    phoneButton = v.findViewById(R.id.button_flag_phone);
    suggestionButton = v.findViewById(R.id.button_flag_suggestion);
    edit = v.findViewById(R.id.flag_edit);
    editorGroup = v.findViewById(R.id.flag_editor);
    done = v.findViewById(R.id.flag_done);
    DiacriticKeys.attach(v.findViewById(R.id.flag_keys), edit);
    phoneButton.setOnClickListener(x -> edit.setText(current().phoneText));
    suggestionButton.setOnClickListener(x -> edit.setText(current().suggestion));
    v.findViewById(R.id.button_flag_accept).setOnClickListener(x -> accept());
    v.findViewById(R.id.button_flag_skip).setOnClickListener(x -> next());
    v.findViewById(R.id.button_flag_undo).setOnClickListener(x -> undo());
    flags = dao.unresolvedFlags(bookId);
    total = dao.countFlags(bookId);
    pos = 0;
    show();
  }

  private WordFlagEntity current() {
    return flags.get(pos);
  }

  private void show() {
    int checked = total - Math.max(0, flags.size() - pos);
    progress.setText(getString(R.string.book_flag_progress, checked, total));
    if (pos >= flags.size()) {
      editorGroup.setVisibility(View.GONE);
      done.setVisibility(View.VISIBLE);
      image.setImageBitmap(null);
      return;
    }
    editorGroup.setVisibility(View.VISIBLE);
    done.setVisibility(View.GONE);
    WordFlagEntity f = current();
    reason.setText(reasonText(f.reason));
    phoneButton.setText(getString(R.string.book_phone_reading, f.phoneText));
    suggestionButton.setVisibility(f.suggestion == null || f.suggestion.isEmpty() ? View.GONE : View.VISIBLE);
    if (f.suggestion != null) suggestionButton.setText(getString(R.string.book_suggestion, f.suggestion));
    edit.setText(f.suggestion != null && !f.suggestion.isEmpty() ? f.suggestion : f.phoneText);
    edit.setSelection(edit.getText().length());
    BookDatabase.io(() -> {
      Bitmap b = crop(f);
      image.post(() -> {
        if (isAdded() && pos < flags.size() && current() == f) image.setImageBitmap(b);
      });
    });
  }

  private int reasonText(String r) {
    if (WordFlagger.LOW_CONF.equals(r)) return R.string.book_flag_reason_low;
    if (WordFlagger.LEADING_MARK.equals(r)) return R.string.book_flag_reason_leading;
    return R.string.book_flag_reason_unknown;
  }

  /** Crops the word's line from the page image and outlines the word. */
  private Bitmap crop(WordFlagEntity f) {
    BookPageEntity p = dao.getPage(f.pageId);
    if (p == null || p.imagePath == null || p.finalOcrPath == null) return null;
    OcrDoc doc = OcrJsonStore.load(new File(p.finalOcrPath));
    if (doc == null) return null;
    OcrDoc.Word word = null;
    for (OcrDoc.Word w : doc.words) if (w.id == f.wordIndex) word = w;
    if (word == null) return null;
    int[] lb = word.b;
    for (OcrDoc.Line line : doc.lines) if (line.id == word.l) lb = line.b;
    try (java.io.FileInputStream in = new java.io.FileInputStream(p.imagePath)) {
      BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(in, false);
      if (dec == null) return null;
      int pad = Math.max(12, lb[3]);
      Rect r = new Rect(Math.max(0, lb[0] - pad), Math.max(0, lb[1] - pad), Math.min(dec.getWidth(), lb[0] + lb[2] + pad), Math.min(dec.getHeight(), lb[1] + lb[3] + pad));
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inSampleSize = 1;
      while (r.width() / o.inSampleSize > 1600) o.inSampleSize *= 2;
      Bitmap region = dec.decodeRegion(r, o);
      dec.recycle();
      if (region == null) return null;
      Bitmap out = region.copy(Bitmap.Config.ARGB_8888, true);
      region.recycle();
      Canvas c = new Canvas(out);
      Paint paint = new Paint();
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(Math.max(2f, 4f / o.inSampleSize));
      paint.setColor(Color.RED);
      float s = 1f / o.inSampleSize;
      c.drawRect((word.b[0] - r.left) * s - 3, (word.b[1] - r.top) * s - 3, (word.b[0] + word.b[2] - r.left) * s + 3, (word.b[1] + word.b[3] - r.top) * s + 3, paint);
      return out;
    } catch (Exception e) {
      Log.w(TAG, "crop: " + e.getMessage());
      return null;
    }
  }

  private void accept() {
    if (pos >= flags.size()) return;
    WordFlagEntity f = current();
    String text = edit.getText().toString().trim();
    if (text.isEmpty()) return;
    f.resolvedText = text;
    f.resolved = true;
    dao.updateFlag(f);
    accepted.push(f);
    BookDatabase.io(() -> PageEdits.setWordText(dao, f.pageId, f.wordIndex, text));
    next();
  }

  private void next() {
    pos++;
    show();
  }

  private void undo() {
    WordFlagEntity f = accepted.poll();
    if (f == null) {
      if (pos > 0) {
        pos--;
        show();
      }
      return;
    }
    f.resolved = false;
    f.resolvedText = null;
    dao.updateFlag(f);
    BookDatabase.io(() -> PageEdits.setWordText(dao, f.pageId, f.wordIndex, f.phoneText));
    int idx = flags.indexOf(f);
    if (idx >= 0) pos = idx;
    show();
  }
}

/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookPageEntity;
import de.schliweb.makeacopy.bookmode.data.BookShotEntity;
import de.schliweb.makeacopy.bookmode.data.WordFlagEntity;
import de.schliweb.makeacopy.bookmode.process.OcrDocBuilder;
import de.schliweb.makeacopy.bookmode.process.WordFlagger;
import de.schliweb.makeacopy.bookmode.review.PageEdits;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.store.OcrJsonStore;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One page: its OCR text (tap a word to fix it) or the photo with word boxes. */
public class PageViewerFragment extends Fragment {
  private static final String SAVED_INDEX = "index";
  private static final int MAX_IMAGE_SIDE = 2400;
  private static final int COLOR_FLAGGED = 0x80FFB300;
  private static final int COLOR_FIXED = 0x5543A047;

  private BookDao dao;
  private final List<BookPageEntity> pages = new ArrayList<>();
  private int index;
  private OcrDoc doc;
  private int sample = 1;

  private MaterialToolbar toolbar;
  private TextView text;
  private View textScroll;
  private ZoomImageView image;
  private TextView position;
  private Button prev;
  private Button next;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_page_viewer, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    dao = BookDatabase.get(requireContext()).dao();
    long bookId = requireArguments().getLong(BookArgs.BOOK_ID);
    long pageId = requireArguments().getLong(BookArgs.PAGE_ID);
    for (BookPageEntity p : dao.pagesForBook(bookId)) if (!BookPageEntity.DELETED.equals(p.status)) pages.add(p);
    for (int i = 0; i < pages.size(); i++) if (pages.get(i).id == pageId) index = i;
    if (saved != null) index = Math.min(saved.getInt(SAVED_INDEX, index), Math.max(0, pages.size() - 1));

    toolbar = BookUi.toolbar(this, v, "");
    toolbar.getMenu().add(0, 1, 0, R.string.viewer_copy_text);
    toolbar.getMenu().add(0, 2, 1, R.string.book_action_reshoot);
    toolbar.getMenu().add(0, 3, 2, R.string.book_action_insert_after);
    toolbar.getMenu().add(0, 4, 3, R.string.book_action_blank);
    toolbar.getMenu().add(0, 5, 4, R.string.book_action_delete);
    toolbar.setOnMenuItemClickListener(this::onMenu);

    text = v.findViewById(R.id.viewer_text);
    textScroll = v.findViewById(R.id.viewer_text_scroll);
    image = v.findViewById(R.id.viewer_image);
    position = v.findViewById(R.id.viewer_position);
    prev = v.findViewById(R.id.viewer_prev);
    next = v.findViewById(R.id.viewer_next);
    text.setMovementMethod(LinkMovementMethod.getInstance());
    text.setHighlightColor(0);
    image.setOnImageTapListener(this::onImageTap);
    MaterialButtonToggleGroup mode = v.findViewById(R.id.viewer_mode);
    mode.addOnButtonCheckedListener((g, id, checked) -> {
      if (!checked) return;
      boolean showImage = id == R.id.viewer_mode_image;
      image.setVisibility(showImage ? View.VISIBLE : View.GONE);
      textScroll.setVisibility(showImage ? View.GONE : View.VISIBLE);
    });
    prev.setOnClickListener(x -> show(index - 1));
    next.setOnClickListener(x -> show(index + 1));
    show(index);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt(SAVED_INDEX, index);
  }

  private BookPageEntity page() {
    return pages.get(index);
  }

  private void show(int i) {
    if (pages.isEmpty()) {
      NavHostFragment.findNavController(this).navigateUp();
      return;
    }
    index = Math.max(0, Math.min(pages.size() - 1, i));
    BookPageEntity p = page();
    toolbar.setTitle(getString(R.string.viewer_page_title, index + 1));
    position.setText(getString(R.string.viewer_position, index + 1, pages.size()));
    prev.setEnabled(index > 0);
    next.setEnabled(index < pages.size() - 1);
    toolbar.getMenu().findItem(4).setTitle(BookPageEntity.BLANK.equals(p.status) ? R.string.book_action_unblank : R.string.book_action_blank);
    final long pageId = p.id;
    BookDatabase.io(() -> {
      OcrDoc d = p.finalOcrPath == null ? null : OcrJsonStore.load(new File(p.finalOcrPath));
      Set<Integer> flagged = new HashSet<>();
      Set<Integer> fixed = new HashSet<>();
      for (WordFlagEntity f : dao.flagsForPage(pageId)) {
        if (WordFlagger.AUTO_FIXED.equals(f.reason)) fixed.add(f.wordIndex);
        else if (!f.resolved) flagged.add(f.wordIndex);
      }
      if (d != null) for (OcrDoc.Word w : d.words) if (w.e && !flagged.contains(w.id)) fixed.add(w.id);
      String printed = p.printedPageNumber;
      Bitmap bmp = renderImage(p.imagePath, d, flagged, fixed);
      text.post(() -> {
        if (!isAdded() || page().id != pageId) return;
        doc = d;
        String sub = (printed != null ? getString(R.string.viewer_printed, printed) + " · " : "")
            + getString(R.string.viewer_to_check, flagged.size());
        toolbar.setSubtitle(sub);
        text.setText(buildText(d, flagged, fixed));
        image.setImageBitmap(bmp);
      });
    });
  }

  private CharSequence buildText(OcrDoc d, Set<Integer> flagged, Set<Integer> fixed) {
    if (d == null || d.words.isEmpty()) return getString(R.string.viewer_no_text);
    SpannableStringBuilder sb = new SpannableStringBuilder();
    for (List<OcrDoc.Word> line : OcrDocBuilder.lines(d)) {
      for (int k = 0; k < line.size(); k++) {
        OcrDoc.Word w = line.get(k);
        if (k > 0) sb.append(' ');
        int start = sb.length();
        sb.append(w.t == null ? "" : w.t);
        int end = sb.length();
        if (end == start) continue;
        if (flagged.contains(w.id)) sb.setSpan(new BackgroundColorSpan(COLOR_FLAGGED), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if (fixed.contains(w.id)) sb.setSpan(new BackgroundColorSpan(COLOR_FIXED), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ClickableSpan() {
          @Override
          public void onClick(@NonNull View widget) {
            editWord(w);
          }

          @Override
          public void updateDrawState(@NonNull TextPaint ds) {
            // plain text look, no link styling
          }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      sb.append('\n');
    }
    return sb;
  }

  /** Page photo, downsampled, with faint boxes for every word and strong ones for flagged words. */
  private Bitmap renderImage(String path, OcrDoc d, Set<Integer> flagged, Set<Integer> fixed) {
    if (path == null || !new File(path).exists()) return null;
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(path, o);
    int s = 1;
    while (Math.max(o.outWidth, o.outHeight) / s > MAX_IMAGE_SIDE) s *= 2;
    o.inJustDecodeBounds = false;
    o.inSampleSize = s;
    o.inMutable = true;
    Bitmap bmp = BitmapFactory.decodeFile(path, o);
    sample = s;
    if (bmp == null || d == null) return bmp;
    Canvas c = new Canvas(bmp);
    Paint faint = stroke(0x6000897B, 1.5f);
    Paint strong = stroke(0xFFFFA000, 4f);
    Paint good = stroke(0xFF43A047, 3f);
    for (OcrDoc.Word w : d.words) {
      Paint p = flagged.contains(w.id) ? strong : fixed.contains(w.id) ? good : faint;
      float pad = 3f;
      c.drawRect(w.b[0] / (float) s - pad, w.b[1] / (float) s - pad, (w.b[0] + w.b[2]) / (float) s + pad, (w.b[1] + w.b[3]) / (float) s + pad, p);
    }
    return bmp;
  }

  private static Paint stroke(int color, float width) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setStyle(Paint.Style.STROKE);
    p.setColor(color);
    p.setStrokeWidth(width);
    return p;
  }

  private void onImageTap(float x, float y) {
    if (doc == null) return;
    float px = x * sample;
    float py = y * sample;
    for (OcrDoc.Word w : doc.words) {
      if (px >= w.b[0] - 6 && px <= w.b[0] + w.b[2] + 6 && py >= w.b[1] - 6 && py <= w.b[1] + w.b[3] + 6) {
        editWord(w);
        return;
      }
    }
  }

  private void editWord(OcrDoc.Word w) {
    LinearLayout box = (LinearLayout) LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_word, null, false);
    EditText edit = box.findViewById(R.id.edit_word_text);
    edit.setText(w.t);
    edit.setSelection(edit.getText().length());
    DiacriticKeys.attach(box.findViewById(R.id.edit_word_keys), edit);
    long pageId = page().id;
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.viewer_edit_title)
        .setView(box)
        .setNegativeButton(R.string.book_cancel, null)
        .setPositiveButton(R.string.viewer_save, (dlg, which) -> {
          String t = edit.getText().toString().trim();
          if (t.isEmpty() || t.equals(w.t)) return;
          BookDatabase.io(() -> {
            PageEdits.correct(dao, pageId, w.id, t);
            text.post(() -> {
              if (isAdded()) show(index);
            });
          });
        })
        .show();
  }

  private boolean onMenu(MenuItem item) {
    BookPageEntity p = page();
    switch (item.getItemId()) {
      case 1 -> {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        String plain = text.getText().toString();
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(toolbar.getTitle(), plain));
        Toast.makeText(requireContext(), R.string.viewer_copied, Toast.LENGTH_SHORT).show();
      }
      case 2 -> BookUi.openCapture(this, p.bookId, "replace", -1, p.shotId);
      case 3 -> {
        BookShotEntity s = dao.getShot(p.shotId);
        BookUi.openCapture(this, p.bookId, "insert", s == null ? dao.maxSeq(p.bookId) + 1 : s.seq + 1, -1);
      }
      case 4 -> {
        p.status = BookPageEntity.BLANK.equals(p.status) ? BookPageEntity.OK : BookPageEntity.BLANK;
        dao.updatePage(p);
        show(index);
      }
      case 5 -> new MaterialAlertDialogBuilder(requireContext())
          .setMessage(R.string.viewer_delete_confirm)
          .setNegativeButton(R.string.book_cancel, null)
          .setPositiveButton(R.string.book_delete, (d, w) -> {
            p.status = BookPageEntity.DELETED;
            dao.updatePage(p);
            pages.remove(index);
            show(index);
          })
          .show();
      default -> {
        return false;
      }
    }
    return true;
  }
}

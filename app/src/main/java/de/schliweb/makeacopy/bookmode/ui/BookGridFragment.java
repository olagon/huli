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

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.data.BookFiles;
import de.schliweb.makeacopy.bookmode.data.BookPageEntity;
import de.schliweb.makeacopy.bookmode.data.BookShotEntity;
import de.schliweb.makeacopy.bookmode.data.WordFlagEntity;
import de.schliweb.makeacopy.bookmode.process.BookProcessWorker;
import de.schliweb.makeacopy.bookmode.review.PageNumberChecker;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.store.OcrJsonStore;
import de.schliweb.makeacopy.utils.image.ImageDecodeUtils;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Page grid with badges, page number check, and the book's actions (spec section 11). */
public class BookGridFragment extends Fragment {
  private static final ExecutorService THUMBS = Executors.newFixedThreadPool(2);

  private BookDao dao;
  private BookEntity book;
  private final List<BookPageEntity> pages = new ArrayList<>();
  private RecyclerView.Adapter<Holder> adapter;
  private TextView info;
  private TextView warning;
  private Button reshoot;
  private Button flagsButton;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable poll = this::refresh;
  private PageNumberChecker.Gap firstGap;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_book_grid, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    dao = BookDatabase.get(requireContext()).dao();
    book = dao.getBook(requireArguments().getLong(BookArgs.BOOK_ID));
    ((TextView) v.findViewById(R.id.book_grid_title)).setText(book.title);
    info = v.findViewById(R.id.book_grid_info);
    warning = v.findViewById(R.id.book_grid_warning);
    reshoot = v.findViewById(R.id.button_book_reshoot);
    flagsButton = v.findViewById(R.id.button_book_flags);
    RecyclerView grid = v.findViewById(R.id.book_grid);
    grid.setLayoutManager(new GridLayoutManager(requireContext(), 3));
    adapter =
        new RecyclerView.Adapter<>() {
          @NonNull
          @Override
          public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_book_page, parent, false));
          }

          @Override
          public void onBindViewHolder(@NonNull Holder h, int position) {
            bind(h, pages.get(position), position);
          }

          @Override
          public int getItemCount() {
            return pages.size();
          }
        };
    grid.setAdapter(adapter);
    v.findViewById(R.id.button_book_capture_more).setOnClickListener(x -> openCapture("append", -1, -1));
    flagsButton.setOnClickListener(x -> nav(R.id.navigation_book_flags));
    v.findViewById(R.id.button_book_export).setOnClickListener(x -> nav(R.id.navigation_book_export));
    v.findViewById(R.id.button_book_more).setOnClickListener(this::showMore);
    reshoot.setOnClickListener(x -> {
      if (firstGap == null) return;
      BookPageEntity after = pages.get(Math.min(firstGap.afterPageIndex(), pages.size() - 1));
      BookShotEntity s = dao.getShot(after.shotId);
      openCapture("insert", s == null ? dao.maxSeq(book.id) + 1 : s.seq + 1, -1);
    });
    de.schliweb.makeacopy.utils.ui.UIUtils.applyBottomBarInsets(v.findViewById(R.id.book_grid_buttons));
  }

  @Override
  public void onResume() {
    super.onResume();
    refresh();
  }

  @Override
  public void onPause() {
    main.removeCallbacks(poll);
    super.onPause();
  }

  private void refresh() {
    if (!isAdded()) return;
    pages.clear();
    for (BookPageEntity p : dao.pagesForBook(book.id)) if (!BookPageEntity.DELETED.equals(p.status)) pages.add(p);
    adapter.notifyDataSetChanged();
    int shots = dao.countShots(book.id);
    int done = dao.countShotsWithStatus(book.id, BookShotEntity.DONE);
    int failed = dao.countShotsWithStatus(book.id, BookShotEntity.FAILED);
    StringBuilder sb = new StringBuilder(getString(R.string.book_pages_count, pages.size()));
    if (done + failed < shots) sb.append(" · ").append(getString(R.string.book_processing_status, done + failed + 1, shots));
    if (failed > 0) sb.append(" · ").append(failed).append(" failed");
    sb.append(" · ").append(getString(R.string.book_storage_used, BookFiles.human(BookFiles.sizeOf(BookFiles.bookDir(requireContext(), book.id)))));
    info.setText(sb);
    requireView().findViewById(R.id.book_grid_empty).setVisibility(pages.isEmpty() ? View.VISIBLE : View.GONE);
    flagsButton.setText(getString(R.string.book_review_flags, dao.countUnresolvedFlags(book.id)));

    List<String> numbers = new ArrayList<>();
    for (BookPageEntity p : pages) numbers.add(BookPageEntity.BLANK.equals(p.status) ? null : p.printedPageNumber);
    List<PageNumberChecker.Gap> gaps = PageNumberChecker.check(numbers);
    firstGap = gaps.isEmpty() ? null : gaps.get(0);
    if (firstGap != null) {
      warning.setText(getString(firstGap.kind() == PageNumberChecker.Kind.SKIP ? R.string.book_page_gap : R.string.book_page_repeat, firstGap.from(), firstGap.to()));
      warning.setVisibility(View.VISIBLE);
      reshoot.setVisibility(View.VISIBLE);
    } else {
      warning.setVisibility(View.GONE);
      reshoot.setVisibility(View.GONE);
    }
    if (done + failed < shots) main.postDelayed(poll, 2000);
  }

  private void bind(Holder h, BookPageEntity p, int position) {
    h.label.setText(String.valueOf(position + 1) + (p.printedPageNumber != null ? " (" + p.printedPageNumber + ")" : ""));
    int color;
    String badge;
    if (BookPageEntity.BLANK.equals(p.status)) {
      color = 0xFF9E9E9E;
      badge = "blank";
    } else if (BookPageEntity.NEEDS_ATTENTION.equals(p.status)) {
      color = 0xFFD32F2F;
      badge = "!" + (p.flaggedCount > 0 ? " " + p.flaggedCount : "");
    } else if (p.flaggedCount > 0) {
      color = 0xFFFFA000;
      badge = String.valueOf(p.flaggedCount);
    } else {
      color = 0xFF388E3C;
      badge = "✓";
    }
    h.badge.setText(badge);
    h.badge.setBackgroundColor(color);
    h.image.setImageBitmap(null);
    h.image.setTag(p.imagePath);
    final String path = p.imagePath;
    THUMBS.execute(() -> {
      Bitmap b = path == null ? null : ImageDecodeUtils.decodeSampled(path, 300, 400);
      main.post(() -> {
        if (path != null && path.equals(h.image.getTag())) h.image.setImageBitmap(b);
      });
    });
    h.itemView.setOnClickListener(v -> showPageMenu(v, p, position));
  }

  private void showPageMenu(View anchor, BookPageEntity p, int position) {
    PopupMenu menu = new PopupMenu(requireContext(), anchor);
    menu.getMenu().add(0, 1, 0, R.string.book_action_reshoot);
    menu.getMenu().add(0, 2, 1, R.string.book_action_insert_after);
    menu.getMenu().add(0, 3, 2, BookPageEntity.BLANK.equals(p.status) ? R.string.book_action_unblank : R.string.book_action_blank);
    menu.getMenu().add(0, 4, 3, R.string.book_action_move_up);
    menu.getMenu().add(0, 5, 4, R.string.book_action_move_down);
    menu.getMenu().add(0, 6, 5, R.string.book_action_delete);
    menu.setOnMenuItemClickListener(item -> {
      BookShotEntity s = dao.getShot(p.shotId);
      switch (item.getItemId()) {
        case 1 -> openCapture("replace", -1, p.shotId);
        case 2 -> openCapture("insert", s == null ? dao.maxSeq(book.id) + 1 : s.seq + 1, -1);
        case 3 -> {
          p.status = BookPageEntity.BLANK.equals(p.status) ? BookPageEntity.OK : BookPageEntity.BLANK;
          dao.updatePage(p);
          refresh();
        }
        case 4, 5 -> {
          int other = item.getItemId() == 4 ? position - 1 : position + 1;
          if (other < 0 || other >= pages.size()) return true;
          BookPageEntity o = pages.get(other);
          int tmp = p.pageIndex;
          p.pageIndex = o.pageIndex;
          o.pageIndex = tmp;
          if (p.pageIndex == o.pageIndex) o.pageIndex += item.getItemId() == 4 ? 1 : -1;
          dao.updatePage(p);
          dao.updatePage(o);
          refresh();
        }
        case 6 -> {
          p.status = BookPageEntity.DELETED;
          dao.updatePage(p);
          refresh();
        }
        default -> {}
      }
      return true;
    });
    menu.show();
  }

  private void showMore(View anchor) {
    PopupMenu menu = new PopupMenu(requireContext(), anchor);
    menu.getMenu().add(0, 1, 0, getString(R.string.book_auto_fixed, dao.autoFixedFlags(book.id).size()));
    menu.getMenu().add(0, 2, 1, R.string.book_retry_failed);
    menu.getMenu().add(0, 3, 2, R.string.book_free_space);
    menu.getMenu().add(0, 4, 3, R.string.book_help);
    menu.getMenu().add(0, 5, 4, R.string.book_reprocess);
    menu.setOnMenuItemClickListener(item -> {
      switch (item.getItemId()) {
        case 1 -> showAutoFixed();
        case 2 -> {
          dao.retryFailedShots(book.id);
          BookProcessWorker.enqueue(requireContext().getApplicationContext(), book.id);
          refresh();
        }
        case 3 -> new MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.book_free_space_confirm)
            .setNegativeButton(R.string.book_cancel, null)
            .setPositiveButton(R.string.book_ok, (d, w) -> freeSpace())
            .show();
        case 4 -> nav(R.id.navigation_book_help);
        case 5 -> {
          dao.deleteOrphanFlags(book.id);
          dao.deleteOrphanPages(book.id);
          dao.resetAllShots(book.id);
          BookProcessWorker.enqueue(requireContext().getApplicationContext(), book.id);
          refresh();
        }
        default -> {}
      }
      return true;
    });
    menu.show();
  }

  private void freeSpace() {
    for (BookShotEntity s : dao.shotsForBook(book.id)) {
      if (BookShotEntity.DONE.equals(s.status) && s.filePath != null) {
        File f = new File(s.filePath);
        if (f.exists() && !f.delete()) {
          // ignore
        }
      }
    }
    refresh();
  }

  private void showAutoFixed() {
    List<WordFlagEntity> fixes = dao.autoFixedFlags(book.id);
    if (fixes.isEmpty()) return;
    String[] items = new String[fixes.size()];
    for (int i = 0; i < items.length; i++) items[i] = fixes.get(i).phoneText + " → " + fixes.get(i).resolvedText + "  (" + fixes.get(i).suggestion + ")";
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(getString(R.string.book_auto_fixed, fixes.size()))
        .setItems(items, (d, which) -> undoFix(fixes.get(which)))
        .setNegativeButton(R.string.book_cancel, null)
        .show();
  }

  private void undoFix(WordFlagEntity fix) {
    BookPageEntity p = dao.getPage(fix.pageId);
    if (p == null || p.finalOcrPath == null) return;
    OcrDoc doc = OcrJsonStore.load(new File(p.finalOcrPath));
    if (doc == null) return;
    for (OcrDoc.Word w : doc.words) {
      if (w.id == fix.wordIndex) {
        w.t = fix.phoneText;
        break;
      }
    }
    OcrJsonStore.save(new File(p.finalOcrPath), doc);
    dao.deleteFlag(fix);
  }

  private void openCapture(String mode, int seq, long shotId) {
    Bundle args = new Bundle();
    args.putLong(BookArgs.BOOK_ID, book.id);
    args.putString(BookArgs.MODE, mode);
    args.putInt(BookArgs.SEQ, seq);
    args.putLong(BookArgs.SHOT_ID, shotId);
    Navigation.findNavController(requireView()).navigate(R.id.navigation_book_capture, args);
  }

  private void nav(int dest) {
    Bundle args = new Bundle();
    args.putLong(BookArgs.BOOK_ID, book.id);
    Navigation.findNavController(requireView()).navigate(dest, args);
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final ImageView image;
    final TextView badge;
    final TextView label;

    Holder(View v) {
      super(v);
      image = v.findViewById(R.id.page_thumb);
      badge = v.findViewById(R.id.page_badge);
      label = v.findViewById(R.id.page_label);
    }
  }
}

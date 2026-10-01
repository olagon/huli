/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.data.BookFiles;
import de.schliweb.makeacopy.bookmode.data.BookPageEntity;
import de.schliweb.makeacopy.bookmode.data.BookShotEntity;
import de.schliweb.makeacopy.utils.image.ImageDecodeUtils;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home screen: your books, their status, and the way into a new scan. */
public class DashboardFragment extends Fragment {
  private static final ExecutorService COVERS = Executors.newSingleThreadExecutor();

  private final List<BookEntity> books = new ArrayList<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable poll = this::refresh;
  private RecyclerView.Adapter<Holder> adapter;
  private BookDao dao;
  private TextView summary;
  private View empty;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_dashboard, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    dao = BookDatabase.get(requireContext()).dao();
    summary = v.findViewById(R.id.dashboard_summary);
    empty = v.findViewById(R.id.dashboard_empty);
    RecyclerView list = v.findViewById(R.id.dashboard_books);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));
    adapter =
        new RecyclerView.Adapter<>() {
          @NonNull
          @Override
          public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_book, parent, false));
          }

          @Override
          public void onBindViewHolder(@NonNull Holder h, int position) {
            bind(h, books.get(position));
          }

          @Override
          public int getItemCount() {
            return books.size();
          }
        };
    list.setAdapter(adapter);
    v.findViewById(R.id.dashboard_new_book).setOnClickListener(x -> Navigation.findNavController(v).navigate(R.id.navigation_book_setup));
    v.findViewById(R.id.dashboard_about).setOnClickListener(x -> Navigation.findNavController(v).navigate(R.id.navigation_about));
    v.findViewById(R.id.dashboard_setup_guide).setOnClickListener(x -> Navigation.findNavController(v).navigate(R.id.navigation_about));
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
    main.removeCallbacks(poll);
    books.clear();
    books.addAll(dao.listBooks());
    adapter.notifyDataSetChanged();
    empty.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
    int pages = 0;
    int toCheck = 0;
    boolean busy = false;
    for (BookEntity b : books) {
      pages += dao.countPages(b.id);
      toCheck += dao.countUnresolvedFlags(b.id);
      busy |= isProcessing(b.id);
    }
    summary.setText(books.isEmpty() ? "" : getString(R.string.dashboard_summary, books.size(), pages, toCheck));
    summary.setVisibility(books.isEmpty() ? View.GONE : View.VISIBLE);
    if (busy) main.postDelayed(poll, 2000);
  }

  private boolean isProcessing(long bookId) {
    int shots = dao.countShots(bookId);
    int finished = dao.countShotsWithStatus(bookId, BookShotEntity.DONE) + dao.countShotsWithStatus(bookId, BookShotEntity.FAILED);
    return finished < shots;
  }

  private void bind(Holder h, BookEntity b) {
    h.title.setText(b.title);
    boolean hasAuthor = b.author != null && !b.author.isEmpty();
    h.author.setVisibility(hasAuthor ? View.VISIBLE : View.GONE);
    h.author.setText(hasAuthor ? b.author : "");
    int pages = dao.countPages(b.id);
    String when = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(b.createdAt));
    h.meta.setText(getString(R.string.book_pages_count, pages) + " · " + when);

    int shots = dao.countShots(b.id);
    int finished = dao.countShotsWithStatus(b.id, BookShotEntity.DONE) + dao.countShotsWithStatus(b.id, BookShotEntity.FAILED);
    int unresolved = dao.countUnresolvedFlags(b.id);
    int color;
    String status;
    if (finished < shots) {
      status = getString(R.string.book_processing_status, finished + 1, shots);
      color = R.color.huli_status_busy;
    } else if (pages == 0) {
      status = getString(R.string.dashboard_status_empty);
      color = R.color.huli_status_idle;
    } else if (unresolved > 0) {
      status = getString(R.string.dashboard_status_check, unresolved);
      color = R.color.huli_status_check;
    } else {
      status = getString(R.string.dashboard_status_ready);
      color = R.color.huli_status_ok;
    }
    h.status.setText(status);
    h.status.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), color)));

    h.cover.setImageDrawable(null);
    h.cover.setTag(b.id);
    File coverFile = new File(BookFiles.bookDir(requireContext(), b.id), "cover.jpg");
    String path = coverFile.exists() ? coverFile.getAbsolutePath() : firstPageImage(b.id);
    COVERS.execute(() -> {
      Bitmap bmp = path == null ? null : ImageDecodeUtils.decodeSampled(path, 160, 220);
      main.post(() -> {
        if (Long.valueOf(b.id).equals(h.cover.getTag())) h.cover.setImageBitmap(bmp);
      });
    });
    h.itemView.setOnClickListener(x -> {
      Bundle args = new Bundle();
      args.putLong(BookArgs.BOOK_ID, b.id);
      Navigation.findNavController(requireView()).navigate(R.id.navigation_book_grid, args);
    });
    h.itemView.setOnLongClickListener(x -> {
      confirmDelete(b);
      return true;
    });
  }

  private String firstPageImage(long bookId) {
    for (BookPageEntity p : dao.pagesForBook(bookId)) {
      if (!BookPageEntity.DELETED.equals(p.status) && p.imagePath != null) return p.imagePath;
    }
    return null;
  }

  private void confirmDelete(BookEntity b) {
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(b.title)
        .setMessage(R.string.book_delete_confirm)
        .setNegativeButton(R.string.book_cancel, null)
        .setPositiveButton(R.string.book_delete, (d, w) -> {
          dao.deleteFlagsForBook(b.id);
          dao.deletePagesForBook(b.id);
          dao.deleteShotsForBook(b.id);
          dao.deleteBookRow(b.id);
          BookFiles.deleteRecursively(BookFiles.bookDir(requireContext(), b.id));
          refresh();
        })
        .show();
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final ImageView cover;
    final TextView title;
    final TextView author;
    final TextView meta;
    final TextView status;

    Holder(View v) {
      super(v);
      cover = v.findViewById(R.id.book_item_cover);
      title = v.findViewById(R.id.book_item_title);
      author = v.findViewById(R.id.book_item_author);
      meta = v.findViewById(R.id.book_item_meta);
      status = v.findViewById(R.id.book_item_status);
    }
  }
}

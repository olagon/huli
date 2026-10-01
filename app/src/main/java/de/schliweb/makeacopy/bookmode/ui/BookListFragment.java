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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Book Mode home: the list of books plus "New book". */
public class BookListFragment extends Fragment {
  private final List<BookEntity> books = new ArrayList<>();
  private RecyclerView.Adapter<Holder> adapter;
  private BookDao dao;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_book_list, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    dao = BookDatabase.get(requireContext()).dao();
    RecyclerView list = v.findViewById(R.id.book_list);
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
            BookEntity b = books.get(position);
            h.title.setText(b.title);
            int pages = dao.countPages(b.id);
            String when = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(b.createdAt));
            h.subtitle.setText(getString(R.string.book_pages_count, pages) + " · " + when + " · " + BookFiles.human(BookFiles.sizeOf(BookFiles.bookDir(requireContext(), b.id))));
            h.itemView.setOnClickListener(x -> open(b.id));
            h.itemView.setOnLongClickListener(
                x -> {
                  confirmDelete(b);
                  return true;
                });
          }

          @Override
          public int getItemCount() {
            return books.size();
          }
        };
    list.setAdapter(adapter);
    v.findViewById(R.id.button_new_book).setOnClickListener(x -> Navigation.findNavController(v).navigate(R.id.navigation_book_setup));
    v.findViewById(R.id.button_book_help).setOnClickListener(x -> Navigation.findNavController(v).navigate(R.id.navigation_book_help));
    de.schliweb.makeacopy.utils.ui.UIUtils.applyBottomBarInsets(v.findViewById(R.id.book_list_buttons));
  }

  @Override
  public void onResume() {
    super.onResume();
    books.clear();
    books.addAll(dao.listBooks());
    adapter.notifyDataSetChanged();
    requireView().findViewById(R.id.book_list_empty).setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
  }

  private void open(long bookId) {
    Bundle args = new Bundle();
    args.putLong(BookArgs.BOOK_ID, bookId);
    Navigation.findNavController(requireView()).navigate(R.id.navigation_book_grid, args);
  }

  private void confirmDelete(BookEntity b) {
    new MaterialAlertDialogBuilder(requireContext())
        .setTitle(b.title)
        .setMessage(R.string.book_delete_confirm)
        .setNegativeButton(R.string.book_cancel, null)
        .setPositiveButton(
            R.string.book_delete,
            (d, w) -> {
              dao.deleteFlagsForBook(b.id);
              dao.deletePagesForBook(b.id);
              dao.deleteShotsForBook(b.id);
              dao.deleteBookRow(b.id);
              BookFiles.deleteRecursively(BookFiles.bookDir(requireContext(), b.id));
              onResume();
            })
        .show();
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final TextView title;
    final TextView subtitle;

    Holder(View v) {
      super(v);
      title = v.findViewById(R.id.book_item_title);
      subtitle = v.findViewById(R.id.book_item_subtitle);
    }
  }
}

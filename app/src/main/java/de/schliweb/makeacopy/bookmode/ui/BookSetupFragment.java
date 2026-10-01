/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.os.Bundle;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.data.BookFiles;
import de.schliweb.makeacopy.bookmode.data.CaptureProfile;

/** "New book" form (spec section 6). */
public class BookSetupFragment extends Fragment {
  private static final long LOW_STORAGE_BYTES = 2L * 1024 * 1024 * 1024;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_book_setup, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    BookUi.toolbar(this, v, getString(R.string.book_new));
    EditText title = v.findViewById(R.id.book_title);
    EditText author = v.findViewById(R.id.book_author);
    RadioGroup lang = v.findViewById(R.id.book_language_group);
    RadioGroup diac = v.findViewById(R.id.book_diacritics_group);
    RadioGroup layout = v.findViewById(R.id.book_layout_group);
    CheckBox firstRight = v.findViewById(R.id.book_first_right_only);
    RadioGroup dewarp = v.findViewById(R.id.book_dewarp_group);
    TextView storage = v.findViewById(R.id.book_storage_warning);
    android.widget.ImageView coverView = v.findViewById(R.id.book_cover_preview);
    v.findViewById(R.id.button_scan_title_page).setOnClickListener(x -> Navigation.findNavController(v).navigate(R.id.navigation_title_scan));
    // Result of the title-page scan (set by TitleScanFragment on this back stack entry).
    androidx.lifecycle.SavedStateHandle state = Navigation.findNavController(v).getCurrentBackStackEntry().getSavedStateHandle();
    state.<String>getLiveData(TitleScanFragment.RESULT_TITLE).observe(getViewLifecycleOwner(), t -> {
      if (t == null) return;
      title.setText(t);
      String a = state.get(TitleScanFragment.RESULT_AUTHOR);
      if (a != null && !a.isEmpty()) author.setText(a);
      state.remove(TitleScanFragment.RESULT_TITLE);
    });
    state.<String>getLiveData(TitleScanFragment.RESULT_COVER).observe(getViewLifecycleOwner(), path -> {
      if (path == null || !new java.io.File(path).exists()) return;
      coverView.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path));
      coverView.setVisibility(View.VISIBLE);
    });
    layout.setOnCheckedChangeListener((g, id) -> firstRight.setVisibility(id == R.id.book_layout_spread ? View.VISIBLE : View.GONE));

    try {
      StatFs fs = new StatFs(requireContext().getFilesDir().getAbsolutePath());
      long free = fs.getAvailableBytes();
      if (free < LOW_STORAGE_BYTES) {
        storage.setText(getString(R.string.book_low_storage, BookFiles.human(free)));
        storage.setVisibility(View.VISIBLE);
      }
    } catch (Exception ignore) {
      // best effort
    }

    v.findViewById(R.id.button_book_setup_next)
        .setOnClickListener(
            x -> {
              String t = title.getText().toString().trim();
              if (t.isEmpty()) {
                title.setError(getString(R.string.book_title_required));
                return;
              }
              BookEntity b = new BookEntity();
              b.title = t;
              b.author = author.getText().toString().trim();
              int l = lang.getCheckedRadioButtonId();
              b.languageMode = l == R.id.book_lang_eng ? BookEntity.LANG_ENG : l == R.id.book_lang_haw ? BookEntity.LANG_HAW : BookEntity.LANG_BOTH;
              int d = diac.getCheckedRadioButtonId();
              b.diacriticsMode = d == R.id.book_diac_with ? "WITH" : d == R.id.book_diac_without ? "WITHOUT" : "UNSURE";
              b.layoutMode = layout.getCheckedRadioButtonId() == R.id.book_layout_single ? BookEntity.LAYOUT_SINGLE : BookEntity.LAYOUT_SPREAD;
              b.firstShotRightOnly = b.isSpread() && firstRight.isChecked();
              b.dewarpMode = dewarp.getCheckedRadioButtonId() == R.id.book_dewarp_off ? BookEntity.DEWARP_OFF : BookEntity.DEWARP_AUTO;
              b.createdAt = System.currentTimeMillis();
              b.captureProfileJson = new CaptureProfile().toJson();
              long id = BookDatabase.get(requireContext()).dao().insertBook(b);
              String coverPath = state.get(TitleScanFragment.RESULT_COVER);
              if (coverPath != null) {
                java.io.File src = new java.io.File(coverPath);
                java.io.File dst = new java.io.File(BookFiles.bookDir(requireContext(), id), "cover.jpg");
                if (src.exists() && dst.getParentFile() != null && (dst.getParentFile().exists() || dst.getParentFile().mkdirs()) && !src.renameTo(dst)) {
                  android.util.Log.w("BookSetup", "could not keep the title page photo");
                }
                state.remove(TitleScanFragment.RESULT_COVER);
              }
              Bundle args = new Bundle();
              args.putLong(BookArgs.BOOK_ID, id);
              Navigation.findNavController(v).navigate(R.id.navigation_book_setup_shot, args);
            });
  }
}

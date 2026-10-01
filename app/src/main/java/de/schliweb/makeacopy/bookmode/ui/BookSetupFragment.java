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
    EditText title = v.findViewById(R.id.book_title);
    EditText author = v.findViewById(R.id.book_author);
    RadioGroup lang = v.findViewById(R.id.book_language_group);
    RadioGroup diac = v.findViewById(R.id.book_diacritics_group);
    RadioGroup layout = v.findViewById(R.id.book_layout_group);
    CheckBox firstRight = v.findViewById(R.id.book_first_right_only);
    RadioGroup dewarp = v.findViewById(R.id.book_dewarp_group);
    TextView storage = v.findViewById(R.id.book_storage_warning);
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
              Bundle args = new Bundle();
              args.putLong(BookArgs.BOOK_ID, id);
              Navigation.findNavController(v).navigate(R.id.navigation_book_setup_shot, args);
            });
    de.schliweb.makeacopy.utils.ui.UIUtils.applyBottomBarInsets(v.findViewById(R.id.book_setup_buttons));
  }
}

/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.os.Bundle;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.appbar.MaterialToolbar;
import de.schliweb.makeacopy.R;

/** Small shared bits for the Book Mode screens. */
final class BookUi {
  private BookUi() {}

  /** Sets up the shared toolbar include: title and a back arrow. */
  static MaterialToolbar toolbar(Fragment f, View root, CharSequence title) {
    MaterialToolbar tb = root.findViewById(R.id.book_toolbar);
    tb.setTitle(title);
    tb.setNavigationOnClickListener(v -> NavHostFragment.findNavController(f).navigateUp());
    return tb;
  }

  /** Pads a full-screen (camera) root so its content clears the status and navigation bars. */
  static void fitSystemBars(View root) {
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          androidx.core.graphics.Insets in =
              insets.getInsets(
                  androidx.core.view.WindowInsetsCompat.Type.systemBars()
                      | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
          v.setPadding(in.left, in.top, in.right, in.bottom);
          return insets;
        });
    androidx.core.view.ViewCompat.requestApplyInsets(root);
  }

  static void openCapture(Fragment f, long bookId, String mode, int seq, long shotId) {
    Bundle args = new Bundle();
    args.putLong(BookArgs.BOOK_ID, bookId);
    args.putString(BookArgs.MODE, mode);
    args.putInt(BookArgs.SEQ, seq);
    args.putLong(BookArgs.SHOT_ID, shotId);
    NavHostFragment.findNavController(f).navigate(R.id.navigation_book_capture, args);
  }
}

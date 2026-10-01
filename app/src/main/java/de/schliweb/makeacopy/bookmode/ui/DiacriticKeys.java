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

import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Fills a row with ʻ ā ē ī ō ū Ā Ē Ī Ō Ū buttons that insert into an EditText. */
public final class DiacriticKeys {
  private static final String[] KEYS = {"ʻ", "ā", "ē", "ī", "ō", "ū", "Ā", "Ē", "Ī", "Ō", "Ū"};

  private DiacriticKeys() {}

  public static void attach(ViewGroup row, EditText target) {
    row.removeAllViews();
    for (String k : KEYS) {
      Button b = new Button(row.getContext(), null, android.R.attr.buttonStyleSmall);
      b.setText(k);
      b.setAllCaps(false);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(0, 0, 0, 0);
      b.setTextSize(18f);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
      b.setLayoutParams(lp);
      b.setOnClickListener(
          v -> {
            int start = Math.max(0, target.getSelectionStart());
            int end = Math.max(start, target.getSelectionEnd());
            target.getText().replace(start, end, k);
            target.requestFocus();
          });
      row.addView(b);
    }
  }
}

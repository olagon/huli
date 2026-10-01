/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;

/** About Huli, with setup and scanning instructions and credits. */
public class AboutFragment extends Fragment {
  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_about, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    BookUi.toolbar(this, v, getString(R.string.about_title));
    ((TextView) v.findViewById(R.id.about_version)).setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));
    TextView body = v.findViewById(R.id.about_body);
    body.setText(Html.fromHtml(getString(R.string.about_html), Html.FROM_HTML_MODE_COMPACT));
    body.setMovementMethod(LinkMovementMethod.getInstance());
  }
}

/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.ui;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookDictionaries;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.data.BookPageEntity;
import de.schliweb.makeacopy.bookmode.export.BookTextFlow;
import de.schliweb.makeacopy.bookmode.export.DocxWriter;
import de.schliweb.makeacopy.bookmode.process.OcrDocBuilder;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.store.OcrJsonStore;
import de.schliweb.makeacopy.utils.export.PageFormat;
import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.export.PdfQualityPreset;
import de.schliweb.makeacopy.utils.image.DocumentCleanupMode;
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Export to searchable PDF and/or Word (spec section 12). */
public class BookExportFragment extends Fragment {
  private static final String TAG = "BookExport";
  private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  private BookDao dao;
  private BookEntity book;
  private CheckBox pdfBox;
  private CheckBox docxBox;
  private RadioGroup quality;
  private CheckBox dropHeaders;
  private CheckBox pageMarkers;
  private CheckBox pageBreaks;
  private EditText font;
  private TextView status;
  private ActivityResultLauncher<String> pdfPicker;
  private ActivityResultLauncher<String> docxPicker;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
    return inflater.inflate(R.layout.fragment_book_export, container, false);
  }

  @Override
  public void onCreate(@Nullable Bundle saved) {
    super.onCreate(saved);
    pdfPicker = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), uri -> {
      if (uri != null) writePdf(uri);
      else maybeDocx();
    });
    docxPicker = registerForActivityResult(new ActivityResultContracts.CreateDocument(DOCX_MIME), uri -> {
      if (uri != null) writeDocx(uri);
    });
  }

  @Override
  public void onViewCreated(@NonNull View v, @Nullable Bundle saved) {
    BookUi.toolbar(this, v, getString(R.string.book_export));
    dao = BookDatabase.get(requireContext()).dao();
    book = dao.getBook(requireArguments().getLong(BookArgs.BOOK_ID));
    pdfBox = v.findViewById(R.id.export_pdf);
    docxBox = v.findViewById(R.id.export_docx);
    quality = v.findViewById(R.id.export_quality);
    dropHeaders = v.findViewById(R.id.export_drop_headers);
    pageMarkers = v.findViewById(R.id.export_page_markers);
    pageBreaks = v.findViewById(R.id.export_page_breaks);
    font = v.findViewById(R.id.export_font);
    status = v.findViewById(R.id.export_status);
    v.findViewById(R.id.button_book_do_export).setOnClickListener(x -> start());
  }

  private List<BookPageEntity> pages() {
    List<BookPageEntity> out = new ArrayList<>();
    for (BookPageEntity p : dao.pagesForBook(book.id)) {
      if (BookPageEntity.DELETED.equals(p.status) || BookPageEntity.BLANK.equals(p.status) || p.imagePath == null) continue;
      out.add(p);
    }
    return out;
  }

  private String fileName(String ext) {
    String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    return book.title.replaceAll("[\\/:*?\"<>|]", "_") + " - " + date + ext;
  }

  private void start() {
    if (!pdfBox.isChecked() && !docxBox.isChecked()) {
      Toast.makeText(requireContext(), R.string.book_export_pick_one, Toast.LENGTH_SHORT).show();
      return;
    }
    if (pages().isEmpty()) {
      Toast.makeText(requireContext(), R.string.book_no_pages, Toast.LENGTH_SHORT).show();
      return;
    }
    if (pdfBox.isChecked()) pdfPicker.launch(fileName(".pdf"));
    else maybeDocx();
  }

  private void maybeDocx() {
    if (docxBox.isChecked()) docxPicker.launch(fileName(".docx"));
  }

  private PdfQualityPreset preset() {
    int id = quality.getCheckedRadioButtonId();
    if (id == R.id.export_quality_high) return PdfQualityPreset.HIGH;
    if (id == R.id.export_quality_small) return PdfQualityPreset.SMALL;
    return PdfQualityPreset.STANDARD;
  }

  private void writePdf(Uri uri) {
    status.setText(R.string.book_exporting);
    List<BookPageEntity> ps = pages();
    PdfQualityPreset q = preset();
    BookDatabase.io(() -> {
      try {
        PdfCreator.PageSource src =
            new PdfCreator.PageSource() {
              @Override
              public int getPageCount() {
                return ps.size();
              }

              @Override
              public Bitmap loadBitmap(int index) {
                return ImageLoader.decode(requireContext(), ps.get(index).imagePath, null);
              }

              @Override
              public List<RecognizedWord> loadWords(int index) {
                String path = ps.get(index).finalOcrPath;
                OcrDoc doc = path == null ? null : OcrJsonStore.load(new File(path));
                return OcrDocBuilder.toRecognizedWords(doc);
              }
            };
        Uri out =
            PdfCreator.createSearchablePdf(
                requireContext(),
                src,
                uri,
                q.jpegQuality,
                q.forceGrayscale,
                false,
                q.targetDpi,
                (i, n) -> status.post(() -> status.setText(getString(R.string.book_processing_progress, i + 1, n))),
                PdfCreator.BwMode.ROBUST,
                PageFormat.FIT_TO_IMAGE,
                DocumentCleanupMode.ORIGINAL,
                PdfCreator.TextLayerMode.WORD_POSITIONED);
        done(out != null ? null : "pdf");
      } catch (Throwable t) {
        Log.e(TAG, "pdf export", t);
        done(t.getMessage());
      }
      status.post(this::maybeDocx);
    });
  }

  private void writeDocx(Uri uri) {
    status.setText(R.string.book_exporting);
    List<BookPageEntity> ps = pages();
    BookTextFlow.Options opts = new BookTextFlow.Options(dropHeaders.isChecked(), pageMarkers.isChecked(), pageBreaks.isChecked());
    String bodyFont = font.getText().toString();
    BookDatabase.io(() -> {
      try {
        Set<String> eng = BookDictionaries.eng(requireContext());
        Set<String> haw = BookDictionaries.haw(requireContext());
        List<BookTextFlow.Page> flowPages = new ArrayList<>();
        for (BookPageEntity p : ps) {
          OcrDoc doc = p.finalOcrPath == null ? null : OcrJsonStore.load(new File(p.finalOcrPath));
          int h = doc == null ? 0 : doc.imageSize.h;
          flowPages.add(new BookTextFlow.Page(OcrDocBuilder.toFlowLines(doc), p.printedPageNumber, h));
        }
        List<DocxWriter.Para> paras = new ArrayList<>();
        paras.add(new DocxWriter.Para(DocxWriter.Style.TITLE, book.title));
        paras.addAll(BookTextFlow.build(flowPages, opts, w -> eng.contains(w) || haw.contains(w)));
        try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
          DocxWriter.write(os, book.title, book.author == null ? "" : book.author, bodyFont, paras);
        }
        done(null);
      } catch (Throwable t) {
        Log.e(TAG, "docx export", t);
        done(t.getMessage());
      }
    });
  }

  private void done(String error) {
    status.post(() -> {
      if (!isAdded()) return;
      status.setText(error == null ? getString(R.string.book_export_done) : getString(R.string.book_export_failed, error));
    });
  }
}

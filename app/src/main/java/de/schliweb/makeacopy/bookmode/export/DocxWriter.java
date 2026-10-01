/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal .docx writer (WordprocessingML) using only the JDK zip classes. */
public final class DocxWriter {

  public enum Style {
    TITLE("Title"),
    HEADING1("Heading1"),
    HEADING2("Heading2"),
    NORMAL("Normal"),
    FOOTNOTE("FootnoteText"),
    PAGE_MARKER("PageMarker"),
    PAGE_BREAK(null);

    final String id;

    Style(String id) {
      this.id = id;
    }
  }

  public record Para(Style style, String text) {}

  private DocxWriter() {}

  public static void write(
      OutputStream out, String title, String author, String bodyFont, List<Para> paras)
      throws IOException {
    String font = (bodyFont == null || bodyFont.isBlank()) ? "Arial" : bodyFont.trim();
    try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      put(zip, "[Content_Types].xml", CONTENT_TYPES);
      put(zip, "_rels/.rels", RELS);
      put(zip, "word/_rels/document.xml.rels", DOC_RELS);
      put(zip, "word/styles.xml", styles(font));
      put(zip, "word/document.xml", document(paras));
      put(zip, "docProps/core.xml", core(title, author));
      put(zip, "docProps/app.xml", APP);
    }
  }

  private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(xml.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  static String escape(String s) {
    if (s == null) return "";
    String n = Normalizer.normalize(s, Normalizer.Form.NFC);
    StringBuilder sb = new StringBuilder(n.length() + 16);
    for (int i = 0; i < n.length(); i++) {
      char c = n.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        default -> {
          // Drop control characters that are illegal in XML 1.0.
          if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c);
        }
      }
    }
    return sb.toString();
  }

  static String document(List<Para> paras) {
    StringBuilder sb = new StringBuilder(4096);
    sb.append(XML_HEAD)
        .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
    for (Para p : paras) {
      if (p == null) continue;
      if (p.style() == Style.PAGE_BREAK) {
        sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
        continue;
      }
      sb.append("<w:p><w:pPr><w:pStyle w:val=\"").append(p.style().id).append("\"/></w:pPr>");
      sb.append("<w:r><w:t xml:space=\"preserve\">").append(escape(p.text())).append("</w:t></w:r></w:p>");
    }
    sb.append("<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/>")
        .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>")
        .append("</w:sectPr></w:body></w:document>");
    return sb.toString();
  }

  private static String styles(String font) {
    String f = escape(font);
    return XML_HEAD
        + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + f + "\" w:hAnsi=\"" + f + "\" w:cs=\"" + f + "\" w:eastAsia=\"" + f + "\"/>"
        + "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>"
        + "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
        + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>"
        + "<w:pPr><w:spacing w:after=\"300\"/><w:jc w:val=\"center\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
        + "<w:pPr><w:keepNext/><w:spacing w:before=\"480\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
        + "<w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"30\"/><w:szCs w:val=\"30\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"FootnoteText\"><w:name w:val=\"footnote text\"/><w:basedOn w:val=\"Normal\"/>"
        + "<w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:rPr><w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"PageMarker\"><w:name w:val=\"Page Marker\"/><w:basedOn w:val=\"Normal\"/>"
        + "<w:pPr><w:spacing w:before=\"120\" w:after=\"120\"/></w:pPr><w:rPr><w:color w:val=\"808080\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/></w:rPr></w:style>"
        + "</w:styles>";
  }

  private static String core(String title, String author) {
    String now = Instant.now().toString().replaceAll("\\.\\d+Z$", "Z");
    return XML_HEAD
        + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
        + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
        + " xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
        + "<dc:title>" + escape(title) + "</dc:title><dc:creator>" + escape(author) + "</dc:creator>"
        + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
        + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
        + "</cp:coreProperties>";
  }

  private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

  private static final String CONTENT_TYPES =
      XML_HEAD
          + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
          + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
          + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
          + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
          + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
          + "</Types>";

  private static final String RELS =
      XML_HEAD
          + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
          + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
          + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
          + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
          + "</Relationships>";

  private static final String DOC_RELS =
      XML_HEAD
          + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
          + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
          + "</Relationships>";

  private static final String APP =
      XML_HEAD
          + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\""
          + " xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
          + "<Application>Huli</Application></Properties>";
}

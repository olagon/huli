/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "books")
public class BookEntity {
  public static final String LANG_ENG = "eng";
  public static final String LANG_HAW = "haw";
  public static final String LANG_BOTH = "haw+eng";
  public static final String LAYOUT_SPREAD = "SPREAD";
  public static final String LAYOUT_SINGLE = "SINGLE";
  public static final String DEWARP_AUTO = "AUTO";
  public static final String DEWARP_OFF = "OFF";

  @PrimaryKey(autoGenerate = true)
  public long id;

  public String title;
  public String author;
  public String languageMode = LANG_BOTH;
  /** WITH, WITHOUT or UNSURE (see HawaiianNormalizer.DiacriticsMode). */
  public String diacriticsMode = "UNSURE";
  public String layoutMode = LAYOUT_SPREAD;
  public boolean firstShotRightOnly;
  public String dewarpMode = DEWARP_AUTO;
  public boolean cloudAllowed;
  public String captureProfileJson;
  public long createdAt;
  public String status = "NEW";

  public boolean isSpread() {
    return LAYOUT_SPREAD.equals(layoutMode);
  }
}

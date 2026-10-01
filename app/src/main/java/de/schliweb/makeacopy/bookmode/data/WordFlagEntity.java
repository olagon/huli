/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.data;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "word_flags", indices = {@Index("pageId")})
public class WordFlagEntity {
  @PrimaryKey(autoGenerate = true)
  public long id;

  public long pageId;
  /** OcrDoc word id on the page. */
  public int wordIndex;
  public String reason;
  public String phoneText;
  public String cloudText;
  public String suggestion;
  public String resolvedText;
  public boolean resolved;
}

/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.data;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "book_pages", indices = {@Index("bookId"), @Index("shotId")})
public class BookPageEntity {
  public static final String SIDE_LEFT = "LEFT";
  public static final String SIDE_RIGHT = "RIGHT";
  public static final String SIDE_SINGLE = "SINGLE";
  public static final String OK = "OK";
  public static final String NEEDS_ATTENTION = "NEEDS_ATTENTION";
  public static final String BLANK = "BLANK";
  public static final String DELETED = "DELETED";

  @PrimaryKey(autoGenerate = true)
  public long id;

  public long bookId;
  public long shotId;
  public String side;
  public int pageIndex;
  public String printedPageNumber;
  public String imagePath;
  public String phoneOcrPath;
  public String cloudOcrPath;
  public String finalOcrPath;
  public boolean dewarpUsed;
  public float meanConfidence;
  public int flaggedCount;
  public int wordCount;
  public String status = OK;
}

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
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "book_shots", indices = {@Index("bookId")})
public class BookShotEntity {
  public static final String NEW = "NEW";
  public static final String PROCESSING = "PROCESSING";
  public static final String DONE = "DONE";
  public static final String FAILED = "FAILED";

  @PrimaryKey(autoGenerate = true)
  public long id;

  public long bookId;
  public int seq;
  public String filePath;
  public long capturedAt;
  /** Sharpness of the saved shot relative to the setup baseline (1.0 = as sharp). */
  public float sharpnessRatio = 1f;
  public String status = NEW;
}

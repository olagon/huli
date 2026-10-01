/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate Room database for Book Mode so the upstream AppDatabase stays untouched. */
@Database(
    entities = {BookEntity.class, BookShotEntity.class, BookPageEntity.class, WordFlagEntity.class},
    version = 1,
    exportSchema = false)
public abstract class BookDatabase extends RoomDatabase {
  private static volatile BookDatabase INSTANCE;

  /** Single background thread for file + database work from the UI. */
  public static final ExecutorService IO = Executors.newSingleThreadExecutor();

  public abstract BookDao dao();

  /** Runs on the IO thread; exceptions are logged instead of killing the process. */
  public static void io(Runnable r) {
    IO.execute(
        () -> {
          try {
            r.run();
          } catch (Throwable t) {
            android.util.Log.e("BookDatabase", "background task failed", t);
          }
        });
  }

  public static BookDatabase get(Context ctx) {
    if (INSTANCE == null) {
      synchronized (BookDatabase.class) {
        if (INSTANCE == null) {
          INSTANCE =
              Room.databaseBuilder(ctx.getApplicationContext(), BookDatabase.class, "bookmode.db")
                  // ponytail: tables are tiny (one row per page); main-thread reads keep the UI
                  // code short. Move to IO if a book ever makes the grid stutter.
                  .allowMainThreadQueries()
                  .fallbackToDestructiveMigration()
                  .build();
        }
      }
    }
    return INSTANCE;
  }
}

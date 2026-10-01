/*
 * Copyright 2026 Olin Lagon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.bookmode.process;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.bookmode.data.BookDao;
import de.schliweb.makeacopy.bookmode.data.BookDatabase;
import de.schliweb.makeacopy.bookmode.data.BookEntity;
import de.schliweb.makeacopy.bookmode.data.BookShotEntity;

/** Processes every NEW shot of a book, in capture order, as a foreground WorkManager job. */
public class BookProcessWorker extends Worker {
  private static final String TAG = "BookProcessWorker";
  public static final String INPUT_BOOK_ID = "bookId";
  private static final String CHANNEL = "book_processing";
  private static final int NOTIFICATION_ID = 4711;

  public BookProcessWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  public static void enqueue(Context ctx, long bookId) {
    OneTimeWorkRequest req =
        new OneTimeWorkRequest.Builder(BookProcessWorker.class)
            .setInputData(new Data.Builder().putLong(INPUT_BOOK_ID, bookId).build())
            .build();
    // APPEND_OR_REPLACE: a shot captured while the worker is finishing gets its own run.
    WorkManager.getInstance(ctx).enqueueUniqueWork("process-book-" + bookId, ExistingWorkPolicy.APPEND_OR_REPLACE, req);
  }

  @NonNull
  @Override
  public Result doWork() {
    long bookId = getInputData().getLong(INPUT_BOOK_ID, -1);
    BookDao dao = BookDatabase.get(getApplicationContext()).dao();
    BookEntity book = dao.getBook(bookId);
    if (book == null) return Result.success();
    BookProcessor processor = new BookProcessor(getApplicationContext());
    int done = 0;
    BookShotEntity shot;
    while ((shot = dao.nextNewShot(bookId)) != null && !isStopped()) {
      int total = dao.countShots(bookId);
      int finished = dao.countShotsWithStatus(bookId, BookShotEntity.DONE);
      try {
        setForegroundAsync(foregroundInfo(book.title, finished + 1, total));
      } catch (Throwable t) {
        Log.w(TAG, "foreground notification: " + t.getMessage());
      }
      shot.status = BookShotEntity.PROCESSING;
      dao.updateShot(shot);
      try {
        book = dao.getBook(bookId); // profile may have been tuned meanwhile
        processor.processShot(book, shot);
        shot.status = BookShotEntity.DONE;
        done++;
      } catch (Throwable t) {
        Log.e(TAG, "shot " + shot.seq + " failed", t);
        shot.status = BookShotEntity.FAILED;
      }
      dao.updateShot(shot);
    }
    Log.i(TAG, "book " + bookId + ": processed " + done + " shots");
    return Result.success();
  }

  private ForegroundInfo foregroundInfo(String title, int current, int total) {
    Context ctx = getApplicationContext();
    NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
      nm.createNotificationChannel(new NotificationChannel(CHANNEL, ctx.getString(R.string.book_processing_channel), NotificationManager.IMPORTANCE_LOW));
    }
    Notification n =
        new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_menu_book)
            .setContentTitle(title == null ? ctx.getString(R.string.book_mode) : title)
            .setContentText(ctx.getString(R.string.book_processing_progress, current, total))
            .setProgress(total, current, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    return new ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
  }
}

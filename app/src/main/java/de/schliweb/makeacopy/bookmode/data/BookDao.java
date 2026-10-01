/*
 * Copyright 2026 Olin Lagon
 * SPDX-License-Identifier: MIT
 */
package de.schliweb.makeacopy.bookmode.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface BookDao {
  // Books
  @Insert
  long insertBook(BookEntity b);

  @Update
  void updateBook(BookEntity b);

  @Query("SELECT * FROM books WHERE id = :id")
  BookEntity getBook(long id);

  @Query("SELECT * FROM books ORDER BY createdAt DESC")
  List<BookEntity> listBooks();

  @Query("DELETE FROM books WHERE id = :id")
  void deleteBookRow(long id);

  // Shots
  @Insert
  long insertShot(BookShotEntity s);

  @Update
  void updateShot(BookShotEntity s);

  @Query("SELECT * FROM book_shots WHERE id = :id")
  BookShotEntity getShot(long id);

  @Query("SELECT * FROM book_shots WHERE bookId = :bookId ORDER BY seq")
  List<BookShotEntity> shotsForBook(long bookId);

  @Query("SELECT * FROM book_shots WHERE bookId = :bookId AND status = 'NEW' ORDER BY seq LIMIT 1")
  BookShotEntity nextNewShot(long bookId);

  @Query("SELECT * FROM book_shots WHERE bookId = :bookId ORDER BY seq DESC LIMIT 1")
  BookShotEntity lastShot(long bookId);

  @Query("SELECT COALESCE(MAX(seq), -1) FROM book_shots WHERE bookId = :bookId")
  int maxSeq(long bookId);

  @Query("SELECT COUNT(*) FROM book_shots WHERE bookId = :bookId")
  int countShots(long bookId);

  @Query("SELECT COUNT(*) FROM book_shots WHERE bookId = :bookId AND status = :status")
  int countShotsWithStatus(long bookId, String status);

  @Query("UPDATE book_shots SET seq = seq + 1 WHERE bookId = :bookId AND seq >= :fromSeq")
  void shiftShots(long bookId, int fromSeq);

  @Query("UPDATE book_shots SET status = 'NEW' WHERE bookId = :bookId AND status = 'FAILED'")
  void retryFailedShots(long bookId);

  @Query("UPDATE book_shots SET status = 'NEW' WHERE bookId = :bookId")
  void resetAllShots(long bookId);

  @Query(
      "DELETE FROM word_flags WHERE pageId IN (SELECT id FROM book_pages WHERE bookId = :bookId "
          + "AND shotId NOT IN (SELECT id FROM book_shots WHERE bookId = :bookId))")
  void deleteOrphanFlags(long bookId);

  @Query(
      "DELETE FROM book_pages WHERE bookId = :bookId "
          + "AND shotId NOT IN (SELECT id FROM book_shots WHERE bookId = :bookId)")
  void deleteOrphanPages(long bookId);

  @Query("DELETE FROM book_shots WHERE id = :id")
  void deleteShot(long id);

  @Query("DELETE FROM book_shots WHERE bookId = :bookId")
  void deleteShotsForBook(long bookId);

  // Pages
  @Insert
  long insertPage(BookPageEntity p);

  @Update
  void updatePage(BookPageEntity p);

  @Query("SELECT * FROM book_pages WHERE id = :id")
  BookPageEntity getPage(long id);

  @Query("SELECT * FROM book_pages WHERE bookId = :bookId ORDER BY pageIndex, side")
  List<BookPageEntity> pagesForBook(long bookId);

  @Query("SELECT * FROM book_pages WHERE shotId = :shotId ORDER BY pageIndex")
  List<BookPageEntity> pagesForShot(long shotId);

  @Query("SELECT COUNT(*) FROM book_pages WHERE bookId = :bookId AND status != 'DELETED'")
  int countPages(long bookId);

  @Query("UPDATE book_pages SET pageIndex = pageIndex + :delta WHERE bookId = :bookId AND pageIndex >= :fromIndex")
  void shiftPages(long bookId, int fromIndex, int delta);

  @Query("DELETE FROM book_pages WHERE shotId = :shotId")
  void deletePagesForShot(long shotId);

  @Query("DELETE FROM book_pages WHERE bookId = :bookId")
  void deletePagesForBook(long bookId);

  // Flags
  @Insert
  void insertFlags(List<WordFlagEntity> flags);

  @Update
  void updateFlag(WordFlagEntity f);

  @Delete
  void deleteFlag(WordFlagEntity f);

  @Query("DELETE FROM word_flags WHERE pageId = :pageId")
  void deleteFlagsForPage(long pageId);

  @Query("DELETE FROM word_flags WHERE pageId IN (SELECT id FROM book_pages WHERE bookId = :bookId)")
  void deleteFlagsForBook(long bookId);

  @Query(
      "SELECT word_flags.* FROM word_flags JOIN book_pages ON word_flags.pageId = book_pages.id "
          + "WHERE book_pages.bookId = :bookId AND word_flags.resolved = 0 AND word_flags.reason != 'AUTO_FIXED' "
          + "AND book_pages.status != 'DELETED' AND book_pages.status != 'BLANK' "
          + "ORDER BY book_pages.pageIndex, word_flags.wordIndex")
  List<WordFlagEntity> unresolvedFlags(long bookId);

  @Query(
      "SELECT COUNT(*) FROM word_flags JOIN book_pages ON word_flags.pageId = book_pages.id "
          + "WHERE book_pages.bookId = :bookId AND word_flags.resolved = 0 AND word_flags.reason != 'AUTO_FIXED' "
          + "AND book_pages.status != 'DELETED' AND book_pages.status != 'BLANK'")
  int countUnresolvedFlags(long bookId);

  @Query(
      "SELECT COUNT(*) FROM word_flags JOIN book_pages ON word_flags.pageId = book_pages.id "
          + "WHERE book_pages.bookId = :bookId AND word_flags.reason != 'AUTO_FIXED' AND book_pages.status != 'DELETED'")
  int countFlags(long bookId);

  @Query(
      "SELECT word_flags.* FROM word_flags JOIN book_pages ON word_flags.pageId = book_pages.id "
          + "WHERE book_pages.bookId = :bookId AND word_flags.reason = 'AUTO_FIXED' AND word_flags.resolved = 1 "
          + "ORDER BY book_pages.pageIndex, word_flags.wordIndex")
  List<WordFlagEntity> autoFixedFlags(long bookId);

  @Query("SELECT * FROM word_flags WHERE pageId = :pageId")
  List<WordFlagEntity> flagsForPage(long pageId);

  @Query("SELECT COUNT(*) FROM word_flags WHERE pageId = :pageId AND resolved = 0 AND reason != 'AUTO_FIXED'")
  int countUnresolvedFlagsForPage(long pageId);

  @Query("SELECT * FROM word_flags WHERE pageId = :pageId AND wordIndex = :wordIndex")
  List<WordFlagEntity> flagsForWord(long pageId, int wordIndex);
}

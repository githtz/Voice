package de.ph1b.audiobook.data.repo

import de.ph1b.audiobook.common.Optional
import de.ph1b.audiobook.common.toOptional
import de.ph1b.audiobook.data.Book
import de.ph1b.audiobook.data.Chapter
import de.ph1b.audiobook.data.repo.internals.BookStorage
import de.ph1b.audiobook.data.repo.internals.IO
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.withContext
import timber.log.Timber
import java.io.File
import java.util.ArrayList
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides access to all books.
 */
@Singleton
class BookRepository
@Inject constructor(private val storage: BookStorage) {

  private val allBooks by lazy { storage.books() }
  private val active: MutableList<Book> by lazy {
    allBooks.filter { it.content.settings.active }
      .toMutableList()
      .apply { sort() }
  }
  private val orphaned: MutableList<Book> by lazy {
    allBooks.filter { it.content.settings.active }
      .toMutableList()
  }

  private val activeBooksSubject: BehaviorSubject<List<Book>> by lazy {
    BehaviorSubject.createDefault<List<Book>>(
      active
    )
  }

  fun booksStream(): Observable<List<Book>> = activeBooksSubject

  fun byId(id: UUID): Observable<Optional<Book>> {
    return activeBooksSubject.map {
      it.find { it.id == id }.toOptional()
    }
  }

  private suspend fun sortBooksAndNotifySubject() {
    active.sort()
    withContext(UI) {
      activeBooksSubject.onNext(active.toList())
    }
  }

  @Synchronized
  suspend fun addBook(book: Book) {
    withContext(IO) {
      Timber.v("addBook=${book.name}")

      storage.addOrUpdate(book)
      active.add(book)
      sortBooksAndNotifySubject()
    }
  }

  /** All active books. */
  val activeBooks: List<Book>
    get() = synchronized(this) { ArrayList(active) }

  @Synchronized
  fun bookById(id: UUID) = active.firstOrNull { it.id == id }

  @Synchronized
  fun getOrphanedBooks(): List<Book> = ArrayList(orphaned)

  @Synchronized
  suspend fun updateBook(book: Book) {
    if (bookById(book.id) == book) {
      return
    }
    withContext(IO) {
      val index = active.indexOfFirst { it.id == book.id }
      if (index != -1) {
        active[index] = book
        storage.addOrUpdate(book)
        withContext(UI) {
          sortBooksAndNotifySubject()
        }
      } else Timber.e("update failed as there was no book")
    }
  }

  suspend fun markBookAsPlayedNow(id: UUID) {
    withContext(IO) {
      val book = bookById(id)
          ?: return@withContext
      val updatedBook = book.update(
        updateSettings = {
          copy(lastPlayedAtMillis = System.currentTimeMillis())
        }
      )
      updateBook(updatedBook)
    }
  }

  @Synchronized
  suspend fun hideBook(toDelete: List<Book>) {
    withContext(IO) {
      Timber.v("hideBooks=${toDelete.size}")
      if (toDelete.isEmpty()) return@withContext

      val idsToDelete = toDelete.map(Book::id)
      active.removeAll { idsToDelete.contains(it.id) }
      orphaned.addAll(toDelete)
      toDelete.forEach { storage.hideBook(it.id) }
      sortBooksAndNotifySubject()
    }
  }

  @Synchronized
  suspend fun revealBook(book: Book) {
    withContext(IO) {
      Timber.v("Called revealBook=$book")

      orphaned.removeAll { it.id == book.id }
      active.add(book)
      storage.revealBook(book.id)
      sortBooksAndNotifySubject()
    }
  }

  @Synchronized
  fun chapterByFile(file: File) = chapterByFile(file, active) ?: chapterByFile(file, orphaned)

  private fun chapterByFile(file: File, books: List<Book>): Chapter? {
    books.forEach {
      it.content.chapters.forEach {
        if (it.file == file) return it
      }
    }
    return null
  }
}

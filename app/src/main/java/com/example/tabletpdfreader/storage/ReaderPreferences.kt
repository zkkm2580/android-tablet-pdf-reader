package com.example.tabletpdfreader.storage

import android.content.Context
import com.example.tabletpdfreader.model.BookmarkItem
import com.example.tabletpdfreader.model.RecentBook
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 阅读器持久化配置管理：
 * - 保存每本 PDF 的书签列表
 * - 保存每本 PDF 的上次阅读页码
 * - 保存翻页方向（左右滑动 / 上下滑动）
 * - 保存阅读主题模式（日间 / 护眼羊皮纸 / 夜间反色）
 * - 保存最近打开的 PDF 列表
 */
class ReaderPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class SwipeDirection {
        HORIZONTAL, // 左右滑动翻页
        VERTICAL    // 上下滑动翻页
    }

    enum class ReadingTheme {
        DAY,        // 标准日间白底
        SEPIA,      // 护眼暖色羊皮纸
        NIGHT       // 夜间暗色反相
    }

    var swipeDirection: SwipeDirection
        get() {
            val raw = prefs.getString(KEY_SWIPE_DIRECTION, SwipeDirection.HORIZONTAL.name)
            return try {
                SwipeDirection.valueOf(raw ?: SwipeDirection.HORIZONTAL.name)
            } catch (_: Exception) {
                SwipeDirection.HORIZONTAL
            }
        }
        set(value) {
            prefs.edit().putString(KEY_SWIPE_DIRECTION, value.name).apply()
        }

    var readingTheme: ReadingTheme
        get() {
            val raw = prefs.getString(KEY_READING_THEME, ReadingTheme.DAY.name)
            return try {
                ReadingTheme.valueOf(raw ?: ReadingTheme.DAY.name)
            } catch (_: Exception) {
                ReadingTheme.DAY
            }
        }
        set(value) {
            prefs.edit().putString(KEY_READING_THEME, value.name).apply()
        }

    fun getLastPage(docKey: String): Int {
        return prefs.getInt("last_page_$docKey", 0)
    }

    fun setLastPage(docKey: String, pageIndex: Int) {
        prefs.edit().putInt("last_page_$docKey", pageIndex).apply()
    }

    fun getBookmarks(docKey: String): List<BookmarkItem> {
        val raw = prefs.getString("bookmarks_$docKey", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<BookmarkItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BookmarkItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        pageIndex = obj.getInt("pageIndex"),
                        title = obj.optString("title", "书签"),
                        note = obj.optString("note", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            list.sortedBy { it.pageIndex }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isPageBookmarked(docKey: String, pageIndex: Int): Boolean {
        return getBookmarks(docKey).any { it.pageIndex == pageIndex }
    }

    fun addOrUpdateBookmark(
        docKey: String,
        pageIndex: Int,
        title: String,
        note: String
    ): List<BookmarkItem> {
        val current = getBookmarks(docKey).toMutableList()
        val existingIdx = current.indexOfFirst { it.pageIndex == pageIndex }
        val item = BookmarkItem(
            id = if (existingIdx >= 0) current[existingIdx].id else UUID.randomUUID().toString(),
            pageIndex = pageIndex,
            title = title.ifBlank { "第 ${pageIndex + 1} 页书签" },
            note = note.trim(),
            timestamp = System.currentTimeMillis()
        )
        if (existingIdx >= 0) {
            current[existingIdx] = item
        } else {
            current.add(item)
        }
        val sorted = current.sortedBy { it.pageIndex }
        saveBookmarks(docKey, sorted)
        return sorted
    }

    fun removeBookmark(docKey: String, bookmarkId: String): List<BookmarkItem> {
        val updated = getBookmarks(docKey).filterNot { it.id == bookmarkId }
        saveBookmarks(docKey, updated)
        return updated
    }

    fun removeBookmarkAtPage(docKey: String, pageIndex: Int): List<BookmarkItem> {
        val updated = getBookmarks(docKey).filterNot { it.pageIndex == pageIndex }
        saveBookmarks(docKey, updated)
        return updated
    }

    private fun saveBookmarks(docKey: String, bookmarks: List<BookmarkItem>) {
        val array = JSONArray()
        for (b in bookmarks) {
            val obj = JSONObject().apply {
                put("id", b.id)
                put("pageIndex", b.pageIndex)
                put("title", b.title)
                put("note", b.note)
                put("timestamp", b.timestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString("bookmarks_$docKey", array.toString()).apply()
    }

    fun recordRecentBook(book: RecentBook) {
        val current = getRecentBooks().filterNot { it.docKey == book.docKey }.toMutableList()
        current.add(0, book)
        val trimmed = current.take(12)
        val array = JSONArray()
        for (item in trimmed) {
            val obj = JSONObject().apply {
                put("docKey", item.docKey)
                put("uriString", item.uriString)
                put("fileName", item.fileName)
                put("lastPage", item.lastPage)
                put("totalPages", item.totalPages)
                put("lastOpened", item.lastOpened)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_RECENT_BOOKS, array.toString()).apply()
    }

    fun getRecentBooks(): List<RecentBook> {
        val raw = prefs.getString(KEY_RECENT_BOOKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<RecentBook>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    RecentBook(
                        docKey = obj.getString("docKey"),
                        uriString = obj.getString("uriString"),
                        fileName = obj.getString("fileName"),
                        lastPage = obj.optInt("lastPage", 0),
                        totalPages = obj.optInt("totalPages", 1),
                        lastOpened = obj.optLong("lastOpened", 0L)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "tablet_pdf_reader_prefs"
        private const val KEY_SWIPE_DIRECTION = "swipe_direction"
        private const val KEY_READING_THEME = "reading_theme"
        private const val KEY_RECENT_BOOKS = "recent_books"
    }
}

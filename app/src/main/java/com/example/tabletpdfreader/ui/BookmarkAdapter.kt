package com.example.tabletpdfreader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tabletpdfreader.databinding.ItemBookmarkEntryBinding
import com.example.tabletpdfreader.model.BookmarkItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书签列表适配器：支持书签搜索、点击跳转页码、编辑与删除书签
 */
class BookmarkAdapter(
    private val onBookmarkClick: (BookmarkItem) -> Unit,
    private val onBookmarkDelete: (BookmarkItem) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.BookmarkViewHolder>() {

    private var fullList: List<BookmarkItem> = emptyList()
    private var filteredList: List<BookmarkItem> = emptyList()
    private var currentQuery: String = ""
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(items: List<BookmarkItem>) {
        fullList = items
        applyFilter(currentQuery)
    }

    fun filter(query: String) {
        currentQuery = query.trim()
        applyFilter(currentQuery)
    }

    private fun applyFilter(query: String) {
        filteredList = if (query.isEmpty()) {
            fullList
        } else {
            fullList.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.note.contains(query, ignoreCase = true) ||
                    (it.pageIndex + 1).toString().contains(query)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkViewHolder {
        val binding = ItemBookmarkEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BookmarkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkViewHolder, position: Int) {
        holder.bind(filteredList[position])
    }

    override fun getItemCount(): Int = filteredList.size

    inner class BookmarkViewHolder(
        private val binding: ItemBookmarkEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BookmarkItem) {
            binding.bookmarkTitleText.text = item.title
            binding.bookmarkPageBadge.text = "第 ${item.pageIndex + 1} 页"
            binding.bookmarkTimeText.text = dateFormat.format(Date(item.timestamp))

            if (item.note.isNotBlank()) {
                binding.bookmarkNoteText.visibility = View.VISIBLE
                binding.bookmarkNoteText.text = item.note
            } else {
                binding.bookmarkNoteText.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onBookmarkClick(item)
            }

            binding.btnDeleteBookmark.setOnClickListener {
                onBookmarkDelete(item)
            }
        }
    }
}

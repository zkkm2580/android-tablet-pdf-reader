package com.example.tabletpdfreader.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tabletpdfreader.databinding.ItemTocEntryBinding
import com.example.tabletpdfreader.model.TocItem

/**
 * PDF 目录（TOC）列表适配器：支持多级缩进、当前阅读章节高亮、点击快速跳转
 */
class TocAdapter(
    private val onTocClicked: (TocItem) -> Unit
) : RecyclerView.Adapter<TocAdapter.TocViewHolder>() {

    private var fullList: List<TocItem> = emptyList()
    private var filteredList: List<TocItem> = emptyList()
    private var currentPageIndex: Int = 0
    private var currentQuery: String = ""

    fun submitList(items: List<TocItem>, activePage: Int) {
        fullList = items
        currentPageIndex = activePage
        applyFilter(currentQuery)
    }

    fun updateCurrentPage(activePage: Int) {
        if (currentPageIndex != activePage) {
            currentPageIndex = activePage
            notifyDataSetChanged()
        }
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
                    (it.pageIndex + 1).toString().contains(query)
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocViewHolder {
        val binding = ItemTocEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TocViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
        holder.bind(filteredList[position], position)
    }

    override fun getItemCount(): Int = filteredList.size

    inner class TocViewHolder(
        private val binding: ItemTocEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TocItem, position: Int) {
            val density = binding.root.resources.displayMetrics.density
            val indentPx = (item.level * 20 * density).toInt()
            binding.tocItemContainer.setPadding(
                (14 * density).toInt() + indentPx,
                (10 * density).toInt(),
                (14 * density).toInt(),
                (10 * density).toInt()
            )

            val prefix = when (item.level) {
                0 -> "▸ "
                1 -> "  • "
                else -> "    ◦ "
            }
            binding.tocTitleText.text = "$prefix${item.title}"
            binding.tocPageText.text = "P.${item.pageIndex + 1}"

            // 判断当前页是否属于该目录项区间
            val nextPageIndex = filteredList.getOrNull(position + 1)?.pageIndex ?: Int.MAX_VALUE
            val isActive = currentPageIndex >= item.pageIndex &&
                (currentPageIndex < nextPageIndex || currentPageIndex == item.pageIndex)

            if (isActive) {
                binding.tocItemContainer.setBackgroundColor(Color.parseColor("#DBEAFE"))
                binding.tocTitleText.setTextColor(Color.parseColor("#1D4ED8"))
                binding.tocTitleText.setTypeface(null, Typeface.BOLD)
                binding.tocPageText.setTextColor(Color.parseColor("#1D4ED8"))
            } else {
                binding.tocItemContainer.setBackgroundColor(Color.TRANSPARENT)
                binding.tocTitleText.setTextColor(
                    if (item.level == 0) Color.parseColor("#0F172A") else Color.parseColor("#334155")
                )
                binding.tocTitleText.setTypeface(
                    null,
                    if (item.level == 0) Typeface.BOLD else Typeface.NORMAL
                )
                binding.tocPageText.setTextColor(Color.parseColor("#64748B"))
            }

            binding.root.setOnClickListener {
                onTocClicked(item)
            }
        }
    }
}

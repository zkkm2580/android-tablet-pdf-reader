package com.example.tabletpdfreader.ui

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tabletpdfreader.databinding.ItemPdfPageBinding
import com.example.tabletpdfreader.pdf.PdfDocumentController
import com.example.tabletpdfreader.storage.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewPager2 页面适配器：支持左右/上下滑动翻页、高清渲染与护眼/夜间滤镜
 */
class PdfPageAdapter(
    private val scope: CoroutineScope,
    private val pdfController: PdfDocumentController,
    private val onPageTap: () -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    private var readingTheme: ReaderPreferences.ReadingTheme = ReaderPreferences.ReadingTheme.DAY

    fun updateTheme(theme: ReaderPreferences.ReadingTheme) {
        if (readingTheme != theme) {
            readingTheme = theme
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemPdfPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.clear()
    }

    override fun getItemCount(): Int = pdfController.pageCount

    inner class PageViewHolder(
        private val binding: ItemPdfPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null

        fun bind(pageIndex: Int) {
            renderJob?.cancel()
            binding.pageImageView.resetZoom()
            binding.pageImageView.setImageDrawable(null)
            binding.pageImageView.onSingleTapListener = onPageTap
            binding.pageLoadingProgress.visibility = View.VISIBLE
            binding.pageNumberCornerBadge.text = "${pageIndex + 1} / ${pdfController.pageCount}"

            applyThemeToPage()

            val screenWidth = binding.root.resources.displayMetrics.widthPixels
            val screenHeight = binding.root.resources.displayMetrics.heightPixels
            val targetRenderWidth = maxOf(screenWidth, screenHeight).coerceIn(1080, 2200)

            renderJob = scope.launch {
                val bitmap = pdfController.renderPage(pageIndex, targetRenderWidth)
                binding.pageLoadingProgress.visibility = View.GONE
                if (bitmap != null) {
                    binding.pageImageView.setImageBitmap(bitmap)
                }
            }
        }

        private fun applyThemeToPage() {
            when (readingTheme) {
                ReaderPreferences.ReadingTheme.DAY -> {
                    binding.pageContainerRoot.setBackgroundColor(Color.parseColor("#E2E8F0"))
                    binding.pageImageView.colorFilter = null
                }

                ReaderPreferences.ReadingTheme.SEPIA -> {
                    binding.pageContainerRoot.setBackgroundColor(Color.parseColor("#EAE0C8"))
                    // 温暖羊皮纸护眼色温滤镜
                    val sepiaMatrix = ColorMatrix(
                        floatArrayOf(
                            0.96f, 0f, 0f, 0f, 0f,
                            0f, 0.91f, 0f, 0f, 0f,
                            0f, 0f, 0.80f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    binding.pageImageView.colorFilter = ColorMatrixColorFilter(sepiaMatrix)
                }

                ReaderPreferences.ReadingTheme.NIGHT -> {
                    binding.pageContainerRoot.setBackgroundColor(Color.parseColor("#0F172A"))
                    // 夜间柔和暗色反相滤镜
                    val nightMatrix = ColorMatrix(
                        floatArrayOf(
                            -0.88f, 0f, 0f, 0f, 235f,
                            0f, -0.88f, 0f, 0f, 235f,
                            0f, 0f, -0.85f, 0f, 240f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    binding.pageImageView.colorFilter = ColorMatrixColorFilter(nightMatrix)
                }
            }
        }

        fun clear() {
            renderJob?.cancel()
            renderJob = null
            binding.pageImageView.setImageDrawable(null)
        }
    }
}

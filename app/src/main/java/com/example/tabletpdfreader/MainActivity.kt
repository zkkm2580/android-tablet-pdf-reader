package com.example.tabletpdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.example.tabletpdfreader.databinding.ActivityMainBinding
import com.example.tabletpdfreader.model.BookmarkItem
import com.example.tabletpdfreader.model.RecentBook
import com.example.tabletpdfreader.model.TocItem
import com.example.tabletpdfreader.pdf.PdfDocumentController
import com.example.tabletpdfreader.storage.ReaderPreferences
import com.example.tabletpdfreader.ui.BookmarkAdapter
import com.example.tabletpdfreader.ui.PdfPageAdapter
import com.example.tabletpdfreader.ui.TocAdapter
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ReaderPreferences
    private lateinit var pdfController: PdfDocumentController

    private lateinit var pageAdapter: PdfPageAdapter
    private lateinit var tocAdapter: TocAdapter
    private lateinit var bookmarkAdapter: BookmarkAdapter

    private var tocItems: List<TocItem> = emptyList()
    private var bookmarkItems: List<BookmarkItem> = emptyList()
    private var currentPageIndex: Int = 0
    private var currentUriString: String = ""
    private var activeSideTab: Int = TAB_TOC
    private var isChromeVisible: Boolean = true

    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            loadPdfFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = ReaderPreferences(this)
        pdfController = PdfDocumentController(this)

        setupViewPager()
        setupSidePanel()
        setupTopAndBottomBars()
        applySwipeDirectionUI(prefs.swipeDirection)
        applyReadingThemeUI(prefs.readingTheme)

        // 如果从系统文件管理器直接打开了 PDF，则优先加载外部 Intent 的 PDF；否则默认加载内置多章节示例文档
        val incomingUri = intent?.data
        if (incomingUri != null) {
            loadPdfFromUri(incomingUri)
        } else {
            loadSamplePdf()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            loadPdfFromUri(uri)
        }
    }

    private fun setupViewPager() {
        pageAdapter = PdfPageAdapter(
            scope = lifecycleScope,
            pdfController = pdfController,
            onPageTap = { toggleImmersiveChrome() }
        )
        binding.pdfViewPager.adapter = pageAdapter
        binding.pdfViewPager.offscreenPageLimit = 1

        binding.pdfViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPageIndex = position
                onCurrentPageChanged(position)
            }
        })
    }

    private fun setupSidePanel() {
        tocAdapter = TocAdapter { item ->
            jumpToPage(item.pageIndex, smooth = false)
            // 在较小尺寸竖屏下点击目录后可自动收起侧栏，大屏横屏保持常驻
            if (resources.configuration.screenWidthDp < 720) {
                setSidePanelVisible(false)
            }
        }

        bookmarkAdapter = BookmarkAdapter(
            onBookmarkClick = { bookmark ->
                jumpToPage(bookmark.pageIndex, smooth = false)
                if (resources.configuration.screenWidthDp < 720) {
                    setSidePanelVisible(false)
                }
            },
            onBookmarkDelete = { bookmark ->
                if (pdfController.currentDocKey.isNotEmpty()) {
                    bookmarkItems = prefs.removeBookmark(pdfController.currentDocKey, bookmark.id)
                    refreshBookmarkListUI()
                    updateBookmarkStarButton()
                    Toast.makeText(this, "已删除第 ${bookmark.pageIndex + 1} 页书签", Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.tocRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.tocRecyclerView.adapter = tocAdapter

        binding.bookmarkRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.bookmarkRecyclerView.adapter = bookmarkAdapter

        binding.sideTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                activeSideTab = tab?.position ?: TAB_TOC
                updateSidePanelTabVisibility()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.searchSideEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                tocAdapter.filter(q)
                bookmarkAdapter.filter(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnAddBookmarkInPanel.setOnClickListener {
            showAddOrEditBookmarkDialog()
        }

        binding.btnCloseSidePanel.setOnClickListener {
            setSidePanelVisible(false)
        }
    }

    private fun setupTopAndBottomBars() {
        // 展开/收起左侧【目录 & 书签】分栏
        binding.btnToggleSidePanel.setOnClickListener {
            val currentlyVisible = binding.sidePanelContainer.visibility == View.VISIBLE
            setSidePanelVisible(!currentlyVisible)
        }

        // 打开本地 PDF 文件
        binding.btnOpenPdf.setOnClickListener {
            openPdfLauncher.launch(arrayOf("application/pdf"))
        }

        // 最近阅读与内置示例入口
        binding.btnRecentOrSample.setOnClickListener {
            showRecentBooksDialog()
        }

        // 一键切换【左右划动翻页】与【上下划动翻页】
        binding.btnToggleSwipeMode.setOnClickListener {
            val nextDirection = if (prefs.swipeDirection == ReaderPreferences.SwipeDirection.HORIZONTAL) {
                ReaderPreferences.SwipeDirection.VERTICAL
            } else {
                ReaderPreferences.SwipeDirection.HORIZONTAL
            }
            prefs.swipeDirection = nextDirection
            applySwipeDirectionUI(nextDirection)
            val modeName = if (nextDirection == ReaderPreferences.SwipeDirection.HORIZONTAL) {
                "左右划动翻页模式"
            } else {
                "上下划动翻页模式"
            }
            Toast.makeText(this, "已切换为：$modeName", Toast.LENGTH_SHORT).show()
        }

        // 添加/编辑当前页书签
        binding.btnBookmarkCurrentPage.setOnClickListener {
            showAddOrEditBookmarkDialog()
        }

        // 切换阅读护眼主题（日间 / 护眼羊皮纸 / 夜间深色）
        binding.btnToggleTheme.setOnClickListener {
            val nextTheme = when (prefs.readingTheme) {
                ReaderPreferences.ReadingTheme.DAY -> ReaderPreferences.ReadingTheme.SEPIA
                ReaderPreferences.ReadingTheme.SEPIA -> ReaderPreferences.ReadingTheme.NIGHT
                ReaderPreferences.ReadingTheme.NIGHT -> ReaderPreferences.ReadingTheme.DAY
            }
            prefs.readingTheme = nextTheme
            applyReadingThemeUI(nextTheme)
        }

        // 上一页 / 下一页按钮
        binding.btnPrevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                jumpToPage(currentPageIndex - 1, smooth = true)
            }
        }

        binding.btnNextPage.setOnClickListener {
            if (currentPageIndex < pdfController.pageCount - 1) {
                jumpToPage(currentPageIndex + 1, smooth = true)
            }
        }

        // 点击页码弹出快速跳页对话框
        binding.pageIndicatorButton.setOnClickListener {
            showJumpToPageDialog()
        }

        // 底部页码进度条拖动
        binding.pageSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && pdfController.pageCount > 0) {
                    binding.pageIndicatorButton.text = "${progress + 1} / ${pdfController.pageCount}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val target = seekBar?.progress ?: return
                jumpToPage(target, smooth = false)
            }
        })
    }

    private fun loadPdfFromUri(uri: Uri) {
        binding.loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = pdfController.openUri(uri)
            binding.loadingOverlay.visibility = View.GONE
            result.onSuccess { extractedToc ->
                currentUriString = uri.toString()
                onDocumentReady(extractedToc)
            }.onFailure { err ->
                Toast.makeText(
                    this@MainActivity,
                    "打开 PDF 失败：${err.localizedMessage ?: "文件不可读"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadSamplePdf() {
        binding.loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = pdfController.openBuiltInSamplePdf()
            binding.loadingOverlay.visibility = View.GONE
            result.onSuccess { extractedToc ->
                currentUriString = "sample://built_in_guide"
                onDocumentReady(extractedToc)
            }.onFailure { err ->
                Toast.makeText(
                    this@MainActivity,
                    "初始化示例 PDF 失败：${err.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onDocumentReady(extractedToc: List<TocItem>) {
        val docKey = pdfController.currentDocKey
        val total = pdfController.pageCount
        binding.docTitleText.text = pdfController.currentFileName

        tocItems = extractedToc
        bookmarkItems = prefs.getBookmarks(docKey)

        // 如果首次打开内置示例且尚无书签，预置两条示例书签方便用户直观体验
        if (docKey == "built_in_sample_guide_v1" && bookmarkItems.isEmpty()) {
            prefs.addOrUpdateBookmark(
                docKey = docKey,
                pageIndex = 0,
                title = "第一章：功能总览",
                note = "支持左右/上下滑动翻页、目录解析与书签批注"
            )
            bookmarkItems = prefs.addOrUpdateBookmark(
                docKey = docKey,
                pageIndex = 3,
                title = "重点：书签与批注说明",
                note = "点击右上角 ★ 按钮即可随时添加或修改书签"
            )
        }

        val savedPage = prefs.getLastPage(docKey).coerceIn(0, (total - 1).coerceAtLeast(0))
        currentPageIndex = savedPage

        pageAdapter.notifyDataSetChanged()
        binding.pageSeekBar.max = (total - 1).coerceAtLeast(0)
        binding.pdfViewPager.setCurrentItem(savedPage, false)

        tocAdapter.submitList(tocItems, savedPage)
        refreshBookmarkListUI()
        onCurrentPageChanged(savedPage)
    }

    private fun onCurrentPageChanged(pageIndex: Int) {
        val total = pdfController.pageCount
        if (total <= 0) return

        val docKey = pdfController.currentDocKey
        prefs.setLastPage(docKey, pageIndex)

        if (currentUriString.isNotEmpty()) {
            prefs.recordRecentBook(
                RecentBook(
                    docKey = docKey,
                    uriString = currentUriString,
                    fileName = pdfController.currentFileName,
                    lastPage = pageIndex,
                    totalPages = total,
                    lastOpened = System.currentTimeMillis()
                )
            )
        }

        binding.pageSeekBar.progress = pageIndex
        binding.pageIndicatorButton.text = "第 ${pageIndex + 1} / $total 页"
        binding.btnPrevPage.isEnabled = pageIndex > 0
        binding.btnNextPage.isEnabled = pageIndex < total - 1

        // 查找当前页对应的章节标题
        val currentChapter = tocItems.lastOrNull { !it.isGenerated && it.pageIndex <= pageIndex }?.title
            ?: "第 ${pageIndex + 1} 页"
        binding.docSubtitleText.text = currentChapter

        tocAdapter.updateCurrentPage(pageIndex)
        updateBookmarkStarButton()
    }

    private fun updateBookmarkStarButton() {
        val docKey = pdfController.currentDocKey
        val bookmarked = docKey.isNotEmpty() && prefs.isPageBookmarked(docKey, currentPageIndex)
        if (bookmarked) {
            binding.btnBookmarkCurrentPage.text = "★ 已加书签"
            binding.btnBookmarkCurrentPage.isSelected = true
        } else {
            binding.btnBookmarkCurrentPage.text = "☆ 添加书签"
            binding.btnBookmarkCurrentPage.isSelected = false
        }
    }

    private fun refreshBookmarkListUI() {
        bookmarkAdapter.submitList(bookmarkItems)
        binding.bookmarkEmptyText.visibility = if (bookmarkItems.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.sideTabLayout.getTabAt(TAB_TOC)?.text = "目录 (${tocItems.size})"
        binding.sideTabLayout.getTabAt(TAB_BOOKMARKS)?.text = "书签 (${bookmarkItems.size})"
    }

    private fun updateSidePanelTabVisibility() {
        if (activeSideTab == TAB_TOC) {
            binding.tocPanelContent.visibility = View.VISIBLE
            binding.bookmarkPanelContent.visibility = View.GONE
        } else {
            binding.tocPanelContent.visibility = View.GONE
            binding.bookmarkPanelContent.visibility = View.VISIBLE
        }
    }

    private fun setSidePanelVisible(visible: Boolean) {
        binding.sidePanelContainer.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnToggleSidePanel.text = if (visible) "收起目录" else "目录/书签"
    }

    private fun applySwipeDirectionUI(direction: ReaderPreferences.SwipeDirection) {
        val targetPage = currentPageIndex
        if (direction == ReaderPreferences.SwipeDirection.HORIZONTAL) {
            binding.pdfViewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
            binding.btnToggleSwipeMode.text = "⇄ 左右翻页"
        } else {
            binding.pdfViewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
            binding.btnToggleSwipeMode.text = "⇅ 上下翻页"
        }
        if (pdfController.pageCount > 0) {
            binding.pdfViewPager.post {
                binding.pdfViewPager.setCurrentItem(targetPage, false)
            }
        }
    }

    private fun applyReadingThemeUI(theme: ReaderPreferences.ReadingTheme) {
        pageAdapter.updateTheme(theme)
        binding.btnToggleTheme.text = when (theme) {
            ReaderPreferences.ReadingTheme.DAY -> "☀ 日间"
            ReaderPreferences.ReadingTheme.SEPIA -> "📜 护眼"
            ReaderPreferences.ReadingTheme.NIGHT -> "🌙 夜间"
        }
    }

    private fun toggleImmersiveChrome() {
        isChromeVisible = !isChromeVisible
        binding.bottomControlBar.visibility = if (isChromeVisible) View.VISIBLE else View.GONE
    }

    private fun jumpToPage(pageIndex: Int, smooth: Boolean) {
        if (pdfController.pageCount <= 0) return
        val safePage = pageIndex.coerceIn(0, pdfController.pageCount - 1)
        binding.pdfViewPager.setCurrentItem(safePage, smooth)
    }

    private fun showAddOrEditBookmarkDialog() {
        val docKey = pdfController.currentDocKey
        if (docKey.isEmpty() || pdfController.pageCount <= 0) return

        val existing = bookmarkItems.firstOrNull { it.pageIndex == currentPageIndex }
        val defaultTitle = existing?.title
            ?: tocItems.lastOrNull { !it.isGenerated && it.pageIndex <= currentPageIndex }?.title?.let {
                "$it (P.${currentPageIndex + 1})"
            }
            ?: "第 ${currentPageIndex + 1} 页重点"

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, (4 * density).toInt())
        }

        val titleInput = EditText(this).apply {
            hint = "书签标题（如：核心公式推导）"
            setText(defaultTitle)
            setSelection(text.length)
        }

        val noteInput = EditText(this).apply {
            hint = "选填：添加批注或阅读笔记..."
            setText(existing?.note.orEmpty())
            minLines = 2
        }

        container.addView(titleInput)
        container.addView(noteInput)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing != null) "编辑第 ${currentPageIndex + 1} 页书签" else "添加第 ${currentPageIndex + 1} 页书签")
            .setView(container)
            .setPositiveButton("保存书签") { _, _ ->
                bookmarkItems = prefs.addOrUpdateBookmark(
                    docKey = docKey,
                    pageIndex = currentPageIndex,
                    title = titleInput.text?.toString()?.trim().orEmpty(),
                    note = noteInput.text?.toString()?.trim().orEmpty()
                )
                refreshBookmarkListUI()
                updateBookmarkStarButton()
                Toast.makeText(this, "已保存第 ${currentPageIndex + 1} 页书签", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)

        if (existing != null) {
            builder.setNeutralButton("删除该书签") { _, _ ->
                bookmarkItems = prefs.removeBookmarkAtPage(docKey, currentPageIndex)
                refreshBookmarkListUI()
                updateBookmarkStarButton()
                Toast.makeText(this, "已移除第 ${currentPageIndex + 1} 页书签", Toast.LENGTH_SHORT).show()
            }
        }

        builder.show()
    }

    private fun showJumpToPageDialog() {
        val total = pdfController.pageCount
        if (total <= 0) return

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "输入页码 (1 - $total)"
            setText((currentPageIndex + 1).toString())
            setSelection(text.length)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("快速跳转页码")
            .setView(wrapper)
            .setPositiveButton("跳转") { _, _ ->
                val pageNum = input.text?.toString()?.toIntOrNull()
                if (pageNum != null && pageNum in 1..total) {
                    jumpToPage(pageNum - 1, smooth = false)
                } else {
                    Toast.makeText(this, "请输入 1 到 $total 之间的有效页码", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRecentBooksDialog() {
        val recent = prefs.getRecentBooks()
        val items = mutableListOf("📘 打开内置《平板PDF阅读器使用指南》示例文档")
        val ExternalBooks = recent.filter { it.uriString != "sample://built_in_guide" }
        ExternalBooks.forEach { book ->
            items.add("📄 ${book.fileName}（读至第 ${book.lastPage + 1}/${book.totalPages} 页）")
        }

        AlertDialog.Builder(this)
            .setTitle("最近阅读与示例文档")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    loadSamplePdf()
                } else {
                    val target = ExternalBooks[which - 1]
                    loadPdfFromUri(Uri.parse(target.uriString))
                }
            }
            .setPositiveButton("浏览本地文件...") { _, _ ->
                openPdfLauncher.launch(arrayOf("application/pdf"))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfController.close()
    }

    companion object {
        private const val TAB_TOC = 0
        private const val TAB_BOOKMARKS = 1
    }
}

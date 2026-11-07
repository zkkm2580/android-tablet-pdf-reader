package com.example.tabletpdfreader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import com.example.tabletpdfreader.model.TocItem
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 负责 PDF 文件加载、页面高清渲染、缓存管理以及目录（Outline / TOC）解析
 */
class PdfDocumentController(private val context: Context) {

    private val renderMutex = Mutex()
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentLocalFile: File? = null

    var currentDocKey: String = ""
        private set
    var currentFileName: String = ""
        private set
    var pageCount: Int = 0
        private set

    // 使用可用内存的 1/6 作为页面 Bitmap 缓存，适配安卓平板大屏切换
    private val bitmapCache: LruCache<String, Bitmap> by lazy {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = maxMemoryKb / 6
        object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount / 1024
            }
        }
    }

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /**
     * 打开外部 URI 指向的 PDF 文档
     */
    suspend fun openUri(uri: Uri): Result<List<TocItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = resolveDisplayName(uri)
            val cacheFile = File(context.cacheDir, "active_reading_doc.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalArgumentException("无法读取所选 PDF 文件")

            val docKey = sha256Short("$uri|$displayName|${cacheFile.length()}")
            openFromLocalFile(cacheFile, displayName, docKey)
        }
    }

    /**
     * 生成并打开内置的多章节示例 PDF（含真实内嵌 PDF 目录树 Outline），方便平板安装后直接体验所有功能
     */
    suspend fun openBuiltInSamplePdf(): Result<List<TocItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val sampleFile = File(context.filesDir, "tablet_reader_guide_sample.pdf")
            generateSampleGuidePdf(sampleFile)
            openFromLocalFile(
                file = sampleFile,
                displayName = "平板PDF阅读器使用指南（示例文档）.pdf",
                docKey = "built_in_sample_guide_v1"
            )
        }
    }

    private suspend fun openFromLocalFile(
        file: File,
        displayName: String,
        docKey: String
    ): List<TocItem> {
        renderMutex.withLock {
            closeInternal()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            fileDescriptor = pfd
            pdfRenderer = renderer
            currentLocalFile = file
            currentFileName = displayName
            currentDocKey = docKey
            pageCount = renderer.pageCount
        }
        return extractTableOfContents(file, pageCount)
    }

    /**
     * 渲染指定页码的高清 Bitmap，支持平板高分屏自适应缩放
     */
    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null
        val safeWidth = targetWidthPx.coerceIn(720, 2560)
        val cacheKey = "$currentDocKey:$pageIndex:$safeWidth"

        bitmapCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        renderMutex.withLock {
            val renderer = pdfRenderer ?: return@withLock null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withLock null

            renderer.openPage(pageIndex).use { page ->
                val aspectRatio = if (page.width > 0) {
                    page.height.toFloat() / page.width.toFloat()
                } else {
                    1.414f
                }
                val bmpWidth = safeWidth
                val bmpHeight = (bmpWidth * aspectRatio).toInt().coerceIn(400, 3600)

                val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmapCache.put(cacheKey, bitmap)
                bitmap
            }
        }
    }

    /**
     * 使用 PDFBox-Android 提取 PDF 内置目录（Document Outline），若无内置目录则生成快速页码索引
     */
    private fun extractTableOfContents(file: File, totalPages: Int): List<TocItem> {
        val extracted = mutableListOf<TocItem>()
        try {
            PDDocument.load(file).use { document ->
                val outline: PDDocumentOutline? = document.documentCatalog?.documentOutline
                if (outline != null) {
                    collectOutlineItems(document, outline, 0, extracted)
                }
            }
        } catch (_: Exception) {
            // 如果 PDF 加密或目录结构特殊，降级为页码目录
        }

        if (extracted.isNotEmpty()) {
            return extracted
        }

        // 若该 PDF 未内嵌章节目录，自动生成页码快速目录
        val fallback = mutableListOf<TocItem>()
        for (i in 0 until totalPages) {
            fallback.add(
                TocItem(
                    title = "第 ${i + 1} 页",
                    pageIndex = i,
                    level = 0,
                    isGenerated = true
                )
            )
        }
        return fallback
    }

    private fun collectOutlineItems(
        document: PDDocument,
        parentNode: PDOutlineNode,
        level: Int,
        outList: MutableList<TocItem>
    ) {
        var current: PDOutlineItem? = parentNode.firstChild
        while (current != null) {
            val title = current.title?.trim()?.takeIf { it.isNotEmpty() } ?: "未命名章节"
            val pageIndex = resolveOutlinePageIndex(document, current)
            if (pageIndex >= 0) {
                outList.add(
                    TocItem(
                        title = title,
                        pageIndex = pageIndex,
                        level = level.coerceIn(0, 4),
                        isGenerated = false
                    )
                )
            }
            if (current.hasChildren()) {
                collectOutlineItems(document, current, level + 1, outList)
            }
            current = current.nextSibling
        }
    }

    private fun resolveOutlinePageIndex(document: PDDocument, item: PDOutlineItem): Int {
        return try {
            val destPage = item.findDestinationPage(document)
            if (destPage != null) {
                val idx = document.pages.indexOf(destPage)
                if (idx >= 0) return idx
            }
            val dest = item.destination
            if (dest is PDPageDestination) {
                val num = dest.retrievePageNumber()
                if (num >= 0) return num
            }
            val action = item.action
            if (action is PDActionGoTo) {
                val actionDest = action.destination
                if (actionDest is PDPageDestination) {
                    val num = actionDest.retrievePageNumber()
                    if (num >= 0) return num
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 生成一份 6 页的高清示例 PDF 并写入多级章节目录（Outline）
     */
    private fun generateSampleGuidePdf(destFile: File) {
        val rawPdfFile = File(context.cacheDir, "raw_sample_pages.pdf")
        val pdfDoc = PdfDocument()

        data class SamplePageSpec(
            val chapterTag: String,
            val title: String,
            val subtitle: String,
            val bullets: List<String>,
            val accentColor: Int
        )

        val pages = listOf(
            SamplePageSpec(
                chapterTag = "第一章 · 概览",
                title = "安卓平板 PDF 阅读器",
                subtitle = "专为大屏安卓平板打造的沉浸式阅读与批注导航工具",
                bullets = listOf(
                    "1. 支持【左右滑动】与【上下滑动】两种翻页方式，顶部工具栏一键切换。",
                    "2. 平板大屏自适应侧边栏：左侧常驻或折叠显示【目录】与【书签】面板。",
                    "3. 支持双指捏合无级缩放（1.0x ~ 4.0x）以及双击快速放大或复位。",
                    "4. 内置三种平板护眼配色：标准日间、暖色羊皮纸、深色夜间模式。"
                ),
                accentColor = Color.parseColor("#1E5EFF")
            ),
            SamplePageSpec(
                chapterTag = "1.1 · 翻页与手势控制",
                title = "左右滑动与上下滑动翻页",
                subtitle = "自由切换横向书本翻页或纵向连续卷轴阅读",
                bullets = listOf(
                    "• 横向模式（左右划动）：适合幻灯片、教材、电子书整页翻阅。",
                    "• 纵向模式（上下划动）：适合论文、长篇报告、技术手册上下连贯阅读。",
                    "• 底部进度滑块：拖拽滑块即可在几百页的 PDF 中毫秒级快速定位。",
                    "• 智能手势防冲突：页面处于放大状态时可自由平移查看细节；复位后划动即翻页。"
                ),
                accentColor = Color.parseColor("#00796B")
            ),
            SamplePageSpec(
                chapterTag = "第二章 · 目录导航",
                title = "多级目录（TOC）自动解析",
                subtitle = "自动读取 PDF 内嵌章节树，支持层级缩进与搜索过滤",
                bullets = listOf(
                    "• 自动解析 PDF 标准 Outline 章节结构，清晰展示章、节、小节层级。",
                    "• 当前阅读页所属章节在侧边目录栏中自动高亮提示。",
                    "• 顶部搜索框支持按关键字实时筛选章节名称或页码。",
                    "• 对于未内嵌目录的扫描版 PDF，自动生成页码快速索引列表。"
                ),
                accentColor = Color.parseColor("#5E35B1")
            ),
            SamplePageSpec(
                chapterTag = "2.1 · 书签批注管理",
                title = "个性化书签与阅读笔记",
                subtitle = "随时标记重点页面并添加阅读批注，数据自动持久化保存",
                bullets = listOf(
                    "• 点击顶部工具栏【★ 书签】按钮，即可为当前页添加自定义标题与备注。",
                    "• 每份 PDF 文档拥有独立的书签库与上次阅读进度记忆。",
                    "• 在侧边栏切换到【书签】标签页，点击任意书签即可瞬间跳转至对应页码。",
                    "• 支持搜索书签标题/备注内容，或随时修改与删除已有书签。"
                ),
                accentColor = Color.parseColor("#E65100")
            ),
            SamplePageSpec(
                chapterTag = "第三章 · 护眼与大屏适配",
                title = "平板分栏与多主题护眼模式",
                subtitle = "针对 8~14 英寸安卓平板横竖屏深度优化",
                bullets = listOf(
                    "• 横屏下可开启左侧目录/书签分栏对照阅读，也可收起侧栏全屏阅读。",
                    "• 护眼羊皮纸模式：柔和暖黄色温，大幅降低长时间阅读 PDF 文献的视觉疲劳。",
                    "• 夜间暗色模式：智能反相页面对比度，适合暗光环境下阅读。",
                    "• 支持从系统文件管理器直接选择打开任意本地或云盘 PDF 文档。"
                ),
                accentColor = Color.parseColor("#2E7D32")
            ),
            SamplePageSpec(
                chapterTag = "附录 · 快捷操作速查",
                title = "常用操作速查表",
                subtitle = "现在就试试滑动屏幕、点击目录或添加您的第一个书签吧！",
                bullets = listOf(
                    "• 【打开文件】：点击左上角文件夹按钮选择平板中的任意 .pdf 文件。",
                    "• 【目录/书签】：点击左上角侧栏按钮展开或收起目录与书签抽屉。",
                    "• 【切换翻页方向】：点击顶部「左右翻页 / 上下翻页」按钮实时切换。",
                    "• 【双指缩放 / 双击放大】：在页面任意位置双击可 2.2 倍放大，再次双击复位。"
                ),
                accentColor = Color.parseColor("#AD1457")
            )
        )

        val pageWidth = 1200
        val pageHeight = 1600

        pages.forEachIndexed { index, spec ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
            val page = pdfDoc.startPage(pageInfo)
            drawSamplePageCanvas(page.canvas, pageWidth, pageHeight, index + 1, pages.size, spec.chapterTag, spec.title, spec.subtitle, spec.bullets, spec.accentColor)
            pdfDoc.finishPage(page)
        }

        FileOutputStream(rawPdfFile).use { out ->
            pdfDoc.writeTo(out)
        }
        pdfDoc.close()

        // 使用 PDFBox 为生成的示例 PDF 注入真实的多级目录树（Outline）
        PDDocument.load(rawPdfFile).use { pdDoc ->
            val outline = PDDocumentOutline()
            pdDoc.documentCatalog.documentOutline = outline

            fun makeItem(title: String, pageIdx: Int): PDOutlineItem {
                return PDOutlineItem().apply {
                    this.title = title
                    this.destination = pdDoc.getPage(pageIdx)
                }
            }

            val ch1 = makeItem("第一章 平板阅读器概览", 0)
            val ch1Sub1 = makeItem("1.1 左右与上下滑动翻页控制", 1)
            ch1.addLast(ch1Sub1)

            val ch2 = makeItem("第二章 目录与书签导航系统", 2)
            val ch2Sub1 = makeItem("2.1 个性化书签与阅读备注", 3)
            ch2.addLast(ch2Sub1)

            val ch3 = makeItem("第三章 平板分栏与护眼主题", 4)
            val appendix = makeItem("附录 常用快捷操作速查表", 5)

            outline.addLast(ch1)
            outline.addLast(ch2)
            outline.addLast(ch3)
            outline.addLast(appendix)
            outline.openNode()

            pdDoc.save(destFile)
        }
        rawPdfFile.delete()
    }

    private fun drawSamplePageCanvas(
        canvas: Canvas,
        width: Int,
        height: Int,
        pageNumber: Int,
        totalPages: Int,
        chapterTag: String,
        title: String,
        subtitle: String,
        bullets: List<String>,
        accentColor: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 背景色
        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // 顶部装饰色带
        paint.color = accentColor
        canvas.drawRect(0f, 0f, width.toFloat(), 26f, paint)

        // 外边框卡片
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.parseColor("#E2E8F0")
        canvas.drawRoundRect(RectF(64f, 72f, width - 64f, height - 72f), 24f, 24f, paint)

        // 章节胶囊标签
        paint.style = Paint.Style.FILL
        paint.color = accentColor
        canvas.drawRoundRect(RectF(110f, 125f, 440f, 185f), 30f, 30f, paint)

        paint.color = Color.WHITE
        paint.textSize = 28f
        paint.isFakeBoldText = true
        canvas.drawText(chapterTag, 140f, 165f, paint)

        // 主标题
        paint.color = Color.parseColor("#0F172A")
        paint.textSize = 56f
        paint.isFakeBoldText = true
        canvas.drawText(title, 110f, 275f, paint)

        // 副标题
        paint.color = Color.parseColor("#475569")
        paint.textSize = 31f
        paint.isFakeBoldText = false
        canvas.drawText(subtitle, 110f, 338f, paint)

        // 分割线
        paint.color = Color.parseColor("#CBD5E1")
        paint.strokeWidth = 2f
        canvas.drawLine(110f, 380f, width - 110f, 380f, paint)

        // 正文要点卡片
        var currentY = 450f
        for (bullet in bullets) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F8FAFC")
            canvas.drawRoundRect(RectF(110f, currentY, width - 110f, currentY + 175f), 20f, 20f, paint)

            paint.color = accentColor
            canvas.drawRoundRect(RectF(110f, currentY, 124f, currentY + 175f), 8f, 8f, paint)

            paint.color = Color.parseColor("#1E293B")
            paint.textSize = 30f
            paint.isFakeBoldText = false

            // 简单自动换行绘制
            val maxCharsPerLine = 30
            if (bullet.length <= maxCharsPerLine) {
                canvas.drawText(bullet, 155f, currentY + 98f, paint)
            } else {
                val line1 = bullet.substring(0, maxCharsPerLine)
                val line2 = bullet.substring(maxCharsPerLine)
                canvas.drawText(line1, 155f, currentY + 75f, paint)
                canvas.drawText(line2, 155f, currentY + 125f, paint)
            }
            currentY += 210f
        }

        // 底部操作提示框
        paint.color = Color.parseColor("#EFF6FF")
        canvas.drawRoundRect(RectF(110f, height - 245f, width - 110f, height - 140f), 18f, 18f, paint)
        paint.color = Color.parseColor("#1D4ED8")
        paint.textSize = 27f
        paint.isFakeBoldText = true
        canvas.drawText("提示：您现在可以尝试左右或上下划动翻页，或点击上方【目录 / 书签】按钮体验导航功能。", 140f, height - 182f, paint)

        // 页脚页码
        paint.color = Color.parseColor("#64748B")
        paint.textSize = 26f
        paint.isFakeBoldText = false
        val footerText = "Tablet PDF Reader · 第 $pageNumber / $totalPages 页"
        val textWidth = paint.measureText(footerText)
        canvas.drawText(footerText, (width - textWidth) / 2f, height - 95f, paint)
    }

    private fun resolveDisplayName(uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx)
                    }
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文档.pdf"
        }
        return name ?: "未命名文档.pdf"
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        bitmapCache.evictAll()
        try {
            pdfRenderer?.close()
        } catch (_: Exception) {
        }
        try {
            fileDescriptor?.close()
        } catch (_: Exception) {
        }
        pdfRenderer = null
        fileDescriptor = null
        pageCount = 0
    }
}

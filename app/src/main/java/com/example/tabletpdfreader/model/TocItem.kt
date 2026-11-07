package com.example.tabletpdfreader.model

/**
 * PDF 目录条目（Table of Contents / Outline）
 * @param title 章节标题
 * @param pageIndex 对应页码（从 0 开始）
 * @param level 目录层级深度（0 为顶级章节，1 为子章节，依此类推）
 * @param isGenerated 是否为无内置目录时自动生成的页码索引
 */
data class TocItem(
    val title: String,
    val pageIndex: Int,
    val level: Int = 0,
    val isGenerated: Boolean = false
)

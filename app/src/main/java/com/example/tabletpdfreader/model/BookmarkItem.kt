package com.example.tabletpdfreader.model

/**
 * 用户书签条目
 * @param id 唯一标识
 * @param pageIndex 页码（从 0 开始）
 * @param title 书签名称
 * @param note 书签备注/摘录
 * @param timestamp 创建或更新时间戳
 */
data class BookmarkItem(
    val id: String,
    val pageIndex: Int,
    val title: String,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

package com.example.tabletpdfreader.model

/**
 * 最近阅读记录条目
 */
data class RecentBook(
    val docKey: String,
    val uriString: String,
    val fileName: String,
    val lastPage: Int,
    val totalPages: Int,
    val lastOpened: Long
)

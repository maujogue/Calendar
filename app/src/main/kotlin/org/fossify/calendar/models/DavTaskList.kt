package org.fossify.calendar.models

data class DavTaskList(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val accessLevel: Int,
)

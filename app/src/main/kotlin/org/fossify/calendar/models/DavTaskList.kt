package org.fossify.calendar.models

import org.fossify.calendar.providers.TasksContract.TaskLists

data class DavTaskList(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val accessLevel: Int,
) {
    fun canWrite() = accessLevel == TaskLists.ACCESS_LEVEL_UNDEFINED || accessLevel > TaskLists.ACCESS_LEVEL_READ
}

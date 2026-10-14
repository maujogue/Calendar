package org.fossify.calendar.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.fossify.calendar.helpers.OTHER_EVENT

@Entity(tableName = "event_types", indices = [(Index(value = ["id"], unique = true))])
data class CalendarEntity(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "title") var title: String,
    @ColumnInfo(name = "color") var color: Int,
    @ColumnInfo(name = "caldav_calendar_id") var caldavCalendarId: Int = 0,
    @ColumnInfo(name = "caldav_display_name") var caldavDisplayName: String = "",
    @ColumnInfo(name = "caldav_email") var caldavEmail: String = "",
    @ColumnInfo(name = "type") var type: Int = OTHER_EVENT,
    @ColumnInfo(name = "dav_task_list_id") var davTaskListId: Long = 0L
) {
    fun getDisplayTitle() = when {
        isDavTaskList() -> "$title ($caldavEmail)"
        caldavCalendarId == 0 -> title
        else -> "$caldavDisplayName ($caldavEmail)"
    }

    fun isSyncedCalendar() = caldavCalendarId != 0

    // a CalDAV task list synced through the app's own tasks provider, it holds tasks only
    fun isDavTaskList() = davTaskListId != 0L
}

package org.fossify.calendar.helpers

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import org.fossify.calendar.extensions.calendarsDB
import org.fossify.calendar.extensions.completedTasksDB
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsDB
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.extensions.toLocalAllDayEvent
import org.fossify.calendar.extensions.updateTaskCompletion
import org.fossify.calendar.extensions.updateWidgets
import org.fossify.calendar.models.CalendarEntity
import org.fossify.calendar.models.DavTaskList
import org.fossify.calendar.models.Event
import org.fossify.calendar.models.Reminder
import org.fossify.calendar.providers.TasksContract
import org.fossify.calendar.providers.TasksDatabaseHelper
import org.fossify.calendar.providers.TasksContract.Alarm
import org.fossify.calendar.providers.TasksContract.Properties
import org.fossify.calendar.providers.TasksContract.TaskLists
import org.fossify.calendar.providers.TasksContract.Tasks
import org.fossify.commons.extensions.getProperPrimaryColor
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import java.util.UUID

/**
 * Mirrors CalDAV tasks between the app's own OpenTasks compatible [org.fossify.calendar.providers.TasksProvider]
 * (synced with the server by DAVx⁵) and the app database, the same way [CalDAVHelper] does for events.
 *
 * Tasks in the app database have source "DavTasks-<list id>" and import id "DavTasks-<list id>-<task id>".
 * Tasks without a due date or a start date cannot be shown in a calendar and are not mirrored.
 */
class DavTasksHelper(val context: Context) {
    companion object {
        private const val PULL_DELAY_MS = 1000L
        private const val EXDATE_PATTERN = "yyyyMMdd'T'HHmmss"
        private const val EXDATE_UTC_PATTERN = "yyyyMMdd'T'HHmmss'Z'"
        private const val DAY_CODE_LENGTH = 8
        private const val MINUTE_MS = 60_000L
        private const val DAY_SECONDS = 86_400L
        private const val PERCENT_COMPLETED = 100

        // columns that belong to the remote copy of a task, they are not kept when a task moves to another list
        private val NOT_MOVED_TASK_COLUMNS = setOf(
            TasksContract._ID,
            Tasks.LIST_ID,
            TasksContract._SYNC_ID,
            TasksContract.SYNC_VERSION,
            TasksContract._DIRTY,
            TasksContract._DELETED,
            Tasks.VERSION,
            Tasks.PARENT_ID,
            Tasks.ORIGINAL_INSTANCE_SYNC_ID,
            Tasks.ORIGINAL_INSTANCE_ID,
        ) + TasksContract.SYNC_COLUMNS

        private val pullLock = Any()
        private val scheduleLock = Any()
        private val pullHandler by lazy {
            Handler(HandlerThread("DavTasksPull").apply { start() }.looper)
        }
        private var pullRunnable: Runnable? = null

        /** Mirrors the provider into the app database shortly after a sync adapter is done writing to it. */
        fun schedulePull(context: Context) {
            val appContext = context.applicationContext
            synchronized(scheduleLock) {
                pullRunnable?.let { pullHandler.removeCallbacks(it) }
                pullRunnable = Runnable { DavTasksHelper(appContext).refreshTaskLists() }.also {
                    pullHandler.postDelayed(it, PULL_DELAY_MS)
                }
            }
        }

        fun getSource(taskListId: Long) = "$SOURCE_DAV_TASKS-$taskListId"

        fun getImportId(taskListId: Long, taskId: Long) = "$SOURCE_DAV_TASKS-$taskListId-$taskId"
    }

    private class RemoteTimes(val dtstart: Long?, val due: Long?, val isAllDay: Boolean)

    private class RemoteAlarm(val minutesBefore: Int, val reference: Int, val type: Int)

    fun getTaskLists(ids: Collection<Long>? = null): List<DavTaskList> {
        if (ids != null && ids.isEmpty()) {
            return emptyList()
        }

        val lists = ArrayList<DavTaskList>()
        val projection = arrayOf(
            TasksContract._ID,
            TaskLists.LIST_NAME,
            TasksContract.ACCOUNT_NAME,
            TasksContract.ACCOUNT_TYPE,
            TaskLists.LIST_COLOR,
            TaskLists.ACCESS_LEVEL
        )
        val selection = ids?.let { "${TasksContract._ID} IN (${it.joinToString(",")})" }
        query(TaskLists.getContentUri(), projection, selection) { cursor ->
            lists.add(
                DavTaskList(
                    id = cursor.long(TasksContract._ID)!!,
                    displayName = cursor.string(TaskLists.LIST_NAME) ?: "",
                    accountName = cursor.string(TasksContract.ACCOUNT_NAME) ?: "",
                    accountType = cursor.string(TasksContract.ACCOUNT_TYPE) ?: "",
                    color = cursor.long(TaskLists.LIST_COLOR)?.toInt() ?: context.getProperPrimaryColor(),
                    accessLevel = cursor.long(TaskLists.ACCESS_LEVEL)?.toInt() ?: 0
                )
            )
        }
        return lists
    }

    fun getCalendarForTaskList(list: DavTaskList): CalendarEntity {
        val existing = context.calendarsDB.getCalendarWithDavTaskListId(list.id)
        if (existing != null) {
            if (existing.caldavDisplayName != list.displayName || existing.color != list.color ||
                existing.caldavEmail != list.accountName
            ) {
                existing.apply {
                    title = list.displayName
                    caldavDisplayName = list.displayName
                    caldavEmail = list.accountName
                    color = list.color
                }
                context.calendarsDB.insertOrUpdate(existing)
            }
            return existing
        }

        val calendar = CalendarEntity(
            id = null,
            title = list.displayName,
            color = list.color,
            caldavDisplayName = list.displayName,
            caldavEmail = list.accountName,
            davTaskListId = list.id
        )
        calendar.id = context.eventsHelper.insertOrUpdateCalendarSync(calendar)
        return calendar
    }

    /** Mirrors every synced task list from the provider into the app database. */
    fun refreshTaskLists() {
        synchronized(pullLock) {
            val config = context.config
            if (!config.caldavSync) {
                return
            }

            val syncedIds = config.getSyncedTaskListIdsAsList()
            if (syncedIds.isEmpty()) {
                return
            }

            val lists = getTaskLists(syncedIds)
            val removedIds = syncedIds - lists.map { it.id }.toSet()
            if (removedIds.isNotEmpty()) {
                // the list was removed by the sync adapter, e.g. the collection was unsubscribed in DAVx⁵
                removeLocalTaskLists(removedIds)
                config.davSyncedTaskListIds = (syncedIds - removedIds.toSet()).joinToString(",")
            }

            lists.forEach {
                val calendar = getCalendarForTaskList(it)
                pullTasks(it, calendar.id!!)
            }
            context.updateWidgets()
        }
    }

    /** Removes the mirrored tasks and calendars of the given task lists from the app database only. */
    fun removeLocalTaskLists(taskListIds: List<Long>) {
        taskListIds.forEach { listId ->
            val ids = context.eventsDB.getTasksFromDavTaskList(getSource(listId)).mapNotNull { it.id }
            context.eventsHelper.deleteEvents(ids.toMutableList(), deleteFromCalDAV = false, updateWidgets = false)
        }
        context.calendarsDB.deleteCalendarsWithDavTaskListIds(taskListIds)
        context.updateWidgets()
    }

    private fun pullTasks(list: DavTaskList, localCalendarId: Long) {
        val source = getSource(list.id)
        val storedTasks = context.eventsDB.getTasksFromDavTaskList(source).associateBy { it.importId }
        val fetchedImportIds = HashSet<String>()
        val alarms = getAlarms(list.id)

        val projection = arrayOf(
            TasksContract._ID,
            Tasks.TITLE,
            Tasks.DESCRIPTION,
            Tasks.LOCATION,
            Tasks.TASK_COLOR,
            Tasks.STATUS,
            Tasks.COMPLETED,
            Tasks.DTSTART,
            Tasks.DUE,
            Tasks.IS_ALLDAY,
            Tasks.TZ,
            Tasks.RRULE,
            Tasks.EXDATE
        )
        val selection =
            "${Tasks.LIST_ID} = ? AND ${TasksContract._DELETED} = 0 AND ${Tasks.ORIGINAL_INSTANCE_ID} IS NULL"
        query(Tasks.getContentUri(), projection, selection, arrayOf(list.id.toString())) { cursor ->
            val remoteId = cursor.long(TasksContract._ID)!!
            val task = cursorToTask(cursor, list, localCalendarId, alarms[remoteId].orEmpty())
                ?: return@query
            fetchedImportIds.add(task.importId)

            val status = cursor.long(Tasks.STATUS)?.toInt()
            val isCompleted = status == Tasks.STATUS_COMPLETED || cursor.long(Tasks.COMPLETED) != null

            val stored = storedTasks[task.importId]
            if (stored == null) {
                context.eventsHelper.insertTask(
                    task = task,
                    showToasts = false,
                    enableCalendar = false,
                    addToCalDAV = false
                ) {}
            } else {
                task.id = stored.id
                task.parentId = stored.parentId
                task.lastUpdated = stored.lastUpdated
                if (stored.getSyncSignature() != task.getSyncSignature()) {
                    if (stored.startTS != task.startTS) {
                        context.completedTasksDB.deleteTaskFutureOccurrences(task.id!!, 0)
                    }

                    context.eventsHelper.updateEvent(
                        event = task,
                        updateAtCalDAV = false,
                        showToasts = false,
                        enableCalendar = false,
                        updateWidgets = false
                    )
                }
            }

            // completing a recurring task is tracked per occurrence by the app, the server can't express that
            if (task.repeatInterval == 0 && task.id != null) {
                val isCompletedLocally =
                    context.completedTasksDB.getTaskWithIdAndTs(task.id!!, task.startTS)?.isTaskCompleted() == true
                if (isCompleted != isCompletedLocally) {
                    context.updateTaskCompletion(task.copy(), isCompleted, pushToProvider = false)
                }
            }
        }

        val removedIds = storedTasks.filterKeys { it !in fetchedImportIds }.values.mapNotNull { it.id }
        context.eventsHelper.deleteEvents(removedIds.toMutableList(), deleteFromCalDAV = false, updateWidgets = false)
    }

    private fun cursorToTask(
        cursor: Cursor,
        list: DavTaskList,
        localCalendarId: Long,
        alarms: List<RemoteAlarm>
    ): Event? {
        val remoteId = cursor.long(TasksContract._ID)!!
        val due = cursor.long(Tasks.DUE)
        val dtstart = cursor.long(Tasks.DTSTART)
        val anchorMs = due ?: dtstart ?: return null
        val isAllDay = cursor.long(Tasks.IS_ALLDAY) == 1L
        val timeZone = cursor.string(Tasks.TZ)?.takeIf { it.isNotEmpty() } ?: DateTimeZone.getDefault().id

        val startTS = anchorMs / 1000L
        // recurrences follow DTSTART, while the app shows each occurrence at its due date
        val recurrenceOffset = if (due != null && dtstart != null) (due - dtstart) / 1000L else 0L
        val localStartTS = if (isAllDay) Formatter.getShiftedLocalTS(startTS) else startTS
        val repetition = Parser().parseRepeatInterval(cursor.string(Tasks.RRULE) ?: "", localStartTS)

        val reminders = alarms.map {
            val referenceMs = if (it.reference == Alarm.ALARM_REFERENCE_START_DATE) {
                dtstart ?: anchorMs
            } else {
                due ?: anchorMs
            }
            val minutes = it.minutesBefore + ((anchorMs - referenceMs) / MINUTE_MS).toInt()
            val type = if (it.type == Alarm.ALARM_TYPE_EMAIL) REMINDER_EMAIL else REMINDER_NOTIFICATION
            Reminder(minutes, type)
        }.distinctBy { it.minutes }.sortedBy { it.minutes }

        val reminder1 = reminders.getOrNull(0)
        val reminder2 = reminders.getOrNull(1)
        val reminder3 = reminders.getOrNull(2)

        val task = Event(
            id = null,
            startTS = startTS,
            endTS = startTS,
            title = cursor.string(Tasks.TITLE) ?: "",
            location = cursor.string(Tasks.LOCATION) ?: "",
            description = cursor.string(Tasks.DESCRIPTION) ?: "",
            reminder1Minutes = reminder1?.minutes ?: REMINDER_OFF,
            reminder2Minutes = reminder2?.minutes ?: REMINDER_OFF,
            reminder3Minutes = reminder3?.minutes ?: REMINDER_OFF,
            reminder1Type = reminder1?.type ?: REMINDER_NOTIFICATION,
            reminder2Type = reminder2?.type ?: REMINDER_NOTIFICATION,
            reminder3Type = reminder3?.type ?: REMINDER_NOTIFICATION,
            repeatInterval = repetition.repeatInterval,
            repeatRule = repetition.repeatRule,
            repeatLimit = repetition.repeatLimit,
            repetitionExceptions = if (repetition.repeatInterval == 0) {
                emptyList()
            } else {
                parseExdates(cursor.string(Tasks.EXDATE), isAllDay, timeZone, recurrenceOffset)
            },
            importId = getImportId(list.id, remoteId),
            timeZone = timeZone,
            flags = if (isAllDay) FLAG_ALL_DAY else 0,
            calendarId = localCalendarId,
            source = getSource(list.id),
            color = cursor.long(Tasks.TASK_COLOR)?.toInt()?.takeIf { it != list.color } ?: 0,
            type = TYPE_TASK
        )

        if (isAllDay) {
            task.toLocalAllDayEvent()
        }
        return task
    }

    private fun getAlarms(taskListId: Long): Map<Long, List<RemoteAlarm>> {
        val alarms = HashMap<Long, ArrayList<RemoteAlarm>>()
        val projection = arrayOf(Properties.TASK_ID, Alarm.MINUTES_BEFORE, Alarm.REFERENCE, Alarm.ALARM_TYPE)
        val selection =
            "${Properties.MIMETYPE} = ? AND ${Properties.TASK_ID} IN " +
                "(SELECT ${TasksContract._ID} FROM ${Tasks.PATH} WHERE ${Tasks.LIST_ID} = ?)"
        val args = arrayOf(Alarm.CONTENT_ITEM_TYPE, taskListId.toString())
        query(Properties.getContentUri(), projection, selection, args) { cursor ->
            val taskId = cursor.long(Properties.TASK_ID) ?: return@query
            val minutes = cursor.long(Alarm.MINUTES_BEFORE)?.toInt() ?: return@query
            alarms.getOrPut(taskId) { ArrayList() }.add(
                RemoteAlarm(
                    minutesBefore = minutes,
                    reference = cursor.long(Alarm.REFERENCE)?.toInt() ?: Alarm.ALARM_REFERENCE_DUE_DATE,
                    type = cursor.long(Alarm.ALARM_TYPE)?.toInt() ?: Alarm.ALARM_TYPE_MESSAGE
                )
            )
        }
        return alarms
    }

    // exdates are stored as "yyyyMMdd" for all-day tasks,
    // else as "yyyyMMddTHHmmss" in the task time zone or in UTC with a "Z" suffix.
    // They match DTSTART based instances, so [recurrenceOffset] (due - start, in seconds)
    // maps them to the app's occurrences
    private fun parseExdates(
        exdate: String?,
        isAllDay: Boolean,
        timeZone: String,
        recurrenceOffset: Long
    ): List<String> {
        if (exdate.isNullOrBlank()) {
            return emptyList()
        }

        return exdate.split(",").mapNotNull { rawValue ->
            val value = rawValue.substringAfter(";").trim()
            try {
                when {
                    value.length < DAY_CODE_LENGTH -> null
                    isAllDay -> shiftDayCode(value.substring(0, DAY_CODE_LENGTH), recurrenceOffset / DAY_SECONDS)
                    value.length == DAY_CODE_LENGTH -> value
                    else -> {
                        val zone = if (value.endsWith("Z")) DateTimeZone.UTC else DateTimeZone.forID(timeZone)
                        val dateTime = DateTimeFormat.forPattern(EXDATE_PATTERN).withZone(zone)
                            .parseDateTime(value.removeSuffix("Z"))
                        Formatter.getDayCodeFromTS(dateTime.millis / 1000L + recurrenceOffset)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }.distinct()
    }

    // date-times are written in UTC with a "Z" suffix, which DAVx⁵ parses whatever the task time zone is
    // (floating values are rejected when the time zone is UTC)
    private fun formatExdates(task: Event, recurrenceOffset: Long): String? {
        if (task.repeatInterval == 0 || task.repetitionExceptions.isEmpty()) {
            return null
        }

        val start = Formatter.getDateTimeFromTS(task.startTS)
        return task.repetitionExceptions.joinToString(",") { dayCode ->
            if (task.getIsAllDay()) {
                shiftDayCode(dayCode, -recurrenceOffset / DAY_SECONDS)
            } else {
                Formatter.getLocalDateTimeFromCode(dayCode)
                    .withTime(start.hourOfDay, start.minuteOfHour, start.secondOfMinute, 0)
                    .minusSeconds(recurrenceOffset.toInt())
                    .withZone(DateTimeZone.UTC)
                    .toString(EXDATE_UTC_PATTERN)
            }
        }
    }

    private fun shiftDayCode(dayCode: String, days: Long) = if (days == 0L) {
        dayCode
    } else {
        Formatter.getDayCodeFromDateTime(Formatter.getDateTimeFromCode(dayCode).plusDays(days.toInt()))
    }

    private fun getTaskListIdOfCalendar(calendarId: Long): Long {
        return context.calendarsDB.getCalendarWithId(calendarId)?.davTaskListId ?: 0L
    }

    /** DAVx⁵ discards local changes in read-only lists, and fails on local deletions in them. */
    fun isTaskListWritable(taskListId: Long) = getTaskLists(listOf(taskListId)).firstOrNull()?.canWrite() == true

    fun isReadOnlyTask(task: Event) = task.isDavTask() && !isTaskListWritable(task.getDavTaskListId())

    /** Checks if [task] can be stored in the calendar [calendarId], the task lists involved have to be writable. */
    fun canWriteTask(task: Event, calendarId: Long): Boolean {
        val listIds = listOf(task.getDavTaskListId(), getTaskListIdOfCalendar(calendarId))
            .filter { it != 0L }
            .distinct()
        return listIds.all { isTaskListWritable(it) }
    }

    /** Adds a task that was just stored in the app database to the provider, if its calendar is a synced task list. */
    fun insertTask(task: Event) = synchronized(pullLock) { insertTaskLocked(task) }

    private fun insertTaskLocked(task: Event) {
        if (task.id == null) {
            return
        }

        val taskListId = getTaskListIdOfCalendar(task.calendarId)
        if (taskListId == 0L) {
            // e.g. a copy of a synced task stored in a local calendar
            if (task.isDavTask()) {
                task.source = SOURCE_SIMPLE_CALENDAR
                context.eventsDB.updateTaskImportIdAndSource(task.importId, task.source, task.id!!)
            }
            return
        }

        if (!isTaskListWritable(taskListId)) {
            return
        }

        val values = fillTaskValues(task, null).apply {
            put(Tasks.LIST_ID, taskListId)
            put(TasksContract._UID, UUID.randomUUID().toString())
            if (task.isTaskCompleted()) {
                putAll(getCompletionValues(true))
            }
        }

        val uri = context.contentResolver.insert(Tasks.getContentUri(), values) ?: return
        val remoteId = ContentUris.parseId(uri)
        task.importId = getImportId(taskListId, remoteId)
        task.source = getSource(taskListId)
        context.eventsDB.updateTaskImportIdAndSource(task.importId, task.source, task.id!!)
        writeAlarms(remoteId, task, Alarm.ALARM_REFERENCE_DUE_DATE)
        requestSync(taskListId)
    }

    /** Updates the provider after a task was updated in the app database, also handles moving it between lists. */
    fun updateTask(task: Event) = synchronized(pullLock) { updateTaskLocked(task) }

    private fun updateTaskLocked(task: Event) {
        val targetListId = getTaskListIdOfCalendar(task.calendarId)
        val currentListId = task.getDavTaskListId()
        when {
            currentListId == targetListId -> if (targetListId != 0L && isTaskListWritable(targetListId)) {
                updateRemoteTask(task, targetListId)
            }

            currentListId == 0L -> insertTask(task)
            targetListId == 0L -> {
                deleteTask(task)
                task.importId = generateImportId()
                task.source = SOURCE_SIMPLE_CALENDAR
                context.eventsDB.updateTaskImportIdAndSource(task.importId, task.source, task.id!!)
            }

            else -> moveTask(task, currentListId, targetListId)
        }
    }

    private fun updateRemoteTask(task: Event, taskListId: Long) {
        val remoteId = task.getCalDAVEventId()
        val existing = getRemoteTimes(remoteId) ?: return
        val values = fillTaskValues(task, existing)
        context.contentResolver.update(ContentUris.withAppendedId(Tasks.getContentUri(), remoteId), values, null, null)
        writeAlarms(remoteId, task, getAlarmReference(values))
        requestSync(taskListId)
    }

    /**
     * Moves a task to another list of the provider. Its UID and all the fields the app doesn't edit
     * (priority, categories, unknown properties...) are kept, only the remote copy in the old list is deleted.
     */
    private fun moveTask(task: Event, currentListId: Long, targetListId: Long) {
        if (!isTaskListWritable(targetListId)) {
            return
        }

        val oldRemoteId = task.getCalDAVEventId()
        val movedValues = getMovedTaskValues(oldRemoteId)
        if (movedValues == null) {
            // the remote task is gone already, store it as a new one
            insertTask(task)
            return
        }

        val values = movedValues.apply {
            putAll(fillTaskValues(task, getRemoteTimes(oldRemoteId)))
            put(Tasks.LIST_ID, targetListId)
        }

        val uri = context.contentResolver.insert(Tasks.getContentUri(), values) ?: return
        val newRemoteId = ContentUris.parseId(uri)
        copyProperties(oldRemoteId, newRemoteId, includeAlarms = false)
        copyExceptions(oldRemoteId, newRemoteId, targetListId)
        writeAlarms(newRemoteId, task, getAlarmReference(values))
        if (isTaskListWritable(currentListId)) {
            context.contentResolver.delete(ContentUris.withAppendedId(Tasks.getContentUri(), oldRemoteId), null, null)
        }

        task.importId = getImportId(targetListId, newRemoteId)
        task.source = getSource(targetListId)
        context.eventsDB.updateTaskImportIdAndSource(task.importId, task.source, task.id!!)
        requestSync(currentListId)
        requestSync(targetListId)
    }

    private fun getMovedTaskValues(remoteId: Long): ContentValues? {
        val columns = (TasksDatabaseHelper.TASK_COLUMNS - NOT_MOVED_TASK_COLUMNS).toTypedArray()
        var values: ContentValues? = null
        query(Tasks.getContentUri(), columns, "${TasksContract._ID} = ?", arrayOf(remoteId.toString())) { cursor ->
            values = cursor.toContentValues()
        }
        return values
    }

    // the alarms of a moved task are rewritten by writeAlarms
    private fun copyProperties(fromRemoteId: Long, toRemoteId: Long, includeAlarms: Boolean) {
        val columns = (listOf(Properties.MIMETYPE) + Properties.DATA_COLUMNS).toTypedArray()
        val selection = if (includeAlarms) {
            "${Properties.TASK_ID} = ?"
        } else {
            "${Properties.TASK_ID} = ? AND ${Properties.MIMETYPE} != '${Alarm.CONTENT_ITEM_TYPE}'"
        }

        val properties = ArrayList<ContentValues>()
        query(Properties.getContentUri(), columns, selection, arrayOf(fromRemoteId.toString())) { cursor ->
            properties.add(cursor.toContentValues())
        }

        properties.forEach {
            it.put(Properties.TASK_ID, toRemoteId)
            context.contentResolver.insert(Properties.getContentUri(), it)
        }
    }

    // overridden occurrences (RECURRENCE-ID) are separate rows linked to the main task
    private fun copyExceptions(fromRemoteId: Long, toRemoteId: Long, targetListId: Long) {
        val columns = (TasksDatabaseHelper.TASK_COLUMNS - NOT_MOVED_TASK_COLUMNS + TasksContract._ID).toTypedArray()
        val exceptions = ArrayList<ContentValues>()
        query(Tasks.getContentUri(), columns, "${Tasks.ORIGINAL_INSTANCE_ID} = ?", arrayOf(fromRemoteId.toString())) {
            exceptions.add(it.toContentValues())
        }

        exceptions.forEach { values ->
            val exceptionId = values.getAsLong(TasksContract._ID)
            values.remove(TasksContract._ID)
            values.put(Tasks.LIST_ID, targetListId)
            values.put(Tasks.ORIGINAL_INSTANCE_ID, toRemoteId)
            val uri = context.contentResolver.insert(Tasks.getContentUri(), values) ?: return@forEach
            copyProperties(exceptionId, ContentUris.parseId(uri), includeAlarms = true)
        }
    }

    private fun getAlarmReference(values: ContentValues) = if (values.containsKey(Tasks.DUE)) {
        Alarm.ALARM_REFERENCE_DUE_DATE
    } else {
        Alarm.ALARM_REFERENCE_START_DATE
    }

    fun deleteTask(task: Event) = synchronized(pullLock) { deleteTaskLocked(task) }

    private fun deleteTaskLocked(task: Event) {
        if (!task.isDavTask() || !isTaskListWritable(task.getDavTaskListId())) {
            return
        }

        val uri = ContentUris.withAppendedId(Tasks.getContentUri(), task.getCalDAVEventId())
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
        requestSync(task.getDavTaskListId())
    }

    fun updateTaskCompletion(task: Event, completed: Boolean) = synchronized(pullLock) {
        updateTaskCompletionLocked(task, completed)
    }

    private fun updateTaskCompletionLocked(task: Event, completed: Boolean) {
        // the completion of recurring tasks is kept per occurrence and stays local
        if (!task.isDavTask() || task.repeatInterval != 0 || !isTaskListWritable(task.getDavTaskListId())) {
            return
        }

        val uri = ContentUris.withAppendedId(Tasks.getContentUri(), task.getCalDAVEventId())
        context.contentResolver.update(uri, getCompletionValues(completed), null, null)
        requestSync(task.getDavTaskListId())
    }

    private fun getCompletionValues(completed: Boolean) = ContentValues().apply {
        if (completed) {
            put(Tasks.STATUS, Tasks.STATUS_COMPLETED)
            put(Tasks.COMPLETED, System.currentTimeMillis())
            put(Tasks.COMPLETED_IS_ALLDAY, 0)
            put(Tasks.PERCENT_COMPLETE, PERCENT_COMPLETED)
        } else {
            put(Tasks.STATUS, Tasks.STATUS_NEEDS_ACTION)
            putNull(Tasks.COMPLETED)
            putNull(Tasks.COMPLETED_IS_ALLDAY)
            putNull(Tasks.PERCENT_COMPLETE)
        }
    }

    private fun getRemoteTimes(remoteId: Long): RemoteTimes? {
        var times: RemoteTimes? = null
        val uri = ContentUris.withAppendedId(Tasks.getContentUri(), remoteId)
        val projection = arrayOf(Tasks.DTSTART, Tasks.DUE, Tasks.IS_ALLDAY, TasksContract._DELETED)
        query(uri, projection) { cursor ->
            if (cursor.long(TasksContract._DELETED) != 1L) {
                times = RemoteTimes(
                    dtstart = cursor.long(Tasks.DTSTART),
                    due = cursor.long(Tasks.DUE),
                    isAllDay = cursor.long(Tasks.IS_ALLDAY) == 1L
                )
            }
        }
        return times
    }

    // only the fields the app can edit are written,
    // everything else (priority, categories, relations...) is kept as it is
    private fun fillTaskValues(task: Event, existing: RemoteTimes?) = ContentValues().apply {
        put(Tasks.TITLE, task.title)
        put(Tasks.DESCRIPTION, task.description)
        put(Tasks.LOCATION, task.location)
        if (task.color == 0) {
            putNull(Tasks.TASK_COLOR)
        } else {
            put(Tasks.TASK_COLOR, task.color)
        }

        val isAllDay = task.getIsAllDay()
        val timestamp = if (isAllDay) {
            Formatter.getShiftedUtcTS(task.startTS) * 1000L
        } else {
            task.startTS * 1000L
        }

        put(Tasks.IS_ALLDAY, if (isAllDay) 1 else 0)
        if (isAllDay) {
            putNull(Tasks.TZ)
        } else {
            put(Tasks.TZ, task.getTimeZoneString())
        }

        // the app shows tasks at their due date, or at their start date when they have no due date
        val useDue = existing == null || existing.due != null || existing.dtstart == null
        if (useDue) {
            put(Tasks.DUE, timestamp)
            if (existing != null) {
                shiftStart(existing, timestamp, isAllDay)
            }
        } else {
            put(Tasks.DTSTART, timestamp)
        }

        val repeatRule = Parser().getRepeatCode(task)
        if (repeatRule.isEmpty()) {
            putNull(Tasks.RRULE)
            putNull(Tasks.EXDATE)
        } else {
            put(Tasks.RRULE, repeatRule)
            put(Tasks.EXDATE, formatExdates(task, getRecurrenceOffset(existing, isAllDay)))
        }
    }

    // the time span between start and due kept by [shiftStart], in seconds
    private fun getRecurrenceOffset(existing: RemoteTimes?, isAllDay: Boolean): Long {
        val existingStart = existing?.dtstart ?: return 0L
        val existingDue = existing.due ?: return 0L
        return if (existing.isAllDay == isAllDay) (existingDue - existingStart) / 1000L else 0L
    }

    private fun ContentValues.shiftStart(existing: RemoteTimes, newDue: Long, isAllDay: Boolean) {
        val existingStart = existing.dtstart ?: return
        val existingDue = existing.due ?: return
        if (existing.isAllDay != isAllDay) {
            putNull(Tasks.DTSTART)
        } else {
            // keep the time span between start and due
            put(Tasks.DTSTART, existingStart + newDue - existingDue)
        }
    }

    private fun writeAlarms(remoteId: Long, task: Event, reference: Int) {
        val resolver = context.contentResolver
        resolver.delete(
            Properties.getContentUri(),
            "${Properties.TASK_ID} = ? AND ${Properties.MIMETYPE} = ?",
            arrayOf(remoteId.toString(), Alarm.CONTENT_ITEM_TYPE)
        )

        task.getReminders().forEach {
            val values = ContentValues().apply {
                put(Properties.TASK_ID, remoteId)
                put(Properties.MIMETYPE, Alarm.CONTENT_ITEM_TYPE)
                put(Alarm.MINUTES_BEFORE, it.minutes)
                put(Alarm.REFERENCE, reference)
                put(Alarm.MESSAGE, task.title)
                put(
                    Alarm.ALARM_TYPE,
                    if (it.type == REMINDER_EMAIL) Alarm.ALARM_TYPE_EMAIL else Alarm.ALARM_TYPE_MESSAGE
                )
            }
            resolver.insert(Properties.getContentUri(), values)
        }
    }

    /** Asks the sync adapters to sync the given task lists, or all synced ones. */
    fun requestSync(taskListId: Long? = null, manual: Boolean = false) {
        val ids = taskListId?.let { listOf(it) } ?: context.config.getSyncedTaskListIdsAsList()
        val accounts = getTaskLists(ids).map { Account(it.accountName, it.accountType) }.toSet()
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            if (manual) {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            }
        }

        accounts.forEach {
            try {
                ContentResolver.requestSync(it, TasksContract.AUTHORITY, extras)
            } catch (_: Exception) {
            }
        }
    }

    private inline fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        callback: (cursor: Cursor) -> Unit
    ) {
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                callback(cursor)
            }
        }
    }

    private fun Cursor.long(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)?.toLongOrNull() ?: getLong(index)
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    // the fields mirrored from the provider, to find out if a stored task has to be updated
    private fun Event.getSyncSignature() = copy(id = null, lastUpdated = 0L, flags = flags and FLAG_ALL_DAY)
}

private fun Cursor.toContentValues() = ContentValues().apply {
    for (index in 0 until columnCount) {
        val column = getColumnName(index)
        when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> putNull(column)
            Cursor.FIELD_TYPE_INTEGER -> put(column, getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> put(column, getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> put(column, getBlob(index))
            else -> put(column, getString(index))
        }
    }
}

package org.fossify.calendar.dialogs

import android.text.TextUtils
import android.widget.RelativeLayout
import org.fossify.calendar.R
import org.fossify.calendar.activities.SimpleActivity
import org.fossify.calendar.databinding.CalendarItemAccountBinding
import org.fossify.calendar.databinding.CalendarItemCalendarBinding
import org.fossify.calendar.databinding.DialogSelectCalendarsBinding
import org.fossify.calendar.extensions.calDAVHelper
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.davTasksHelper
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.views.MyAppCompatCheckbox

class ManageSyncedCalendarsDialog(val activity: SimpleActivity, val callback: () -> Unit) {
    // CalDAV task lists are tagged with this wrapper to tell them apart from calendar ids
    private data class TaskListTag(val id: Long)

    private var prevAccount = ""
    private val binding by activity.viewBinding(DialogSelectCalendarsBinding::inflate)

    init {
        val ids = activity.config.getSyncedCalendarIdsAsList()
        val taskListIds = activity.config.getSyncedTaskListIdsAsList()
        val calendars = activity.calDAVHelper.getCalDAVCalendars("", true)
        val taskLists = activity.davTasksHelper.getTaskLists()
        val isEmpty = calendars.isEmpty() && taskLists.isEmpty()
        binding.apply {
            dialogSelectCalendarsPlaceholder.beVisibleIf(isEmpty)
            dialogSelectCalendarsHolder.beVisibleIf(!isEmpty)
        }

        val accounts = (calendars.map { it.accountName } + taskLists.map { it.accountName }).distinct().sorted()
        accounts.forEach { account ->
            if (prevAccount != account) {
                prevAccount = account
                addCalendarItem(false, account)
            }

            calendars.filter { it.accountName == account }.sortedBy { it.displayName }.forEach {
                addCalendarItem(true, it.displayName, it.id, ids.contains(it.id))
            }

            taskLists.filter { it.accountName == account }.sortedBy { it.displayName }.forEach {
                val title = activity.getString(R.string.caldav_task_list, it.displayName)
                addCalendarItem(true, title, TaskListTag(it.id), taskListIds.contains(it.id))
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> confirmSelection() }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.select_caldav_calendars)
            }
    }

    private fun addCalendarItem(
        isEvent: Boolean,
        text: String,
        tag: Any = 0,
        shouldCheck: Boolean = false
    ) {
        val itemBinding = if (isEvent) {
            CalendarItemCalendarBinding.inflate(
                activity.layoutInflater,
                binding.dialogSelectCalendarsHolder,
                false
            ).apply {
                calendarItemCalendarSwitch.tag = tag
                calendarItemCalendarSwitch.text = text
                calendarItemCalendarSwitch.isChecked = shouldCheck
                root.setOnClickListener {
                    calendarItemCalendarSwitch.toggle()
                }
            }
        } else {
            CalendarItemAccountBinding.inflate(
                activity.layoutInflater,
                binding.dialogSelectCalendarsHolder,
                false
            ).apply {
                calendarItemAccount.text = text
            }
        }

        binding.dialogSelectCalendarsHolder.addView(itemBinding.root)
    }

    private fun confirmSelection() {
        val calendarIds = ArrayList<Int>()
        val taskListIds = ArrayList<Long>()
        val childCnt = binding.dialogSelectCalendarsHolder.childCount
        for (i in 0..childCnt) {
            val child = binding.dialogSelectCalendarsHolder.getChildAt(i)
            if (child is RelativeLayout) {
                val check = child.getChildAt(0)
                if (check is MyAppCompatCheckbox && check.isChecked) {
                    when (val tag = check.tag) {
                        is TaskListTag -> taskListIds.add(tag.id)
                        is Int -> calendarIds.add(tag)
                    }
                }
            }
        }

        activity.config.caldavSyncedCalendarIds = TextUtils.join(",", calendarIds)
        activity.config.davSyncedTaskListIds = TextUtils.join(",", taskListIds)
        callback()
    }
}

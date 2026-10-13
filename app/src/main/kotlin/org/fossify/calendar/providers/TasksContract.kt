package org.fossify.calendar.providers

import android.content.Context
import android.net.Uri
import org.fossify.calendar.BuildConfig

/**
 * Subset of the OpenTasks contract (org.dmfs.tasks.contract.TaskContract) exposed by [TasksProvider].
 * Column names and values must stay identical to OpenTasks, sync adapters like DAVx⁵ rely on them.
 */
object TasksContract {
    val AUTHORITY = "${BuildConfig.APPLICATION_ID}.tasks"

    const val CALLER_IS_SYNCADAPTER = "caller_is_syncadapter"
    const val ACCOUNT_NAME = "account_name"
    const val ACCOUNT_TYPE = "account_type"
    const val LOAD_PROPERTIES = "load_properties"

    fun getContentUri(): Uri = Uri.parse("content://$AUTHORITY")

    fun getSyncAdapterUri(uri: Uri, accountName: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ACCOUNT_NAME, accountName)
            .appendQueryParameter(ACCOUNT_TYPE, accountType)
            .build()

    fun isCallerSyncAdapter(uri: Uri) =
        uri.getBooleanQueryParameter(CALLER_IS_SYNCADAPTER, false)

    private const val SYNC_COLUMN_COUNT = 8
    private const val LAST_DATA_COLUMN = 15

    // columns shared by task lists and tasks, named like in OpenTasks' TaskContract
    @Suppress("ObjectPropertyNaming")
    const val _ID = "_id"
    @Suppress("ObjectPropertyNaming")
    const val _SYNC_ID = "_sync_id"
    const val SYNC_VERSION = "sync_version"
    @Suppress("ObjectPropertyNaming")
    const val _DIRTY = "_dirty"
    @Suppress("ObjectPropertyNaming")
    const val _DELETED = "_deleted"
    @Suppress("ObjectPropertyNaming")
    const val _UID = "_uid"
    val SYNC_COLUMNS = (1..SYNC_COLUMN_COUNT).map { "sync$it" }

    object TaskLists {
        const val PATH = "tasklists"
        fun getContentUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH")

        const val LIST_NAME = "list_name"
        const val LIST_COLOR = "list_color"
        const val ACCESS_LEVEL = "list_access_level"
        const val VISIBLE = "visible"
        const val SYNC_ENABLED = "sync_enabled"
        const val OWNER = "list_owner"

        const val ACCESS_LEVEL_UNDEFINED = 0
        const val ACCESS_LEVEL_READ = 200
        const val ACCESS_LEVEL_OWNER = 700
    }

    object Tasks {
        const val PATH = "tasks"
        fun getContentUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH")

        const val LIST_ID = "list_id"
        const val TITLE = "title"
        const val LOCATION = "location"
        const val GEO = "geo"
        const val DESCRIPTION = "description"
        const val URL = "url"
        const val ORGANIZER = "organizer"
        const val PRIORITY = "priority"
        const val CLASSIFICATION = "class"
        const val COMPLETED = "completed"
        const val COMPLETED_IS_ALLDAY = "completed_is_allday"
        const val PERCENT_COMPLETE = "percent_complete"
        const val STATUS = "status"
        const val TASK_COLOR = "task_color"
        const val DTSTART = "dtstart"
        const val IS_ALLDAY = "is_allday"
        const val CREATED = "created"
        const val LAST_MODIFIED = "last_modified"
        const val TZ = "tz"
        const val DUE = "due"
        const val DURATION = "duration"
        const val RDATE = "rdate"
        const val EXDATE = "exdate"
        const val RRULE = "rrule"
        const val PARENT_ID = "parent_id"
        const val ORIGINAL_INSTANCE_SYNC_ID = "original_instance_sync_id"
        const val ORIGINAL_INSTANCE_ID = "original_instance_id"
        const val ORIGINAL_INSTANCE_TIME = "original_instance_time"
        const val ORIGINAL_INSTANCE_ALLDAY = "original_instance_allday"
        const val VERSION = "version"

        const val STATUS_NEEDS_ACTION = 0
        const val STATUS_IN_PROCESS = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_CANCELLED = 3
    }

    object Properties {
        const val PATH = "properties"
        fun getContentUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH")

        const val PROPERTY_ID = "property_id"
        const val TASK_ID = "task_id"
        const val MIMETYPE = "mimetype"
        const val VERSION = "prop_version"
        val DATA_COLUMNS = (0..LAST_DATA_COLUMN).map { "data$it" }
        val SYNC_COLUMNS = (1..SYNC_COLUMN_COUNT).map { "prop_sync$it" }
    }

    object Alarm {
        const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/alarm"
        const val MINUTES_BEFORE = "data0"
        const val REFERENCE = "data1"
        const val MESSAGE = "data2"
        const val ALARM_TYPE = "data3"

        const val ALARM_REFERENCE_DUE_DATE = 1
        const val ALARM_REFERENCE_START_DATE = 2

        const val ALARM_TYPE_MESSAGE = 1
        const val ALARM_TYPE_EMAIL = 2
    }

    object Relation {
        const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/relation"
        const val RELATED_ID = "data1"
        const val RELATED_TYPE = "data2"
        const val RELATED_UID = "data3"

        const val RELTYPE_PARENT = 0
        const val RELTYPE_CHILD = 1
    }

    fun notifyChange(context: Context, syncToNetwork: Boolean) {
        @Suppress("DEPRECATION")
        context.contentResolver.notifyChange(getContentUri(), null, syncToNetwork)
    }
}

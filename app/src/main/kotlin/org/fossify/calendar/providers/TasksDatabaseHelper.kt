package org.fossify.calendar.providers

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.fossify.calendar.providers.TasksContract.Properties
import org.fossify.calendar.providers.TasksContract.TaskLists
import org.fossify.calendar.providers.TasksContract.Tasks

/**
 * Storage behind [TasksProvider]. This is the "remote" side of CalDAV tasks: sync adapters (DAVx⁵) read and write it,
 * and [org.fossify.calendar.helpers.DavTasksHelper] mirrors it into the app's own Room database.
 */
class TasksDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "tasks_provider.db"
        private const val DB_VERSION = 1

        const val TABLE_LISTS = "tasklists"
        const val TABLE_TASKS = "tasks"
        const val TABLE_PROPERTIES = "properties"

        val LIST_COLUMNS = setOf(
            TasksContract._ID,
            TasksContract.ACCOUNT_NAME,
            TasksContract.ACCOUNT_TYPE,
            TaskLists.LIST_NAME,
            TaskLists.LIST_COLOR,
            TaskLists.ACCESS_LEVEL,
            TaskLists.VISIBLE,
            TaskLists.SYNC_ENABLED,
            TaskLists.OWNER,
            TasksContract._SYNC_ID,
            TasksContract.SYNC_VERSION,
        ) + TasksContract.SYNC_COLUMNS

        val TASK_COLUMNS = setOf(
            TasksContract._ID,
            Tasks.LIST_ID,
            TasksContract._UID,
            TasksContract._SYNC_ID,
            TasksContract.SYNC_VERSION,
            TasksContract._DIRTY,
            TasksContract._DELETED,
            Tasks.VERSION,
            Tasks.TITLE,
            Tasks.LOCATION,
            Tasks.GEO,
            Tasks.DESCRIPTION,
            Tasks.URL,
            Tasks.ORGANIZER,
            Tasks.PRIORITY,
            Tasks.CLASSIFICATION,
            Tasks.COMPLETED,
            Tasks.COMPLETED_IS_ALLDAY,
            Tasks.PERCENT_COMPLETE,
            Tasks.STATUS,
            Tasks.TASK_COLOR,
            Tasks.DTSTART,
            Tasks.IS_ALLDAY,
            Tasks.CREATED,
            Tasks.LAST_MODIFIED,
            Tasks.TZ,
            Tasks.DUE,
            Tasks.DURATION,
            Tasks.RDATE,
            Tasks.EXDATE,
            Tasks.RRULE,
            Tasks.PARENT_ID,
            Tasks.ORIGINAL_INSTANCE_SYNC_ID,
            Tasks.ORIGINAL_INSTANCE_ID,
            Tasks.ORIGINAL_INSTANCE_TIME,
            Tasks.ORIGINAL_INSTANCE_ALLDAY,
        ) + TasksContract.SYNC_COLUMNS

        val PROPERTY_COLUMNS = setOf(
            Properties.PROPERTY_ID,
            Properties.TASK_ID,
            Properties.MIMETYPE,
            Properties.VERSION,
        ) + Properties.DATA_COLUMNS + Properties.SYNC_COLUMNS

        @Volatile
        private var instance: TasksDatabaseHelper? = null

        fun getInstance(context: Context): TasksDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: TasksDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        val listSyncColumns = TasksContract.SYNC_COLUMNS.joinToString("") { ", $it TEXT" }
        db.execSQL(
            """
            CREATE TABLE $TABLE_LISTS (
                ${TasksContract._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${TasksContract.ACCOUNT_NAME} TEXT,
                ${TasksContract.ACCOUNT_TYPE} TEXT,
                ${TaskLists.LIST_NAME} TEXT,
                ${TaskLists.LIST_COLOR} INTEGER,
                ${TaskLists.ACCESS_LEVEL} INTEGER NOT NULL DEFAULT 0,
                ${TaskLists.VISIBLE} INTEGER NOT NULL DEFAULT 1,
                ${TaskLists.SYNC_ENABLED} INTEGER NOT NULL DEFAULT 1,
                ${TaskLists.OWNER} TEXT,
                ${TasksContract._SYNC_ID} TEXT,
                ${TasksContract.SYNC_VERSION} TEXT
                $listSyncColumns
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_TASKS (
                ${TasksContract._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${Tasks.LIST_ID} INTEGER NOT NULL REFERENCES $TABLE_LISTS(${TasksContract._ID}) ON DELETE CASCADE,
                ${TasksContract._UID} TEXT,
                ${TasksContract._SYNC_ID} TEXT,
                ${TasksContract.SYNC_VERSION} TEXT,
                ${TasksContract._DIRTY} INTEGER NOT NULL DEFAULT 1,
                ${TasksContract._DELETED} INTEGER NOT NULL DEFAULT 0,
                ${Tasks.VERSION} INTEGER NOT NULL DEFAULT 0,
                ${Tasks.TITLE} TEXT,
                ${Tasks.LOCATION} TEXT,
                ${Tasks.GEO} TEXT,
                ${Tasks.DESCRIPTION} TEXT,
                ${Tasks.URL} TEXT,
                ${Tasks.ORGANIZER} TEXT,
                ${Tasks.PRIORITY} INTEGER,
                ${Tasks.CLASSIFICATION} INTEGER,
                ${Tasks.COMPLETED} INTEGER,
                ${Tasks.COMPLETED_IS_ALLDAY} INTEGER,
                ${Tasks.PERCENT_COMPLETE} INTEGER,
                ${Tasks.STATUS} INTEGER,
                ${Tasks.TASK_COLOR} INTEGER,
                ${Tasks.DTSTART} INTEGER,
                ${Tasks.IS_ALLDAY} INTEGER,
                ${Tasks.CREATED} INTEGER,
                ${Tasks.LAST_MODIFIED} INTEGER,
                ${Tasks.TZ} TEXT,
                ${Tasks.DUE} INTEGER,
                ${Tasks.DURATION} TEXT,
                ${Tasks.RDATE} TEXT,
                ${Tasks.EXDATE} TEXT,
                ${Tasks.RRULE} TEXT,
                ${Tasks.PARENT_ID} INTEGER,
                ${Tasks.ORIGINAL_INSTANCE_SYNC_ID} TEXT,
                ${Tasks.ORIGINAL_INSTANCE_ID} INTEGER,
                ${Tasks.ORIGINAL_INSTANCE_TIME} INTEGER,
                ${Tasks.ORIGINAL_INSTANCE_ALLDAY} INTEGER
                $listSyncColumns
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX tasks_list_id ON $TABLE_TASKS (${Tasks.LIST_ID})")
        db.execSQL("CREATE INDEX tasks_uid ON $TABLE_TASKS (${TasksContract._UID})")

        val dataColumns = Properties.DATA_COLUMNS.joinToString("") { ", $it TEXT" }
        val propSyncColumns = Properties.SYNC_COLUMNS.joinToString("") { ", $it TEXT" }
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROPERTIES (
                ${Properties.PROPERTY_ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${Properties.TASK_ID} INTEGER NOT NULL REFERENCES $TABLE_TASKS(${TasksContract._ID}) ON DELETE CASCADE,
                ${Properties.MIMETYPE} TEXT NOT NULL,
                ${Properties.VERSION} INTEGER NOT NULL DEFAULT 0
                $dataColumns
                $propSyncColumns
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX properties_task_id ON $TABLE_PROPERTIES (${Properties.TASK_ID})")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // there is only one version of the database so far
    }
}

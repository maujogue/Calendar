package org.fossify.calendar.providers

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import org.fossify.calendar.helpers.DavTasksHelper
import org.fossify.calendar.providers.TasksContract.Properties
import org.fossify.calendar.providers.TasksContract.Relation
import org.fossify.calendar.providers.TasksContract.Tasks
import org.fossify.calendar.providers.TasksDatabaseHelper.Companion.LIST_COLUMNS
import org.fossify.calendar.providers.TasksDatabaseHelper.Companion.PROPERTY_COLUMNS
import org.fossify.calendar.providers.TasksDatabaseHelper.Companion.TABLE_LISTS
import org.fossify.calendar.providers.TasksDatabaseHelper.Companion.TABLE_PROPERTIES
import org.fossify.calendar.providers.TasksDatabaseHelper.Companion.TABLE_TASKS
import org.fossify.calendar.providers.TasksDatabaseHelper.Companion.TASK_COLUMNS

/**
 * OpenTasks compatible task provider, so that sync adapters like DAVx⁵ can sync CalDAV tasks (VTODO) straight into
 * Fossify Calendar without a separate tasks app.
 *
 * Semantics follow OpenTasks:
 * - writes by a sync adapter (caller_is_syncadapter=true) are stored as they are;
 * - writes by anyone else mark the task as dirty, deletes only flag the task as deleted until the sync adapter
 *   purges it;
 * - task lists can only be created by sync adapters and are scoped to the account passed in the uri.
 */
class TasksProvider : ContentProvider() {
    companion object {
        private const val LISTS = 1
        private const val LIST_ID = 2
        private const val TASKS = 3
        private const val TASK_ID = 4
        private const val PROPERTIES = 5
        private const val PROPERTY_ID = 6

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(TasksContract.AUTHORITY, TasksContract.TaskLists.PATH, LISTS)
            addURI(TasksContract.AUTHORITY, "${TasksContract.TaskLists.PATH}/#", LIST_ID)
            addURI(TasksContract.AUTHORITY, Tasks.PATH, TASKS)
            addURI(TasksContract.AUTHORITY, "${Tasks.PATH}/#", TASK_ID)
            addURI(TasksContract.AUTHORITY, Properties.PATH, PROPERTIES)
            addURI(TasksContract.AUTHORITY, "${Properties.PATH}/#", PROPERTY_ID)
        }

        private const val TASKS_WITH_PROPERTIES = "$TABLE_TASKS LEFT OUTER JOIN $TABLE_PROPERTIES " +
            "ON ($TABLE_PROPERTIES.${Properties.TASK_ID} = $TABLE_TASKS.${TasksContract._ID})"
    }

    private lateinit var dbHelper: TasksDatabaseHelper

    // set while applying a batch so that change notifications and relation updates are done only once at the end
    private val batchState = ThreadLocal<BatchState?>()

    private class BatchState {
        var changed = false
        var changedBySyncAdapter = false
        var relationsChanged = false
    }

    override fun onCreate(): Boolean {
        dbHelper = TasksDatabaseHelper.getInstance(context!!)
        return true
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        LISTS -> "vnd.android.cursor.dir/org.dmfs.tasks.tasklists"
        LIST_ID -> "vnd.android.cursor.item/org.dmfs.tasks.tasklists"
        TASKS -> "vnd.android.cursor.dir/org.dmfs.tasks.tasks"
        TASK_ID -> "vnd.android.cursor.item/org.dmfs.tasks.tasks"
        PROPERTIES -> "vnd.android.cursor.dir/org.dmfs.tasks.properties"
        PROPERTY_ID -> "vnd.android.cursor.item/org.dmfs.tasks.properties"
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val builder = SQLiteQueryBuilder()
        val where = ArrayList<String>()
        val args = ArrayList<String>()

        when (uriMatcher.match(uri)) {
            LISTS, LIST_ID -> {
                builder.tables = TABLE_LISTS
                addListAccountFilter(uri, where, args, TasksContract._ID)
            }

            TASKS, TASK_ID -> {
                builder.tables = if (uri.getQueryParameter(TasksContract.LOAD_PROPERTIES) != null) {
                    TASKS_WITH_PROPERTIES
                } else {
                    TABLE_TASKS
                }
                addListAccountFilter(uri, where, args, Tasks.LIST_ID)
            }

            PROPERTIES, PROPERTY_ID -> builder.tables = TABLE_PROPERTIES
            else -> throw IllegalArgumentException("Unknown uri $uri")
        }

        addIdFilter(uri, where, args)
        val cursor = builder.query(
            dbHelper.readableDatabase,
            projection,
            combineSelection(where, selection),
            (args + selectionArgs.orEmpty()).toTypedArray(),
            null,
            null,
            sortOrder
        )
        cursor.setNotificationUri(context!!.contentResolver, TasksContract.getContentUri())
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val isSyncAdapter = TasksContract.isCallerSyncAdapter(uri)
        val db = dbHelper.writableDatabase
        val newId: Long
        var relationsChanged = false

        when (uriMatcher.match(uri)) {
            LISTS -> {
                if (!isSyncAdapter) {
                    throw UnsupportedOperationException("Only sync adapters can create task lists")
                }

                val listValues = filterValues(values, LIST_COLUMNS)
                uri.getQueryParameter(TasksContract.ACCOUNT_NAME)?.let {
                    listValues.put(TasksContract.ACCOUNT_NAME, it)
                }
                uri.getQueryParameter(TasksContract.ACCOUNT_TYPE)?.let {
                    listValues.put(TasksContract.ACCOUNT_TYPE, it)
                }
                newId = db.insertOrThrow(TABLE_LISTS, null, listValues)
            }

            TASKS -> {
                val taskValues = filterValues(values, TASK_COLUMNS)
                if (!isSyncAdapter) {
                    taskValues.put(TasksContract._DIRTY, 1)
                    taskValues.put(TasksContract._DELETED, 0)
                }
                newId = db.insertOrThrow(TABLE_TASKS, null, taskValues)
                relationsChanged = taskValues.containsKey(TasksContract._UID)
            }

            PROPERTIES -> {
                val propertyValues = filterValues(values, PROPERTY_COLUMNS)
                newId = db.insertOrThrow(TABLE_PROPERTIES, null, propertyValues)
                if (!isSyncAdapter) {
                    markTasksDirty(db, listOf(propertyValues.getAsLong(Properties.TASK_ID)))
                }
                relationsChanged =
                    propertyValues.getAsString(Properties.MIMETYPE) == Relation.CONTENT_ITEM_TYPE
            }

            else -> throw IllegalArgumentException("Unsupported uri for insert $uri")
        }

        onChange(isSyncAdapter, relationsChanged)
        return ContentUris.withAppendedId(uri.buildUpon().clearQuery().build(), newId)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val isSyncAdapter = TasksContract.isCallerSyncAdapter(uri)
        val db = dbHelper.writableDatabase
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        addIdFilter(uri, where, args)
        val finalSelection = combineSelection(where, selection)
        val finalArgs = (args + selectionArgs.orEmpty()).toTypedArray()
        var relationsChanged = false

        val count = when (uriMatcher.match(uri)) {
            LISTS, LIST_ID -> {
                val listValues = filterValues(values, LIST_COLUMNS)
                if (listValues.size() == 0) 0 else db.update(TABLE_LISTS, listValues, finalSelection, finalArgs)
            }

            TASKS, TASK_ID -> {
                // DAVx⁵ renames accounts by updating account_name through the tasks uri, apply it to the task lists
                var renamedLists = 0
                values?.getAsString(TasksContract.ACCOUNT_NAME)?.let { newName ->
                    val listValues = ContentValues().apply { put(TasksContract.ACCOUNT_NAME, newName) }
                    val listSelection = selection?.takeIf { it.isNotBlank() } ?: "1"
                    renamedLists = db.update(TABLE_LISTS, listValues, listSelection, selectionArgs)
                }

                val taskValues = filterValues(values, TASK_COLUMNS)
                if (taskValues.size() == 0) {
                    renamedLists
                } else {
                    if (!isSyncAdapter) {
                        taskValues.put(TasksContract._DIRTY, 1)
                    }
                    relationsChanged = taskValues.containsKey(TasksContract._UID) ||
                        taskValues.containsKey(Tasks.PARENT_ID)
                    db.update(TABLE_TASKS, taskValues, finalSelection, finalArgs)
                }
            }

            PROPERTIES, PROPERTY_ID -> {
                val propertyValues = filterValues(values, PROPERTY_COLUMNS)
                val taskIds = if (isSyncAdapter) emptyList() else getPropertyTaskIds(db, finalSelection, finalArgs)
                val updated = db.update(TABLE_PROPERTIES, propertyValues, finalSelection, finalArgs)
                markTasksDirty(db, taskIds)
                relationsChanged = true
                updated
            }

            else -> throw IllegalArgumentException("Unsupported uri for update $uri")
        }

        if (count > 0) {
            onChange(isSyncAdapter, relationsChanged)
        }
        return count
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val isSyncAdapter = TasksContract.isCallerSyncAdapter(uri)
        val db = dbHelper.writableDatabase
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        addIdFilter(uri, where, args)
        val finalSelection = combineSelection(where, selection)
        val finalArgs = (args + selectionArgs.orEmpty()).toTypedArray()

        val count = when (uriMatcher.match(uri)) {
            LISTS, LIST_ID -> {
                if (!isSyncAdapter) {
                    throw UnsupportedOperationException("Only sync adapters can delete task lists")
                }
                db.delete(TABLE_LISTS, finalSelection, finalArgs)
            }

            TASKS, TASK_ID -> if (isSyncAdapter) {
                db.delete(TABLE_TASKS, finalSelection, finalArgs)
            } else {
                // keep the row until the sync adapter has deleted it on the server
                val deletedValues = ContentValues().apply {
                    put(TasksContract._DELETED, 1)
                    put(TasksContract._DIRTY, 1)
                }
                db.update(TABLE_TASKS, deletedValues, finalSelection, finalArgs)
            }

            PROPERTIES, PROPERTY_ID -> {
                val taskIds = if (isSyncAdapter) emptyList() else getPropertyTaskIds(db, finalSelection, finalArgs)
                val deleted = db.delete(TABLE_PROPERTIES, finalSelection, finalArgs)
                markTasksDirty(db, taskIds)
                deleted
            }

            else -> throw IllegalArgumentException("Unsupported uri for delete $uri")
        }

        if (count > 0) {
            onChange(isSyncAdapter, relationsChanged = true)
        }
        return count
    }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val db = dbHelper.writableDatabase
        val state = BatchState()
        batchState.set(state)
        db.beginTransaction()
        try {
            val results = super.applyBatch(operations)
            if (state.relationsChanged) {
                updateRelations(db)
            }
            db.setTransactionSuccessful()
            return results
        } finally {
            db.endTransaction()
            batchState.set(null)
            if (state.changed) {
                notifyChange(state.changedBySyncAdapter)
            }
        }
    }

    private fun onChange(isSyncAdapter: Boolean, relationsChanged: Boolean) {
        val state = batchState.get()
        if (state != null) {
            state.changed = true
            state.changedBySyncAdapter = state.changedBySyncAdapter || isSyncAdapter
            state.relationsChanged = state.relationsChanged || relationsChanged
        } else {
            if (relationsChanged) {
                updateRelations(dbHelper.writableDatabase)
            }
            notifyChange(isSyncAdapter)
        }
    }

    private fun notifyChange(changedBySyncAdapter: Boolean) {
        val context = context ?: return
        // local changes have to be uploaded, while remote changes have to be mirrored into the app's database
        TasksContract.notifyChange(context, syncToNetwork = !changedBySyncAdapter)
        if (changedBySyncAdapter) {
            DavTasksHelper.schedulePull(context)
        }
    }

    /**
     * Resolves the RELATED_ID of relation properties from their RELATED_UID and keeps tasks.parent_id in sync with
     * the relations, like OpenTasks does.
     */
    private fun updateRelations(db: SQLiteDatabase) {
        db.execSQL(
            """
            UPDATE $TABLE_PROPERTIES SET ${Relation.RELATED_ID} = (
                SELECT related.${TasksContract._ID} FROM $TABLE_TASKS related, $TABLE_TASKS owner
                WHERE owner.${TasksContract._ID} = $TABLE_PROPERTIES.${Properties.TASK_ID}
                AND related.${Tasks.LIST_ID} = owner.${Tasks.LIST_ID}
                AND related.${TasksContract._UID} = $TABLE_PROPERTIES.${Relation.RELATED_UID}
                LIMIT 1
            )
            WHERE ${Properties.MIMETYPE} = ? AND ${Relation.RELATED_UID} IS NOT NULL
            """.trimIndent(),
            arrayOf(Relation.CONTENT_ITEM_TYPE)
        )

        db.execSQL(
            """
            UPDATE $TABLE_TASKS SET ${Tasks.PARENT_ID} = COALESCE(
                (
                    SELECT p.${Relation.RELATED_ID} FROM $TABLE_PROPERTIES p
                    WHERE p.${Properties.TASK_ID} = $TABLE_TASKS.${TasksContract._ID}
                    AND p.${Properties.MIMETYPE} = ?1 AND p.${Relation.RELATED_TYPE} = ${Relation.RELTYPE_PARENT}
                    AND p.${Relation.RELATED_ID} IS NOT NULL
                    LIMIT 1
                ),
                (
                    SELECT p.${Properties.TASK_ID} FROM $TABLE_PROPERTIES p
                    WHERE p.${Relation.RELATED_ID} = $TABLE_TASKS.${TasksContract._ID}
                    AND p.${Properties.MIMETYPE} = ?1 AND p.${Relation.RELATED_TYPE} = ${Relation.RELTYPE_CHILD}
                    LIMIT 1
                )
            )
            WHERE ${Tasks.PARENT_ID} IS NOT NULL OR ${TasksContract._ID} IN (
                SELECT ${Properties.TASK_ID} FROM $TABLE_PROPERTIES WHERE ${Properties.MIMETYPE} = ?1
                UNION SELECT ${Relation.RELATED_ID} FROM $TABLE_PROPERTIES WHERE ${Properties.MIMETYPE} = ?1
            )
            """.trimIndent(),
            arrayOf(Relation.CONTENT_ITEM_TYPE)
        )
    }

    private fun getPropertyTaskIds(db: SQLiteDatabase, selection: String?, args: Array<String>): List<Long> {
        val ids = ArrayList<Long>()
        db.query(TABLE_PROPERTIES, arrayOf(Properties.TASK_ID), selection, args, null, null, null).use {
            while (it.moveToNext()) {
                ids.add(it.getLong(0))
            }
        }
        return ids
    }

    private fun markTasksDirty(db: SQLiteDatabase, taskIds: List<Long?>) {
        taskIds.filterNotNull().distinct().forEach {
            val values = ContentValues().apply { put(TasksContract._DIRTY, 1) }
            db.update(TABLE_TASKS, values, "${TasksContract._ID} = ?", arrayOf(it.toString()))
        }
    }

    private fun addIdFilter(uri: Uri, where: ArrayList<String>, args: ArrayList<String>) {
        val column = when (uriMatcher.match(uri)) {
            LIST_ID -> "$TABLE_LISTS.${TasksContract._ID}"
            TASK_ID -> "$TABLE_TASKS.${TasksContract._ID}"
            PROPERTY_ID -> "$TABLE_PROPERTIES.${Properties.PROPERTY_ID}"
            else -> return
        }
        where.add("$column = ?")
        args.add(ContentUris.parseId(uri).toString())
    }

    // sync adapters only get to see the lists and tasks of their own account
    private fun addListAccountFilter(
        uri: Uri,
        where: ArrayList<String>,
        args: ArrayList<String>,
        listIdColumn: String
    ) {
        val accountName = uri.getQueryParameter(TasksContract.ACCOUNT_NAME) ?: return
        val accountType = uri.getQueryParameter(TasksContract.ACCOUNT_TYPE) ?: return
        val table = if (listIdColumn == TasksContract._ID) TABLE_LISTS else TABLE_TASKS
        where.add(
            "$table.$listIdColumn IN (SELECT ${TasksContract._ID} FROM $TABLE_LISTS " +
                "WHERE ${TasksContract.ACCOUNT_NAME} = ? AND ${TasksContract.ACCOUNT_TYPE} = ?)"
        )
        args.add(accountName)
        args.add(accountType)
    }

    private fun combineSelection(where: List<String>, selection: String?): String? {
        val parts = where + listOfNotNull(selection?.takeIf { it.isNotBlank() })
        return if (parts.isEmpty()) null else parts.joinToString(" AND ") { "($it)" }
    }

    private fun filterValues(values: ContentValues?, allowedColumns: Set<String>): ContentValues {
        val filtered = ContentValues()
        values?.keySet()?.filter { it in allowedColumns }?.forEach { key ->
            when (val value = values.get(key)) {
                null -> filtered.putNull(key)
                is String -> filtered.put(key, value)
                is Long -> filtered.put(key, value)
                is Int -> filtered.put(key, value)
                is Short -> filtered.put(key, value)
                is Byte -> filtered.put(key, value)
                is Boolean -> filtered.put(key, value)
                is Double -> filtered.put(key, value)
                is Float -> filtered.put(key, value)
                is ByteArray -> filtered.put(key, value)
                else -> filtered.put(key, value.toString())
            }
        }
        return filtered
    }
}

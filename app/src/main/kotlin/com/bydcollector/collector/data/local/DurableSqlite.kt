package com.bydcollector.collector.data.local

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build

/** A handoff may leave this process only after its owning database has synced. */
internal object DurableSqlite {
    fun diagnosticLines(db: SQLiteDatabase, owner: String): List<String> = listOf(
        "${owner}_sqlite_version=${db.version}",
        "${owner}_sqlite_journal_mode=" + db.rawQuery("PRAGMA journal_mode", null).use { check(it.moveToFirst()); it.getString(0) },
        "${owner}_sqlite_synchronous=" + db.rawQuery("PRAGMA synchronous", null).use { check(it.moveToFirst()); it.getInt(0) }
    )

    fun configure(helper: SQLiteOpenHelper) {
        if (Build.VERSION.SDK_INT >= 28) {
            helper.setOpenParams(SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                .setSynchronousMode("FULL").build())
        } else {
            helper.setWriteAheadLoggingEnabled(true)
        }
    }

    fun configureConnection(db: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT < 28) db.execSQL("PRAGMA synchronous=FULL")
    }

    fun barrier(db: SQLiteDatabase) {
        check(!db.inTransaction()) { "Durability barrier requires a completed transaction" }
        if (Build.VERSION.SDK_INT >= 28) return
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0 && cursor.getLong(1) == cursor.getLong(2)) {
                "SQLite WAL durability checkpoint did not complete"
            }
        }
    }
}

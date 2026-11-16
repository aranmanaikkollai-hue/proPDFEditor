package com.propdfeditor.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/**
 * Helpers for the Room migration and schema tests.
 *
 * The exported schema JSON files are packaged as androidTest assets (see app/build.gradle) at
 * <database class name>/<version>.json, the same layout MigrationTestHelper uses.
 */
object RoomSchemaTestSupport {

    class EntitySql(val table: String, val createSql: String, val indexSql: List<String>)

    class Schema(val version: Int, val identityHash: String, val entities: List<EntitySql>) {
        fun entity(table: String): EntitySql =
            entities.firstOrNull { it.table == table } ?: error("table not in exported schema: " + table)
    }

    fun load(dbClass: Class<*>, version: Int): Schema {
        val path = dbClass.canonicalName + "/" + version + ".json"
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open(path)
            .bufferedReader().use { it.readText() }
        val db = JSONObject(text).getJSONObject("database")
        val entities = ArrayList<EntitySql>()
        val array = db.getJSONArray("entities")
        for (i in 0 until array.length()) {
            val e = array.getJSONObject(i)
            val table = e.getString("tableName")
            val indexSql = ArrayList<String>()
            val indices = e.optJSONArray("indices")
            if (indices != null) {
                for (j in 0 until indices.length()) {
                    indexSql.add(fill(indices.getJSONObject(j).getString("createSql"), table))
                }
            }
            entities.add(EntitySql(table, fill(e.getString("createSql"), table), indexSql))
        }
        return Schema(db.getInt("version"), db.getString("identityHash"), entities)
    }

    private fun fill(sql: String, table: String): String = sql.replace("\${TABLE_NAME}", table)

    /**
     * Returns a CREATE TABLE statement without the given columns. Splits the column list at top level
     * commas, so PRIMARY KEY(...) and FOREIGN KEY(...) clauses stay intact.
     */
    fun withoutColumns(createSql: String, columns: Set<String>): String {
        val open = createSql.indexOf('(')
        val close = createSql.lastIndexOf(')')
        require(open in 0 until close) { "unexpected CREATE TABLE: " + createSql }
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in createSql.substring(open + 1, close)) {
            when {
                ch == '(' -> { depth++; current.append(ch) }
                ch == ')' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> { parts.add(current.toString().trim()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        parts.add(current.toString().trim())
        val kept = parts.filter { part -> columns.none { part.startsWith("`" + it + "`") } }
        require(kept.size == parts.size - columns.size) { "column not found while removing " + columns }
        return createSql.substring(0, open + 1) + kept.joinToString(", ") + ")"
    }

    /** Creates a database file at [version] by running [statements]; room_master_table is not created. */
    fun createLegacyDatabase(name: String, version: Int, statements: List<String>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (sql in statements) db.execSQL(sql)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase // opens the file, which runs onCreate
        helper.close()
    }

    fun deleteDatabase(name: String) {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    fun tableNames(db: SupportSQLiteDatabase): Set<String> {
        val result = HashSet<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use {
            while (it.moveToNext()) result.add(it.getString(0))
        }
        return result
    }

    fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val result = HashSet<String>()
        db.query("PRAGMA table_info(" + table + ")").use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) result.add(it.getString(nameIndex))
        }
        return result
    }
}

package androidx.media3.database;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteStatement;

public class DatabaseUtil {

  private DatabaseUtil() {}

  /** Returns whether the table exists in the database. */
  @UnstableApi
  public static boolean tableExists(SupportSQLiteDatabase database, String tableName) {
    long count =
        queryNumEntries(database, "sqlite_master", "tbl_name = ?", new String[] {tableName});
    return count > 0;
  }

  /**
   * copied from the android framework {@link
   * android.database.DatabaseUtils#queryNumEntries(SQLiteDatabase, String, String)}
   */
  public static long queryNumEntries(
      SupportSQLiteDatabase db, String table, String selection, String[] selectionArgs) {
    String s = (!android.text.TextUtils.isEmpty(selection)) ? " where " + selection : "";
    return longForQuery(db, "select count(*) from " + table + s, selectionArgs);
  }

  /**
   * Utility method to run the query on the db and return the value in the first column of the first
   * row.
   *
   * <p>copied from {@link android.database.DatabaseUtils#longForQuery(SQLiteDatabase, String,
   * String[])}
   */
  public static long longForQuery(SupportSQLiteDatabase db, String query, String[] selectionArgs) {
    SupportSQLiteStatement prog = db.compileStatement(query);
    try {
      return longForQuery(prog, selectionArgs);
    } finally {
      androidx.media3.common.util.Util.closeQuietly(prog);
    }
  }

  /** copied from {@link android.database.DatabaseUtils#longForQuery(SQLiteStatement, String[])} */
  public static long longForQuery(SupportSQLiteStatement prog, String[] selectionArgs) {
    bindAllArgsAsStrings(prog, selectionArgs);
    return prog.simpleQueryForLong();
  }

  /**
   * Equivalent to {@link android.database.sqlite.SQLiteStatement#bindAllArgsAsStrings(String[])}
   */
  public static void bindAllArgsAsStrings(
      SupportSQLiteStatement prog, @Nullable String[] bindArgs) {
    if (bindArgs != null) {
      for (int i = bindArgs.length; i != 0; i--) {
        prog.bindString(i, bindArgs[i - 1]);
      }
    }
  }
}

package androidx.media3.database;

import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;

/**
 * @deprecated Use {@link SupportDatabaseProvider} instead.
 */
@Deprecated
public class DefaultDatabaseProvider extends SupportSQLiteOpenHelper.Callback
    implements DatabaseProvider {

  private final SupportSQLiteOpenHelper helper;

  private SupportSQLiteOpenHelper createHelper(Context context, SQLiteOpenHelper openHelper) {
    SupportSQLiteOpenHelper.Configuration cfg =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(openHelper.getDatabaseName())
            .callback(this)
            .build();
    return new FrameworkSQLiteOpenHelperFactory().create(cfg);
  }

  public DefaultDatabaseProvider(Context context, SQLiteOpenHelper openHelper) {
    super();
    this.helper = createHelper(context, openHelper);
  }

  @Override
  public SupportSQLiteDatabase getWritableDatabase() {
    return helper.getWritableDatabase();
  }

  @Override
  public void onCreate(SupportSQLiteDatabase supportSQLiteDatabase) {
    // Features create their own tables.
  }

  @Override
  public void onUpgrade(SupportSQLiteDatabase supportSQLiteDatabase, int i, int i1) {
    // Features handle their own upgrades.
  }

  @Override
  public SupportSQLiteDatabase getReadableDatabase() {
    return helper.getReadableDatabase();
  }
}

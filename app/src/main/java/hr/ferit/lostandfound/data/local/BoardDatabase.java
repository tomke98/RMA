package hr.ferit.lostandfound.data.local;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * The project's first Room database (spec-3-4): a single-table, single-purpose
 * read-through mirror of the Board's open reports (AD-13). Named after its one
 * table since nothing else lives here.
 */
@Database(entities = {BoardReportEntity.class}, version = 1, exportSchema = false)
public abstract class BoardDatabase extends RoomDatabase {

    private static final String DB_NAME = "board.db";

    private static volatile BoardDatabase instance;

    public abstract BoardReportDao boardReportDao();

    @NonNull
    public static BoardDatabase get(@NonNull Context context) {
        if (instance == null) {
            synchronized (BoardDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(), BoardDatabase.class, DB_NAME)
                            .build();
                }
            }
        }
        return instance;
    }
}

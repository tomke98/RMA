package hr.ferit.lostandfound.data.local;

import java.util.List;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

/**
 * The four operations {@code BoardRepository}'s mirror, the offline label, and
 * sign-out teardown actually need (AD-13 read-only-cache scope, spec-3-4) —
 * nothing else touches {@code board_report}.
 */
@Dao
public abstract class BoardReportDao {

    @Query("DELETE FROM board_report")
    abstract void deleteAllInternal();

    @Insert
    abstract void insertAll(List<BoardReportEntity> rows);

    /** Full replace, never an append: every mirror wipes the prior rows first. */
    @Transaction
    public void replaceAll(List<BoardReportEntity> rows) {
        deleteAllInternal();
        insertAll(rows);
    }

    @Query("SELECT * FROM board_report ORDER BY createdAtMillis DESC")
    public abstract List<BoardReportEntity> getAllOrderedByCreatedAtDesc();

    @Query("SELECT MAX(syncedAt) FROM board_report")
    public abstract Long getMaxSyncedAt();

    /** Sign-out teardown only (AD-17) — never called from the mirror step. */
    @Query("DELETE FROM board_report")
    public abstract void deleteAll();
}

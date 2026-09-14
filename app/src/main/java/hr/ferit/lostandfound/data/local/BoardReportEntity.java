package hr.ferit.lostandfound.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room read-through mirror of one open {@code reports} document (AD-13,
 * spec-3-4). Populated exclusively by {@code BoardRepository}'s snapshot
 * listener via a full delete-all + insert-all {@code @Transaction} — never
 * written to independently (AD-3/AD-17). No {@code status} column: the
 * Firestore {@code whereEqualTo("status","open")} query already filters what
 * gets mirrored here, so every row in this table is implicitly open.
 */
@Entity(tableName = "board_report")
public class BoardReportEntity {

    @PrimaryKey
    @NonNull
    public String id;

    public String type;
    public String category;
    public String description;
    public String photoUrl;
    public double lat;
    public double lng;
    public String locationLabel;
    public String reporterId;
    public String reporterPhone;
    public long createdAtMillis;
    public long syncedAt;

    public BoardReportEntity() {
        this.id = "";
    }
}

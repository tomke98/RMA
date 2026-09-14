package hr.ferit.lostandfound.util;

import android.content.res.Resources;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Category;

/**
 * Shared report display formatting, extracted from {@code ui.detail.ReportDetailActivity}
 * (Story 3.1) so {@code ui.board.BoardListAdapter} (Story 3.3) renders category
 * labels and relative ages identically instead of duplicating the logic.
 */
public final class ReportDisplay {

    private ReportDisplay() {
    }

    /** {@code report.getCategory()} order-matches {@code R.array.kategorije_nazivi}
     * (AD-16); an unrecognised/null value (unreachable via this app's own
     * writers) falls back to the "Ostalo" label rather than crashing. */
    @NonNull
    public static String categoryLabel(@NonNull Resources resources, @Nullable String categoryName) {
        String[] labels = resources.getStringArray(R.array.kategorije_nazivi);
        try {
            return labels[Category.valueOf(categoryName).ordinal()];
        } catch (IllegalArgumentException | NullPointerException e) {
            return labels[Category.OTHER.ordinal()];
        }
    }

    @NonNull
    public static CharSequence relativeAge(@Nullable Timestamp createdAt) {
        if (createdAt == null) {
            return "";
        }
        return DateUtils.getRelativeTimeSpanString(
                createdAt.toDate().getTime(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    }

    /** Relative-time rendering of {@code max(syncedAt)} for the Board's offline
     * "Zadnje ažurirano" label (Story 3.4); mirrors {@link #relativeAge}'s
     * formatting so both timestamps read consistently. */
    @NonNull
    public static CharSequence relativeSyncTime(long syncedAtMillis) {
        return DateUtils.getRelativeTimeSpanString(
                syncedAtMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    }
}

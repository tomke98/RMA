package hr.ferit.lostandfound.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.EnumSet;
import java.util.Set;

import hr.ferit.lostandfound.data.model.Category;

/**
 * Small wrapper around a dedicated {@link SharedPreferences} file for the
 * "Praćene kategorije" screen (Story 4.1). Every account's followed set lives
 * under its own {@code followed_categories_<uid>} key so switching accounts on
 * the same device never shows or leaks another account's selection.
 *
 * <p>Pure local read/write, no network/Firestore access (this story doesn't
 * consume the Board listener — that is Story 4.2).
 */
public class FollowedCategoriesStore {

    private static final String PREFS_NAME = "followed_categories";
    private static final String KEY_PREFIX = "followed_categories_";
    private static final String SEPARATOR = ",";

    private final Context appContext;

    public FollowedCategoriesStore(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Returns the persisted followed set for {@code uid}, empty if the key is
     * absent. Any token that doesn't match a {@link Category#name()} (e.g. from
     * a future enum rename) is silently dropped rather than crashing (AD-14).
     */
    @NonNull
    public Set<Category> getFollowed(@NonNull String uid) {
        Set<Category> result = EnumSet.noneOf(Category.class);
        String stored = prefs().getString(key(uid), null);
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        for (String token : stored.split(SEPARATOR)) {
            try {
                result.add(Category.valueOf(token));
            } catch (IllegalArgumentException e) {
                // Malformed/unknown token — drop it and keep going (fail-soft, AD-14).
            }
        }
        return result;
    }

    /** Persists {@code categories} as the new followed set for {@code uid}, immediately. */
    public void setFollowed(@NonNull String uid, @NonNull Set<Category> categories) {
        StringBuilder builder = new StringBuilder();
        for (Category category : categories) {
            if (builder.length() > 0) {
                builder.append(SEPARATOR);
            }
            builder.append(category.name());
        }
        prefs().edit().putString(key(uid), builder.toString()).apply();
    }

    @NonNull
    private static String key(@NonNull String uid) {
        return KEY_PREFIX + uid;
    }

    @NonNull
    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}

package hr.ferit.lostandfound.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.EnumSet;
import java.util.Set;

import hr.ferit.lostandfound.data.model.Category;

/**
 * Covers {@link FollowedCategoriesStore}'s I/O matrix (spec-4-1): empty by
 * default, round-trip persist/read, per-UID isolation, and the
 * corrupt-token-is-dropped fail-soft edge case (AD-14). First SharedPreferences
 * code in the project, hence its own coverage.
 */
@RunWith(RobolectricTestRunner.class)
public class FollowedCategoriesStoreTest {

    private final Application application = RuntimeEnvironment.getApplication();
    private final FollowedCategoriesStore store = new FollowedCategoriesStore(application);

    @Test
    public void getFollowed_noPriorSelection_returnsEmptySet() {
        Set<Category> followed = store.getFollowed("uid-a");

        assertTrue(followed.isEmpty());
    }

    @Test
    public void setFollowed_thenGetFollowed_roundTripsExactSet() {
        Set<Category> toPersist = EnumSet.of(Category.KEYS, Category.WALLET, Category.UMBRELLA);

        store.setFollowed("uid-a", toPersist);
        Set<Category> readBack = store.getFollowed("uid-a");

        assertEquals(toPersist, readBack);
    }

    @Test
    public void toggle_addThenRemoveOneCategory_reflectsImmediatelyWithoutAffectingOthers() {
        // Mirrors FollowedCategoriesActivity#onToggle: read current set, mutate,
        // write back immediately -- no explicit save step.
        Set<Category> afterCheckingTwo = store.getFollowed("uid-a");
        afterCheckingTwo.add(Category.KEYS);
        afterCheckingTwo.add(Category.WALLET);
        store.setFollowed("uid-a", afterCheckingTwo);

        assertEquals(EnumSet.of(Category.KEYS, Category.WALLET), store.getFollowed("uid-a"));

        Set<Category> afterUncheckingOne = store.getFollowed("uid-a");
        afterUncheckingOne.remove(Category.KEYS);
        store.setFollowed("uid-a", afterUncheckingOne);

        assertEquals(EnumSet.of(Category.WALLET), store.getFollowed("uid-a"));
    }

    @Test
    public void setFollowed_twoDifferentUids_neitherSeesTheOthersSet() {
        store.setFollowed("uid-a", EnumSet.of(Category.KEYS));
        store.setFollowed("uid-b", EnumSet.of(Category.PHONE, Category.BAG));

        assertEquals(EnumSet.of(Category.KEYS), store.getFollowed("uid-a"));
        assertEquals(EnumSet.of(Category.PHONE, Category.BAG), store.getFollowed("uid-b"));
    }

    @Test
    public void getFollowed_corruptTokenAmongValidOnes_dropsCorruptTokenOnly() {
        application.getSharedPreferences("followed_categories", 0)
                .edit()
                .putString("followed_categories_uid-a", "KEYS,NOT_A_REAL_CATEGORY,WALLET")
                .apply();

        Set<Category> followed = store.getFollowed("uid-a");

        assertEquals(EnumSet.of(Category.KEYS, Category.WALLET), followed);
    }

    @Test
    public void getFollowed_onlyCorruptTokenStored_doesNotCrashAndReturnsEmptySet() {
        SharedPreferences.Editor editor = application
                .getSharedPreferences("followed_categories", 0)
                .edit();
        editor.putString("followed_categories_uid-a", "TOTALLY_UNKNOWN");
        editor.apply();

        Set<Category> followed = store.getFollowed("uid-a");

        assertTrue(followed.isEmpty());
    }
}

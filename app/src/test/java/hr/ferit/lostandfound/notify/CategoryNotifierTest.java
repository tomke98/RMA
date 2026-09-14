package hr.ferit.lostandfound.notify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import hr.ferit.lostandfound.data.model.Category;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.data.repo.BoardRepository;
import hr.ferit.lostandfound.util.FollowedCategoriesStore;

/**
 * Covers {@link CategoryNotifier}'s full I/O &amp; Edge-Case Matrix
 * (spec-4-2): first-snapshot-seeds-only, followed+other-user fires,
 * own-report doesn't, unfollowed category doesn't, duplicate id doesn't
 * re-fire, and a malformed category never crashes. First notification-logic
 * code in the project, hence its own full coverage.
 *
 * <p>Uses a {@link FakeBoardRepository} subclass (built with {@code null}
 * Firebase/Room handles, like {@code BoardViewModelTest}'s equivalent) rather
 * than Mockito, matching this repo's existing convention. {@link
 * RecordingCategoryNotifier} overrides the one method that touches real
 * Android resources ({@link CategoryNotifier#postNotification}) with a
 * call-recording stub instead: per {@code BoardListAdapterTest}'s class doc,
 * this project's unit tests run without {@code includeAndroidResources}
 * (turning that on breaks the Firebase-dependent tests elsewhere in the suite
 * by pulling in the real manifest's auto-init content provider), so {@code
 * ReportDisplay.categoryLabel}'s underlying resource lookup does not resolve
 * here -- the matching/id-tracking logic under test does not touch resources
 * at all. Robolectric is only needed because {@code FollowedCategoriesStore}
 * uses real {@code SharedPreferences}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class CategoryNotifierTest {

    private static final String UID = "uid-self";
    private static final String OTHER_UID = "uid-other";

    @Test
    public void onAddedReports_firstCallback_seedsOnlyAndPostsNoNotification() {
        FakeBoardRepository repo = new FakeBoardRepository();
        RecordingCategoryNotifier notifier = newNotifier(repo, EnumSet.of(Category.KEYS));
        notifier.start();

        Report existing = report("r1", Category.KEYS.name(), OTHER_UID);
        notifier.onAddedReports(Collections.singletonList(existing));

        assertTrue(notifier.isSeeded());
        assertTrue(notifier.hasSeen("r1"));
        assertEquals(0, notifier.postedReportIds.size());
    }

    @Test
    public void onAddedReports_followedCategoryOtherUser_afterSeed_postsExactlyOneNotification() {
        FakeBoardRepository repo = new FakeBoardRepository();
        RecordingCategoryNotifier notifier = newNotifier(repo, EnumSet.of(Category.KEYS));
        notifier.start();

        notifier.onAddedReports(Collections.<Report>emptyList()); // seed pass (first snapshot)
        notifier.onAddedReports(Collections.singletonList(report("r2", Category.KEYS.name(), OTHER_UID)));

        assertEquals(1, notifier.postedReportIds.size());
        assertEquals("r2", notifier.postedReportIds.get(0));
        assertTrue(notifier.hasSeen("r2"));
    }

    @Test
    public void onAddedReports_ownReport_afterSeed_noNotificationButIdSeen() {
        FakeBoardRepository repo = new FakeBoardRepository();
        RecordingCategoryNotifier notifier = newNotifier(repo, EnumSet.of(Category.KEYS));
        notifier.start();

        notifier.onAddedReports(Collections.<Report>emptyList());
        notifier.onAddedReports(Collections.singletonList(report("r3", Category.KEYS.name(), UID)));

        assertEquals(0, notifier.postedReportIds.size());
        assertTrue(notifier.hasSeen("r3"));
    }

    @Test
    public void onAddedReports_categoryNotFollowed_afterSeed_noNotificationButIdSeen() {
        FakeBoardRepository repo = new FakeBoardRepository();
        RecordingCategoryNotifier notifier = newNotifier(repo, EnumSet.of(Category.WALLET));
        notifier.start();

        notifier.onAddedReports(Collections.<Report>emptyList());
        notifier.onAddedReports(Collections.singletonList(report("r4", Category.KEYS.name(), OTHER_UID)));

        assertEquals(0, notifier.postedReportIds.size());
        assertTrue(notifier.hasSeen("r4"));
    }

    @Test
    public void onAddedReports_duplicateAlreadySeenId_doesNotReNotify() {
        FakeBoardRepository repo = new FakeBoardRepository();
        RecordingCategoryNotifier notifier = newNotifier(repo, EnumSet.of(Category.KEYS));
        notifier.start();

        notifier.onAddedReports(Collections.<Report>emptyList());
        Report matching = report("r5", Category.KEYS.name(), OTHER_UID);
        notifier.onAddedReports(Collections.singletonList(matching));
        assertEquals(1, notifier.postedReportIds.size());

        // Same id redelivered (e.g. Firestore local-cache replay).
        notifier.onAddedReports(Collections.singletonList(matching));

        assertEquals(1, notifier.postedReportIds.size());
    }

    @Test
    public void onAddedReports_malformedCategory_treatedAsNotFollowedAndNeverCrashes() {
        FakeBoardRepository repo = new FakeBoardRepository();
        RecordingCategoryNotifier notifier = newNotifier(repo, EnumSet.of(Category.KEYS));
        notifier.start();

        notifier.onAddedReports(Collections.<Report>emptyList());
        notifier.onAddedReports(Collections.singletonList(report("r6", "NOT_A_REAL_CATEGORY", OTHER_UID)));

        assertEquals(0, notifier.postedReportIds.size());
        assertTrue(notifier.hasSeen("r6"));
    }

    @Test
    public void onAddedReports_nullUid_afterSeed_noNotificationButIdSeen() {
        // ServiceLocator.categoryNotifier() can construct with a null uid
        // (no signed-in user); matches() must short-circuit without crashing.
        FakeBoardRepository repo = new FakeBoardRepository();
        FollowedCategoriesStore store = new FollowedCategoriesStore(RuntimeEnvironment.getApplication());
        RecordingCategoryNotifier notifier =
                new RecordingCategoryNotifier(RuntimeEnvironment.getApplication(), repo, store, null);
        notifier.start();

        notifier.onAddedReports(Collections.<Report>emptyList());
        notifier.onAddedReports(Collections.singletonList(report("r8", Category.KEYS.name(), OTHER_UID)));

        assertEquals(0, notifier.postedReportIds.size());
        assertTrue(notifier.hasSeen("r8"));
    }

    @Test
    public void start_subscribesToBoardRepositoryAddedReportsHook() {
        FakeBoardRepository repo = new FakeBoardRepository();
        RecordingCategoryNotifier notifier = newNotifier(repo, EnumSet.noneOf(Category.class));

        notifier.start();

        assertSame(notifier, repo.listener);
    }

    @Test
    public void stop_unsubscribesAndClearsSeenState() {
        FakeBoardRepository repo = new FakeBoardRepository();
        RecordingCategoryNotifier notifier = newNotifier(repo, EnumSet.of(Category.KEYS));
        notifier.start();
        notifier.onAddedReports(Collections.singletonList(report("r7", Category.KEYS.name(), OTHER_UID)));

        notifier.stop();

        assertFalse(notifier.isSeeded());
        assertFalse(notifier.hasSeen("r7"));
        assertNull(repo.listener);
    }

    @NonNull
    private static RecordingCategoryNotifier newNotifier(
            @NonNull FakeBoardRepository repo, @NonNull Set<Category> followed) {
        FollowedCategoriesStore store = new FollowedCategoriesStore(RuntimeEnvironment.getApplication());
        store.setFollowed(UID, followed);
        return new RecordingCategoryNotifier(RuntimeEnvironment.getApplication(), repo, store, UID);
    }

    private static Report report(@NonNull String id, @NonNull String category, @NonNull String reporterId) {
        Report report = new Report();
        report.setId(id);
        report.setType("lost");
        report.setCategory(category);
        report.setDescription("desc");
        report.setLat(45.5d);
        report.setLng(18.6d);
        report.setLocationLabel("label");
        report.setReporterId(reporterId);
        report.setReporterPhone("0911234567");
        return report;
    }

    /** Overrides the one resource-touching method; see the class doc above. */
    private static final class RecordingCategoryNotifier extends CategoryNotifier {
        final List<String> postedReportIds = new ArrayList<>();

        RecordingCategoryNotifier(@NonNull android.content.Context context,
                                   @NonNull BoardRepository boardRepository,
                                   @NonNull FollowedCategoriesStore followedCategoriesStore,
                                   @Nullable String uid) {
            super(context, boardRepository, followedCategoriesStore, uid);
        }

        @Override
        void postNotification(@NonNull Report report) {
            postedReportIds.add(report.getId());
        }
    }

    /** Firebase-free stand-in for {@link BoardRepository}, per the codebase's
     * subclass-over-Mockito convention (no Mockito in this repo); also records
     * the last registered {@link BoardRepository.AddedReportsListener} so
     * start()/stop() subscription wiring can be asserted directly. */
    private static final class FakeBoardRepository extends BoardRepository {
        private final MutableLiveData<Resource<List<Report>>> resource = new MutableLiveData<>();
        AddedReportsListener listener;

        FakeBoardRepository() {
            super(null, null, RuntimeEnvironment.getApplication());
        }

        @NonNull
        @Override
        public LiveData<Resource<List<Report>>> resource() {
            return resource;
        }

        @Override
        public void setAddedReportsListener(AddedReportsListener listener) {
            this.listener = listener;
        }
    }
}

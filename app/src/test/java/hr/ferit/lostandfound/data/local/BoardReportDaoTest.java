package hr.ferit.lostandfound.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import androidx.room.Room;

/**
 * First Room coverage in the project (spec-3-4): {@code replaceAll} must fully
 * replace {@code board_report}, never append, and {@code getMaxSyncedAt} must
 * reflect the latest mirror. Uses {@link Room#inMemoryDatabaseBuilder} so no
 * file-system database is touched.
 */
@RunWith(RobolectricTestRunner.class)
public class BoardReportDaoTest {

    private BoardDatabase database;
    private BoardReportDao dao;

    @Before
    public void setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BoardDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = database.boardReportDao();
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void replaceAll_onEmptyTable_insertsAllRows() {
        dao.replaceAll(Arrays.asList(entity("r1", 100L, 1_000L), entity("r2", 200L, 1_000L)));

        List<BoardReportEntity> rows = dao.getAllOrderedByCreatedAtDesc();
        assertEquals(2, rows.size());
    }

    @Test
    public void replaceAll_withExistingRows_fullyReplacesNotAppends() {
        dao.replaceAll(Collections.singletonList(entity("old", 100L, 1_000L)));

        dao.replaceAll(Arrays.asList(entity("new1", 200L, 2_000L), entity("new2", 300L, 2_000L)));

        List<BoardReportEntity> rows = dao.getAllOrderedByCreatedAtDesc();
        assertEquals(2, rows.size());
        assertEquals("new2", rows.get(0).id);
        assertEquals("new1", rows.get(1).id);
    }

    @Test
    public void getAllOrderedByCreatedAtDesc_ordersNewestFirst() {
        dao.replaceAll(Arrays.asList(
                entity("oldest", 100L, 1_000L),
                entity("newest", 300L, 1_000L),
                entity("middle", 200L, 1_000L)));

        List<BoardReportEntity> rows = dao.getAllOrderedByCreatedAtDesc();

        assertEquals("newest", rows.get(0).id);
        assertEquals("middle", rows.get(1).id);
        assertEquals("oldest", rows.get(2).id);
    }

    @Test
    public void getMaxSyncedAt_onEmptyTable_returnsNull() {
        assertNull(dao.getMaxSyncedAt());
    }

    @Test
    public void getMaxSyncedAt_reflectsLatestMirror() {
        dao.replaceAll(Collections.singletonList(entity("r1", 100L, 1_000L)));
        dao.replaceAll(Collections.singletonList(entity("r1", 100L, 5_000L)));

        assertEquals(Long.valueOf(5_000L), dao.getMaxSyncedAt());
    }

    @Test
    public void deleteAll_onSignOut_emptiesTheTable() {
        dao.replaceAll(Arrays.asList(entity("r1", 100L, 1_000L), entity("r2", 200L, 1_000L)));

        dao.deleteAll();

        assertEquals(0, dao.getAllOrderedByCreatedAtDesc().size());
        assertNull(dao.getMaxSyncedAt());
    }

    private static BoardReportEntity entity(String id, long createdAtMillis, long syncedAt) {
        BoardReportEntity entity = new BoardReportEntity();
        entity.id = id;
        entity.type = "lost";
        entity.category = "OTHER";
        entity.description = "desc";
        entity.photoUrl = null;
        entity.lat = 45.5d;
        entity.lng = 18.6d;
        entity.locationLabel = "label";
        entity.reporterId = "u1";
        entity.reporterPhone = "0911234567";
        entity.createdAtMillis = createdAtMillis;
        entity.syncedAt = syncedAt;
        return entity;
    }
}

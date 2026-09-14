package hr.ferit.lostandfound.ui.board;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.google.android.gms.maps.model.LatLng;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.ui.report.CreateReportActivity;

/**
 * Verifies {@link BoardListAdapter} against the Story 3.3 I/O matrix: distance
 * shown when a fix is available, hidden (never "0 m") when it is not, and that
 * {@link BoardListAdapter#submitReports} on an already-bound adapter simply
 * updates the row count in place — the adapter never touches Firestore itself,
 * so there is no second read to guard against.
 *
 * <p>The distance checks call {@link BoardListAdapter.RowHolder#showsDistance}
 * directly — the exact boolean {@code RowHolder.bindDistance} gates the row's
 * visibility on — rather than inflating the real row layout: this project's
 * unit tests run without {@code includeAndroidResources} (turning that on
 * breaks the Firebase-dependent tests elsewhere in the suite by pulling in the
 * real manifest's auto-init content provider), so neither the row layout nor
 * its string resources resolve in this test environment. Runs under
 * Robolectric only because {@code RecyclerView.Adapter}'s own internal
 * observable list needs a real Android environment to initialise correctly;
 * no app resource is touched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class BoardListAdapterTest {

    @Test
    public void showsDistance_noCurrentFix_isFalse() {
        assertFalse(BoardListAdapter.RowHolder.showsDistance(null));
    }

    @Test
    public void showsDistance_currentFixAvailable_isTrue() {
        LatLng fix = new LatLng(45.5550d, 18.6955d);

        // Per the matrix: the decision does not depend on the individual
        // report, only on whether a fix is available.
        assertTrue(BoardListAdapter.RowHolder.showsDistance(fix));
    }

    @Test
    public void submitReports_onAlreadyBoundAdapter_updatesRowCountInPlace() {
        BoardListAdapter adapter = new BoardListAdapter(id -> { });

        adapter.submitReports(Arrays.asList(
                report("r1", 45.56, 18.70),
                report("r2", 45.60, 18.75)));
        assertEquals(2, adapter.getItemCount());

        // Re-submission from the same still-live BoardViewModel stream (no
        // Firestore call happens inside the adapter to guard against here).
        List<Report> singleReport = Collections.singletonList(report("r3", 45.50, 18.60));
        adapter.submitReports(singleReport);
        assertEquals(1, adapter.getItemCount());
    }

    private Report report(String id, double lat, double lng) {
        Report report = new Report();
        report.setId(id);
        report.setType(CreateReportActivity.REPORT_TYPE_LOST);
        report.setStatus("open");
        report.setCategory("KEYS");
        report.setDescription("desc");
        report.setPhotoUrl(null);
        report.setLat(lat);
        report.setLng(lng);
        report.setLocationLabel("Osijek");
        report.setReporterId("u1");
        report.setReporterPhone("099 000 0000");
        return report;
    }
}

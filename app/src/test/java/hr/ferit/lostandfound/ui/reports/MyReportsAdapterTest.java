package hr.ferit.lostandfound.ui.reports;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.repo.ReportRepository;

/**
 * Verifies {@link MyReportsAdapter} against the Story 4.3 I/O matrix's
 * open-vs-closed row branch (button visibility / status label), the one piece
 * of {@code RowHolder.bind} that would otherwise have no coverage.
 *
 * <p>{@link MyReportsAdapter.RowHolder#isOpen} is called directly -- the exact
 * boolean {@code RowHolder.bind} gates the "Zatvori" button's visibility and
 * status label on -- rather than inflating the real row layout: this project's
 * unit tests run without {@code includeAndroidResources} (see {@code
 * BoardListAdapterTest}'s class doc for why), so neither the row layout nor
 * its string resources resolve in this test environment. Runs under
 * Robolectric only because {@code RecyclerView.Adapter}'s own internal
 * observable list needs a real Android environment to initialise correctly;
 * no app resource is touched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class MyReportsAdapterTest {

    @Test
    public void isOpen_statusOpen_isTrue() {
        assertTrue(MyReportsAdapter.RowHolder.isOpen(ReportRepository.STATUS_OPEN));
    }

    @Test
    public void isOpen_statusClosed_isFalse() {
        assertFalse(MyReportsAdapter.RowHolder.isOpen(ReportRepository.STATUS_CLOSED));
    }

    @Test
    public void isOpen_nullStatus_isFalse() {
        assertFalse(MyReportsAdapter.RowHolder.isOpen(null));
    }

    @Test
    public void submitReports_onAlreadyBoundAdapter_updatesRowCountInPlace() {
        MyReportsAdapter adapter = new MyReportsAdapter(id -> { });

        adapter.submitReports(Arrays.asList(report("r1", ReportRepository.STATUS_OPEN),
                report("r2", ReportRepository.STATUS_CLOSED)));
        assertEquals(2, adapter.getItemCount());

        adapter.submitReports(java.util.Collections.singletonList(report("r3", ReportRepository.STATUS_OPEN)));
        assertEquals(1, adapter.getItemCount());
    }

    private Report report(String id, String status) {
        Report report = new Report();
        report.setId(id);
        report.setType("lost");
        report.setStatus(status);
        report.setCategory("KEYS");
        report.setReporterId("u1");
        return report;
    }
}

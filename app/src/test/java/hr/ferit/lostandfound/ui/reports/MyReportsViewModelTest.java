package hr.ferit.lostandfound.ui.reports;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.data.repo.ReportRepository;

/**
 * Load/close branches from spec-4-3's I/O &amp; Edge-Case Matrix: empty result,
 * malformed-document skip (verified at the repository level, since the
 * matrix's per-document try/catch lives in {@code ReportRepository.getByReporterId}),
 * close success updates in place with no re-query, close failure leaves state
 * unchanged. Uses a {@link ReportRepository} subclass fake (built with {@code
 * null} Firebase clients), matching {@code ReportDetailViewModelTest}'s
 * convention -- no Mockito in this repo. Robolectric only because {@code
 * ViewModel}'s {@code LiveData} needs the Android test runtime present.
 */
@RunWith(RobolectricTestRunner.class)
public class MyReportsViewModelTest {

    @Test
    public void load_success_resolvesToSuccessWithReports() {
        FakeReportRepository repo = new FakeReportRepository();
        Report report = new Report();
        report.setId("r1");
        repo.reportsToReturn = Collections.singletonList(report);

        MyReportsViewModel viewModel = new MyReportsViewModel(repo, "my-uid");
        viewModel.load();

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isSuccess());
        assertEquals(1, result.data.size());
        assertEquals("my-uid", repo.lastRequestedUid);
    }

    @Test
    public void load_emptyResult_resolvesToSuccessWithEmptyList() {
        FakeReportRepository repo = new FakeReportRepository();
        repo.reportsToReturn = Collections.emptyList();

        MyReportsViewModel viewModel = new MyReportsViewModel(repo, "my-uid");
        viewModel.load();

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isSuccess());
        assertTrue(result.data.isEmpty());
    }

    @Test
    public void load_repositoryError_resolvesToErrorWithLoadSentinel() {
        FakeReportRepository repo = new FakeReportRepository();
        repo.errorToReturn = new RuntimeException("boom");

        MyReportsViewModel viewModel = new MyReportsViewModel(repo, "my-uid");
        viewModel.load();

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isError());
        assertEquals(MyReportsViewModel.ERROR_LOAD, result.message);
    }

    @Test
    public void load_noSignedInUid_resolvesToErrorWithoutCallingRepository() {
        FakeReportRepository repo = new FakeReportRepository();

        MyReportsViewModel viewModel = new MyReportsViewModel(repo, null);
        viewModel.load();

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isError());
        assertEquals(MyReportsViewModel.ERROR_LOAD, result.message);
        assertEquals(0, repo.getByReporterIdCallCount);
    }

    @Test
    public void close_success_updatesReportInPlaceWithoutReload() {
        FakeReportRepository repo = new FakeReportRepository();
        Report open = new Report();
        open.setId("r1");
        open.setStatus(ReportRepository.STATUS_OPEN);
        repo.reportsToReturn = Collections.singletonList(open);

        MyReportsViewModel viewModel = new MyReportsViewModel(repo, "my-uid");
        viewModel.load();
        viewModel.close("r1");

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isSuccess());
        assertEquals(ReportRepository.STATUS_CLOSED, result.data.get(0).getStatus());
        assertEquals(1, repo.getByReporterIdCallCount);
        assertEquals("r1", repo.lastClosedId);
    }

    @Test
    public void close_failure_leavesListUnchanged() {
        FakeReportRepository repo = new FakeReportRepository();
        Report open = new Report();
        open.setId("r1");
        open.setStatus(ReportRepository.STATUS_OPEN);
        repo.reportsToReturn = Collections.singletonList(open);

        MyReportsViewModel viewModel = new MyReportsViewModel(repo, "my-uid");
        viewModel.load();

        repo.closeErrorToReturn = new RuntimeException("offline");
        viewModel.close("r1");

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isError());
        assertEquals(MyReportsViewModel.ERROR_CLOSE, result.message);
        assertEquals(ReportRepository.STATUS_OPEN, result.data.get(0).getStatus());
    }

    /** Firebase-free stand-in for {@link ReportRepository}, per the codebase's
     * subclass-over-Mockito convention. */
    private static final class FakeReportRepository extends ReportRepository {
        List<Report> reportsToReturn = new ArrayList<>();
        Exception errorToReturn;
        Exception closeErrorToReturn;
        int getByReporterIdCallCount;
        String lastRequestedUid;
        String lastClosedId;

        FakeReportRepository() {
            super(null, null);
        }

        @Override
        public void getByReporterId(@NonNull String uid, @NonNull GetListCallback callback) {
            getByReporterIdCallCount++;
            lastRequestedUid = uid;
            if (errorToReturn != null) {
                callback.onError(errorToReturn);
            } else {
                callback.onSuccess(reportsToReturn);
            }
        }

        @Override
        public void close(@NonNull String id, @NonNull CloseCallback callback) {
            lastClosedId = id;
            if (closeErrorToReturn != null) {
                callback.onError(closeErrorToReturn);
            } else {
                callback.onSuccess();
            }
        }
    }
}

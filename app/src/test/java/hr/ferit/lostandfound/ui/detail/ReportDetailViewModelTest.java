package hr.ferit.lostandfound.ui.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.data.repo.ReportRepository;

/**
 * LOADING/SUCCESS/ERROR/not-found and own-vs-other-reporter branching from the
 * spec's I/O &amp; Edge-Case Matrix. Uses a {@link FakeReportRepository} subclass
 * (built with {@code null} Firebase clients, like {@code ReportViewModelTest})
 * rather than Mockito, matching this repo's existing convention. Robolectric
 * only because {@code ViewModel}'s {@code LiveData} needs the Android test
 * runtime present.
 */
@RunWith(RobolectricTestRunner.class)
public class ReportDetailViewModelTest {

    @Test
    public void load_success_resolvesToSuccessWithReport() {
        FakeReportRepository repo = new FakeReportRepository();
        Report report = new Report();
        report.setReporterId("other-uid");
        repo.reportToReturn = report;

        ReportDetailViewModel viewModel = new ReportDetailViewModel(repo, "my-uid");
        viewModel.load("report-1");

        Resource<Report> result = viewModel.resource().getValue();
        assertTrue(result.isSuccess());
        assertEquals(report, result.data);
        assertEquals("report-1", repo.lastRequestedId);
    }

    @Test
    public void load_notFound_resolvesToErrorWithNotFoundSentinel() {
        FakeReportRepository repo = new FakeReportRepository();
        repo.notFound = true;

        ReportDetailViewModel viewModel = new ReportDetailViewModel(repo, "my-uid");
        viewModel.load("missing-id");

        Resource<Report> result = viewModel.resource().getValue();
        assertTrue(result.isError());
        assertEquals(ReportDetailViewModel.ERROR_NOT_FOUND, result.message);
    }

    @Test
    public void load_repositoryError_resolvesToErrorWithGenericSentinel() {
        FakeReportRepository repo = new FakeReportRepository();
        repo.errorToReturn = new RuntimeException("boom");

        ReportDetailViewModel viewModel = new ReportDetailViewModel(repo, "my-uid");
        viewModel.load("report-1");

        Resource<Report> result = viewModel.resource().getValue();
        assertTrue(result.isError());
        assertEquals(ReportDetailViewModel.ERROR_GENERIC, result.message);
    }

    @Test
    public void load_beforeRepositoryResponds_leavesLiveDataLoading() {
        FakeReportRepository repo = new FakeReportRepository();
        repo.suppressCallback = true;

        ReportDetailViewModel viewModel = new ReportDetailViewModel(repo, "my-uid");
        viewModel.load("report-1");

        assertTrue(viewModel.resource().getValue().isLoading());
    }

    @Test
    public void retry_replaysTheLastLoadedId() {
        FakeReportRepository repo = new FakeReportRepository();
        repo.reportToReturn = new Report();

        ReportDetailViewModel viewModel = new ReportDetailViewModel(repo, "my-uid");
        viewModel.load("report-1");
        viewModel.retry();

        assertEquals(2, repo.callCount);
        assertEquals("report-1", repo.lastRequestedId);
    }

    @Test
    public void retry_beforeAnyLoad_isNoOp() {
        FakeReportRepository repo = new FakeReportRepository();
        ReportDetailViewModel viewModel = new ReportDetailViewModel(repo, "my-uid");

        viewModel.retry();

        assertEquals(0, repo.callCount);
        assertNull(viewModel.resource().getValue());
    }

    @Test
    public void isOwnReport_matchingUid_isTrue() {
        ReportDetailViewModel viewModel = new ReportDetailViewModel(new FakeReportRepository(), "my-uid");
        Report report = new Report();
        report.setReporterId("my-uid");

        assertTrue(viewModel.isOwnReport(report));
    }

    @Test
    public void isOwnReport_differentUid_isFalse() {
        ReportDetailViewModel viewModel = new ReportDetailViewModel(new FakeReportRepository(), "my-uid");
        Report report = new Report();
        report.setReporterId("other-uid");

        assertFalse(viewModel.isOwnReport(report));
    }

    @Test
    public void isOwnReport_noSignedInUser_isFalse() {
        ReportDetailViewModel viewModel = new ReportDetailViewModel(new FakeReportRepository(), null);
        Report report = new Report();
        report.setReporterId("other-uid");

        assertFalse(viewModel.isOwnReport(report));
    }

    /** Firebase-free stand-in for {@link ReportRepository}, per the codebase's
     * subclass-over-Mockito convention (no Mockito in this repo). */
    private static final class FakeReportRepository extends ReportRepository {
        Report reportToReturn;
        Exception errorToReturn;
        boolean notFound;
        boolean suppressCallback;
        int callCount;
        String lastRequestedId;

        FakeReportRepository() {
            super(null, null);
        }

        @Override
        public void getById(@NonNull String id, @NonNull ReportRepository.GetCallback callback) {
            callCount++;
            lastRequestedId = id;
            if (suppressCallback) {
                return;
            }
            if (notFound) {
                callback.onNotFound();
            } else if (errorToReturn != null) {
                callback.onError(errorToReturn);
            } else if (reportToReturn != null) {
                callback.onSuccess(reportToReturn);
            }
        }
    }
}

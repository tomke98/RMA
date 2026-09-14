package hr.ferit.lostandfound.ui.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.List;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.data.repo.BoardRepository;

/**
 * LOADING/SUCCESS(empty)/SUCCESS(N)/ERROR passthrough, plus the
 * start()-once-per-instance / retry()-restarts contract, from the spec's I/O
 * &amp; Edge-Case Matrix. Uses a {@link FakeBoardRepository} subclass (built with
 * a {@code null} Firebase client, like {@code ReportDetailViewModelTest}'s
 * {@code FakeReportRepository}) rather than Mockito, matching this repo's
 * existing convention. Robolectric only because {@code ViewModel}'s {@code
 * LiveData} needs the Android test runtime present.
 */
@RunWith(RobolectricTestRunner.class)
public class BoardViewModelTest {

    @Test
    public void construction_startsTheRepositoryListenerExactlyOnce() {
        FakeBoardRepository repo = new FakeBoardRepository();

        new BoardViewModel(repo);

        assertEquals(1, repo.startCallCount);
    }

    @Test
    public void resource_loading_reflectsRepositoryState() {
        FakeBoardRepository repo = new FakeBoardRepository();
        BoardViewModel viewModel = new BoardViewModel(repo);

        assertTrue(viewModel.resource().getValue().isLoading());
    }

    @Test
    public void resource_success_empty_reflectsRepositoryState() {
        FakeBoardRepository repo = new FakeBoardRepository();
        BoardViewModel viewModel = new BoardViewModel(repo);

        repo.emit(Resource.success(Collections.<Report>emptyList()));

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isSuccess());
        assertTrue(result.data.isEmpty());
    }

    @Test
    public void resource_success_withReports_reflectsRepositoryState() {
        FakeBoardRepository repo = new FakeBoardRepository();
        BoardViewModel viewModel = new BoardViewModel(repo);

        Report report = new Report();
        report.setId("r1");
        repo.emit(Resource.success(Collections.singletonList(report)));

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isSuccess());
        assertEquals(1, result.data.size());
        assertEquals("r1", result.data.get(0).getId());
    }

    @Test
    public void resource_error_reflectsRepositoryState() {
        FakeBoardRepository repo = new FakeBoardRepository();
        BoardViewModel viewModel = new BoardViewModel(repo);

        repo.emit(Resource.<List<Report>>error(BoardRepository.ERROR_GENERIC));

        Resource<List<Report>> result = viewModel.resource().getValue();
        assertTrue(result.isError());
    }

    @Test
    public void retry_callsRepositoryStartAgain() {
        FakeBoardRepository repo = new FakeBoardRepository();
        BoardViewModel viewModel = new BoardViewModel(repo);

        viewModel.retry();

        assertEquals(2, repo.startCallCount);
    }

    @Test
    public void viewModel_neverCallsRepositoryStop() {
        // Only ServiceLocator.reset() should ever call BoardRepository#stop();
        // the ViewModel itself (construction or retry()) must not.
        FakeBoardRepository repo = new FakeBoardRepository();
        BoardViewModel viewModel = new BoardViewModel(repo);

        viewModel.retry();

        assertEquals(0, repo.stopCallCount);
    }

    /** Firebase-free stand-in for {@link BoardRepository}, per the codebase's
     * subclass-over-Mockito convention (no Mockito in this repo). */
    private static final class FakeBoardRepository extends BoardRepository {
        private final MutableLiveData<Resource<List<Report>>> resource = new MutableLiveData<>();
        int startCallCount;
        int stopCallCount;

        FakeBoardRepository() {
            super(null, null, RuntimeEnvironment.getApplication());
        }

        @NonNull
        @Override
        public LiveData<Resource<List<Report>>> resource() {
            return resource;
        }

        @Override
        public void start() {
            startCallCount++;
            resource.setValue(Resource.loading());
        }

        @Override
        public void stop() {
            stopCallCount++;
            resource.setValue(null);
        }

        void emit(Resource<List<Report>> value) {
            resource.setValue(value);
        }
    }
}

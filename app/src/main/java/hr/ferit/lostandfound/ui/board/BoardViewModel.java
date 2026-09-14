package hr.ferit.lostandfound.ui.board;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.data.repo.BoardRepository;

/**
 * Screen logic for {@link BoardActivity}: holds the process-wide {@link
 * BoardRepository}, starts its listener once per {@code ViewModel} instance,
 * and exposes its {@code LiveData<Resource<List<Report>>>} as-is. Holds no
 * Android framework type and imports no {@code com.google.firebase.*} type,
 * mirroring {@code ui.detail.ReportDetailViewModel}.
 *
 * <p>Survives rotation (framework contract of {@link ViewModel}): the
 * constructor runs once per Activity lifetime, so {@link BoardRepository#start()}
 * — already idempotent — is invoked exactly once outside of an explicit
 * {@link #retry()}.
 */
public class BoardViewModel extends ViewModel {

    private static final String TAG = "BoardViewModel";

    @NonNull
    private final BoardRepository boardRepository;

    public BoardViewModel(@NonNull BoardRepository boardRepository) {
        this.boardRepository = boardRepository;
        boardRepository.start();
    }

    @NonNull
    public LiveData<Resource<List<Report>>> resource() {
        return boardRepository.resource();
    }

    /** {@code max(syncedAt)} from the Room mirror, for the offline label. */
    @NonNull
    public LiveData<Long> lastSyncedAt() {
        return boardRepository.lastSyncedAt();
    }

    /** Re-attaches the Board listener after a failure; a no-op while a listener is already attached. */
    public void retry() {
        boardRepository.start();
    }
}

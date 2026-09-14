package hr.ferit.lostandfound.ui.reports;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.data.repo.ReportRepository;

/**
 * Screen logic for {@link MyReportsActivity} (Story 4.3): loads every report
 * with {@code reporterId == uid} through {@link ReportRepository#getByReporterId},
 * exposes it as {@code LiveData<Resource<List<Report>>>}, and closes a single
 * report through {@link ReportRepository#close}. On a successful close the
 * held list is mutated in place and re-posted as {@link Resource#success} —
 * no re-query — matching {@code ReportDetailViewModel}'s Resource-wrapping
 * style and this story's Design Notes (instant UI update, no redundant read).
 */
public class MyReportsViewModel extends ViewModel {

    private static final String TAG = "MyReportsViewModel";

    /** {@link Resource#message} sentinel for any load failure. */
    public static final String ERROR_LOAD = "ERROR_LOAD";

    /** {@link Resource#message} sentinel for a {@link #close} failure; carries
     * the unchanged list as {@link Resource#data} so the view can keep
     * rendering the list while it shows a retryable Snackbar. */
    public static final String ERROR_CLOSE = "ERROR_CLOSE";

    @NonNull
    private final ReportRepository reportRepository;

    @Nullable
    private final String uid;

    private final MutableLiveData<Resource<List<Report>>> resource = new MutableLiveData<>();

    public MyReportsViewModel(@NonNull ReportRepository reportRepository, @Nullable String uid) {
        this.reportRepository = reportRepository;
        this.uid = uid;
    }

    @NonNull
    public LiveData<Resource<List<Report>>> resource() {
        return resource;
    }

    /** Loads (or reloads) the current uid's reports. A no-op, publishing an
     * error, when there is no signed-in uid (this screen is only reachable
     * while signed in via BoardActivity anyway; fail-soft per AD-14). */
    public void load() {
        if (uid == null) {
            Log.w(TAG, "load() called with no signed-in uid.");
            resource.setValue(Resource.error(ERROR_LOAD));
            return;
        }
        resource.setValue(Resource.loading());
        reportRepository.getByReporterId(uid, new ReportRepository.GetListCallback() {
            @Override
            public void onSuccess(@NonNull List<Report> reports) {
                resource.setValue(Resource.success(reports));
            }

            @Override
            public void onError(@NonNull Exception e) {
                Log.w(TAG, "ReportRepository.getByReporterId failed.", e);
                resource.setValue(Resource.error(ERROR_LOAD));
            }
        });
    }

    /** Repeats the last {@link #load} call. */
    public void retry() {
        load();
    }

    /**
     * Closes {@code reportId}. On success, replaces that report's status with
     * {@link ReportRepository#STATUS_CLOSED} in the currently held list and
     * re-emits {@link Resource#success} (no re-query). On failure, re-emits the
     * unchanged list wrapped in {@link Resource#error(String, Object)} with
     * {@link #ERROR_CLOSE}, so the row stays open/unchanged (fail-soft, AD-14).
     */
    public void close(@NonNull String reportId) {
        reportRepository.close(reportId, new ReportRepository.CloseCallback() {
            @Override
            public void onSuccess() {
                Resource<List<Report>> current = resource.getValue();
                List<Report> updated = current != null && current.data != null
                        ? new ArrayList<>(current.data)
                        : new ArrayList<>();
                for (Report report : updated) {
                    if (reportId.equals(report.getId())) {
                        report.setStatus(ReportRepository.STATUS_CLOSED);
                        break;
                    }
                }
                resource.setValue(Resource.success(updated));
            }

            @Override
            public void onError(@NonNull Exception e) {
                Log.w(TAG, "ReportRepository.close failed for " + reportId + ".", e);
                Resource<List<Report>> current = resource.getValue();
                List<Report> unchanged = current != null ? current.data : null;
                resource.setValue(Resource.error(ERROR_CLOSE, unchanged));
            }
        });
    }
}

package hr.ferit.lostandfound.ui.detail;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.data.repo.ReportRepository;

/**
 * Screen logic for {@link ReportDetailActivity}: loads one {@link Report} by id
 * through {@link ReportRepository#getById}, exposes it as
 * {@code LiveData<Resource<Report>>}, and answers whether the current viewer is
 * the reporter. Holds no Android framework type and imports no
 * {@code com.google.firebase.*} type — the current uid is resolved once, in
 * {@code ViewModelFactory} via {@code ServiceLocator.auth()}, and handed in here
 * as a plain {@link String}.
 */
public class ReportDetailViewModel extends ViewModel {

    private static final String TAG = "ReportDetailViewModel";

    /**
     * {@link Resource#message} sentinel for "no such document" — the view shows
     * a non-retryable "not found" message for this one, and a retryable generic
     * error ({@link #ERROR_GENERIC}) for every other failure.
     */
    public static final String ERROR_NOT_FOUND = "ERROR_NOT_FOUND";

    /** {@link Resource#message} sentinel for any other load failure. */
    public static final String ERROR_GENERIC = "ERROR_GENERIC";

    @NonNull
    private final ReportRepository reportRepository;

    @Nullable
    private final String currentUid;

    private final MutableLiveData<Resource<Report>> resource = new MutableLiveData<>();

    /** The last id passed to {@link #load}, replayed by {@link #retry}. */
    @Nullable
    private String lastLoadedId;

    public ReportDetailViewModel(@NonNull ReportRepository reportRepository, @Nullable String currentUid) {
        this.reportRepository = reportRepository;
        this.currentUid = currentUid;
    }

    @NonNull
    public LiveData<Resource<Report>> resource() {
        return resource;
    }

    /** Loads (or reloads) the report with this id. */
    public void load(@NonNull String id) {
        lastLoadedId = id;
        resource.setValue(Resource.loading());
        reportRepository.getById(id, new ReportRepository.GetCallback() {
            @Override
            public void onSuccess(@NonNull Report report) {
                resource.setValue(Resource.success(report));
            }

            @Override
            public void onNotFound() {
                resource.setValue(Resource.error(ERROR_NOT_FOUND));
            }

            @Override
            public void onError(@NonNull Exception e) {
                Log.w(TAG, "ReportRepository.getById failed.", e);
                resource.setValue(Resource.error(ERROR_GENERIC));
            }
        });
    }

    /** Repeats the last {@link #load} call; a no-op if nothing was loaded yet. */
    public void retry() {
        if (lastLoadedId != null) {
            load(lastLoadedId);
        }
    }

    /** @return whether {@code report} was filed by the current signed-in user. */
    public boolean isOwnReport(@NonNull Report report) {
        return currentUid != null && currentUid.equals(report.getReporterId());
    }
}

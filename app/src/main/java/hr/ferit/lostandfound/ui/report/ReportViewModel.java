package hr.ferit.lostandfound.ui.report;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Category;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.repo.ReportRepository;

/**
 * Screen logic for {@link CreateReportActivity}: validates the report fields,
 * branched by type (lost: category, description &gt;= 10 chars, location
 * required, photo optional; found: category, location, photo required,
 * description optional), then hands a {@link Report} draft to
 * {@link ReportRepository}. Mirrors {@code ui.auth.RegisterViewModel}'s
 * {@code List<FieldError>} / single-shot-{@code Outcome} shape. Holds no
 * Android framework type and imports no {@code com.google.firebase.*} type.
 */
public class ReportViewModel extends ViewModel {

    private static final String TAG = "ReportViewModel";

    private static final int MIN_DESCRIPTION_LENGTH = 10;

    private static final String REPORT_TYPE_FOUND = "found";

    /** The possible required-field errors, in top-to-bottom form order. */
    public enum Field {
        CATEGORY,
        DESCRIPTION,
        LOCATION,
        PHOTO
    }

    /** Terminal + loading states the view renders. */
    public enum Outcome {
        LOADING,
        SUCCESS,
        /** Fields are valid but there is no connectivity; no Firestore call was made. */
        OFFLINE,
        ERROR
    }

    /** One inline per-field error: which field, and the string resource to show. */
    public static final class FieldError {
        public final Field field;
        @StringRes
        public final int messageRes;

        public FieldError(@NonNull Field field, @StringRes int messageRes) {
            this.field = field;
            this.messageRes = messageRes;
        }
    }

    @NonNull
    private final ReportRepository reportRepository;

    private final MutableLiveData<List<FieldError>> fieldErrors = new MutableLiveData<>();
    private final MutableLiveData<Outcome> outcome = new MutableLiveData<>();

    /** Guards against a second submit while a create attempt is in flight. */
    private boolean inFlight;

    public ReportViewModel(@NonNull ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    @NonNull
    public LiveData<List<FieldError>> fieldErrors() {
        return fieldErrors;
    }

    @NonNull
    public LiveData<Outcome> outcome() {
        return outcome;
    }

    /** Called by the view after it has handled a terminal {@link Outcome}. */
    public void consumeOutcome() {
        outcome.setValue(null);
    }

    /**
     * Validates the report fields, branched by type (lost: category,
     * description &gt;= 10 chars, location required, photo optional; found:
     * category, location, photo required, description optional) and, if they
     * pass, starts the create sequence. A no-op while an attempt is already in
     * flight.
     *
     * <p>Validation runs before the connectivity check, so an offline user still
     * sees inline field errors; only a <em>valid</em> offline submit resolves to
     * {@link Outcome#OFFLINE} and makes no Firestore call.
     *
     * @param type            report type, {@code "lost"} or {@code "found"}
     * @param category        selected category, or {@code null} if none chosen
     * @param descriptionRaw  free-text description
     * @param lat             picked latitude, or {@code null} if no location was set
     * @param lng             picked longitude, or {@code null} if no location was set
     * @param locationLabel   optional one-line location label
     * @param localPhotoPath  optional local (already downscaled) photo file path
     * @param online          current connectivity, read by the view via {@code Connectivity.isOnline}
     */
    public void submit(@NonNull String type,
                       @Nullable Category category,
                       @Nullable String descriptionRaw,
                       @Nullable Double lat,
                       @Nullable Double lng,
                       @Nullable String locationLabel,
                       @Nullable String localPhotoPath,
                       boolean online) {
        if (inFlight) {
            return;
        }

        String description = trimOrEmpty(descriptionRaw);

        List<FieldError> errors = validate(type, category, description, lat, lng, localPhotoPath);
        if (!errors.isEmpty()) {
            fieldErrors.setValue(errors);
            return;
        }
        fieldErrors.setValue(Collections.emptyList());

        if (!online) {
            outcome.setValue(Outcome.OFFLINE);
            return;
        }

        Report draft = new Report();
        draft.setType(type);
        draft.setCategory(category.name());
        draft.setDescription(description);
        draft.setLat(lat);
        draft.setLng(lng);
        draft.setLocationLabel(locationLabel);

        inFlight = true;
        outcome.setValue(Outcome.LOADING);
        reportRepository.create(draft, localPhotoPath, new ReportRepository.CreateCallback() {
            @Override
            public void onSuccess() {
                inFlight = false;
                deleteLocalPhotoQuietly(localPhotoPath);
                outcome.setValue(Outcome.SUCCESS);
            }

            @Override
            public void onError(@NonNull Exception e) {
                Log.w(TAG, "ReportRepository.create failed.", e);
                inFlight = false;
                outcome.setValue(Outcome.ERROR);
            }
        });
    }

    /**
     * The local downscaled photo (Story 2.2's {@code cache/images/} file) is only
     * needed to drive the form's own preview; once a report is written it has no
     * further use, so it is deleted here rather than left to accumulate in the
     * cache. Not called on {@code onError} — the preview still needs it if the
     * user retries.
     */
    private static void deleteLocalPhotoQuietly(@Nullable String localPhotoPath) {
        if (localPhotoPath == null) {
            return;
        }
        if (!new File(localPhotoPath).delete()) {
            Log.w(TAG, "Failed to delete local photo cache file: " + localPhotoPath);
        }
    }

    private static List<FieldError> validate(@NonNull String type,
                                              @Nullable Category category,
                                              @NonNull String description,
                                              @Nullable Double lat,
                                              @Nullable Double lng,
                                              @Nullable String localPhotoPath) {
        boolean found = REPORT_TYPE_FOUND.equals(type);
        List<FieldError> errors = new ArrayList<>();

        if (category == null) {
            errors.add(new FieldError(Field.CATEGORY, R.string.prijava_kategorija_obavezna));
        }

        if (!found && description.length() < MIN_DESCRIPTION_LENGTH) {
            errors.add(new FieldError(Field.DESCRIPTION, R.string.prijava_opis_prekratak));
        }

        if (lat == null || lng == null) {
            errors.add(new FieldError(Field.LOCATION, R.string.prijava_lokacija_obavezna));
        }

        if (found && localPhotoPath == null) {
            errors.add(new FieldError(Field.PHOTO, R.string.prijava_fotografija_obavezna));
        }

        return errors;
    }

    @NonNull
    private static String trimOrEmpty(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}

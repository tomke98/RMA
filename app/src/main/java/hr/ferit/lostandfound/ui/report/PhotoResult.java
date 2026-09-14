package hr.ferit.lostandfound.ui.report;

import androidx.annotation.NonNull;

/**
 * Immutable transport value delivered by
 * {@link PhotoCaptureController.PhotoResultListener#onPhotoReady(PhotoResult)}.
 * Plain data only — no framework import, no Firebase type. Stories 2.3/2.4
 * read {@link #getLocalFilePath()} and pass it to their {@code ReportViewModel}
 * for the upload step (AD-7); this type is never persisted and never handed to
 * a repository as an object. Mirrors {@link PickedLocation}.
 */
public final class PhotoResult {

    @NonNull
    private final String localFilePath;

    public PhotoResult(@NonNull String localFilePath) {
        this.localFilePath = localFilePath;
    }

    @NonNull
    public String getLocalFilePath() {
        return localFilePath;
    }
}

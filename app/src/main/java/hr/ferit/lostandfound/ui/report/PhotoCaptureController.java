package hr.ferit.lostandfound.ui.report;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.util.ImageUtils;

/**
 * Reusable camera-or-gallery photo attachment (Story 2.2), owned by
 * {@code ui.report.CreateReportActivity} for Stories 2.3/2.4 — no screen of
 * its own. No {@code com.google.firebase.*} import, no repository call, no
 * {@code ViewModel}: output is a local file path only (AD-7's upload step
 * runs later, in {@code ReportRepository.create}).
 *
 * <p>Mirrors {@code util.LocationHelper} / {@code ui.report.LocationPickerActivity}'s
 * launcher conventions: {@link #PhotoCaptureController} must be constructed in
 * the host {@link AppCompatActivity}'s {@code onCreate}, before it reaches
 * {@code STARTED} — {@code registerForActivityResult} requires this.
 *
 * <p>Every failure path (no camera app, denied {@code CAMERA}, undecodable
 * image, I/O failure) calls {@link PhotoResultListener#onPhotoError(String)}
 * — never throws past this class (AD-14, fail soft).
 */
public final class PhotoCaptureController {

    private static final String TAG = "PhotoCaptureController";

    /** Delivers the outcome of a single capture/pick attempt on the main thread. */
    public interface PhotoResultListener {
        void onPhotoReady(@NonNull PhotoResult result);

        void onPhotoCancelled();

        /** {@code messageKey} is a resolved, ready-to-display Croatian message
         * (from {@code strings.xml}) — the host can hand it straight to a
         * {@code Snackbar} or inline error view. */
        void onPhotoError(@NonNull String messageKey);
    }

    @NonNull
    private final AppCompatActivity activity;
    @NonNull
    private final PhotoResultListener listener;

    @NonNull
    private final ActivityResultLauncher<String> cameraPermissionLauncher;
    @NonNull
    private final ActivityResultLauncher<Uri> takePictureLauncher;
    @NonNull
    private final ActivityResultLauncher<String> pickImageLauncher;

    /** Raw (pre-downscale) camera capture target for the in-flight request
     * only; cleared once the launcher result arrives. */
    @Nullable
    private Uri pendingCameraUri;
    @Nullable
    private File pendingCameraFile;

    public PhotoCaptureController(@NonNull AppCompatActivity activity, @NonNull PhotoResultListener listener) {
        this.activity = activity;
        this.listener = listener;
        this.cameraPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), this::onCameraPermissionResult);
        this.takePictureLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.TakePicture(), this::onTakePictureResult);
        this.pickImageLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onPickImageResult);
    }

    /** The in-flight camera capture target, for the host to persist across a
     * configuration change (e.g. in {@code onSaveInstanceState}); {@code null}
     * when no camera request is in flight. */
    @Nullable
    public Uri getPendingCameraUri() {
        return pendingCameraUri;
    }

    /** @see #getPendingCameraUri() */
    @Nullable
    public File getPendingCameraFile() {
        return pendingCameraFile;
    }

    /** Re-applies an in-flight camera request's target after this controller was
     * reconstructed (host Activity recreated by a configuration change). Must be
     * called before the pending {@code takePictureLauncher} result arrives, so
     * the result is delivered to {@link PhotoResultListener} instead of being
     * misread as a cancellation against an empty controller. */
    public void restorePendingCamera(@Nullable Uri uri, @Nullable File file) {
        pendingCameraUri = uri;
        pendingCameraFile = file;
    }

    /** Requests {@code CAMERA} permission at point of use if needed, then
     * launches the system camera. Denial calls {@code onPhotoError} but never
     * blocks {@link #pickPhoto()}. */
    public void takePhoto() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /** Launches the gallery picker; no runtime permission needed for
     * {@code ACTION_GET_CONTENT}. */
    public void pickPhoto() {
        try {
            pickImageLauncher.launch("image/*");
        } catch (ActivityNotFoundException e) {
            // No gallery/picker app resolves ACTION_GET_CONTENT — fail soft.
            Log.w(TAG, "No gallery app available.", e);
            listener.onPhotoError(activity.getString(R.string.fotografija_greska_datoteka));
        }
    }

    private void onCameraPermissionResult(boolean granted) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (granted) {
            startCamera();
        } else {
            Log.w(TAG, "CAMERA permission denied; gallery remains available.");
            listener.onPhotoError(activity.getString(R.string.fotografija_bez_dozvole));
        }
    }

    private void startCamera() {
        File dir = new File(activity.getCacheDir(), "images");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create cache/images directory for camera capture.");
            listener.onPhotoError(activity.getString(R.string.fotografija_greska_datoteka));
            return;
        }

        File file = new File(dir, UUID.randomUUID() + ".jpg");
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Unable to mint FileProvider Uri for camera capture.", e);
            listener.onPhotoError(activity.getString(R.string.fotografija_greska_kamera));
            return;
        }

        pendingCameraFile = file;
        pendingCameraUri = uri;
        try {
            takePictureLauncher.launch(uri);
        } catch (ActivityNotFoundException e) {
            // No camera app resolves ACTION_IMAGE_CAPTURE — fail soft, gallery
            // still works.
            Log.w(TAG, "No camera app available.", e);
            clearPendingCamera();
            listener.onPhotoError(activity.getString(R.string.fotografija_greska_kamera));
        }
    }

    private void onTakePictureResult(boolean success) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            deleteQuietly(pendingCameraFile);
            clearPendingCamera();
            return;
        }

        Uri capturedUri = pendingCameraUri;
        File rawFile = pendingCameraFile;
        clearPendingCamera();

        if (!success || capturedUri == null) {
            deleteQuietly(rawFile);
            listener.onPhotoCancelled();
            return;
        }
        processImage(capturedUri, rawFile);
    }

    private void onPickImageResult(@Nullable Uri uri) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (uri == null) {
            listener.onPhotoCancelled();
            return;
        }
        processImage(uri, null);
    }

    /** Downscales {@code src} into a fresh file, then best-effort deletes
     * {@code rawFileToCleanup} (the untouched camera capture, if any) —
     * cleanup runs regardless of outcome so nothing partial is left behind. */
    private void processImage(@NonNull Uri src, @Nullable File rawFileToCleanup) {
        try {
            File destDir = new File(activity.getCacheDir(), "images");
            File output = ImageUtils.downscaleToJpeg(activity, src, destDir);
            listener.onPhotoReady(new PhotoResult(output.getAbsolutePath()));
        } catch (IOException e) {
            Log.w(TAG, "Failed to process photo from " + src, e);
            listener.onPhotoError(activity.getString(R.string.fotografija_greska_datoteka));
        } finally {
            deleteQuietly(rawFileToCleanup);
        }
    }

    private void clearPendingCamera() {
        pendingCameraUri = null;
        pendingCameraFile = null;
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}

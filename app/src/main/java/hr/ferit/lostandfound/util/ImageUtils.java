package hr.ferit.lostandfound.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Stateless downscale/re-encode used by {@code ui.report.PhotoCaptureController}
 * (Story 2.2) for both camera and gallery sources. No Firebase type, no
 * repository, no {@code ViewModel} import — this is a pure decode/transform
 * utility that only touches the local file system (AD-7 / AD-8: the actual
 * upload happens later, in {@code ReportRepository.create}).
 *
 * <p>Every failure surfaces as {@link IOException}; the caller (the
 * controller) is responsible for catching it and reporting fail-soft, never
 * letting it crash the app.
 */
public final class ImageUtils {

    private static final String TAG = "ImageUtils";

    /** Longest edge of the re-encoded output, per AD-7. */
    public static final int TARGET_LONGEST_EDGE_PX = 1024;

    /** JPEG re-encode quality, per AD-7 ("~80%"). */
    public static final int JPEG_QUALITY = 80;

    private ImageUtils() {
    }

    /**
     * Decodes {@code src}, normalises its EXIF rotation, downscales it so its
     * longest edge is at most {@link #TARGET_LONGEST_EDGE_PX}px, re-encodes it
     * as a JPEG at {@link #JPEG_QUALITY}% quality into a new file inside
     * {@code destDir}, and returns that file.
     *
     * <p>On any failure (unreadable source, undecodable image, I/O error) this
     * throws {@link IOException} and leaves no partial output file behind —
     * never a bare/empty catch internally.
     */
    @NonNull
    public static File downscaleToJpeg(@NonNull Context context, @NonNull Uri src, @NonNull File destDir)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();

        int orientation = readExifOrientation(resolver, src);

        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        try (InputStream boundsStream = openInputStream(resolver, src)) {
            if (boundsStream == null) {
                throw new IOException("Unable to open input stream for " + src);
            }
            BitmapFactory.decodeStream(boundsStream, null, boundsOptions);
        }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            throw new IOException("Unable to decode image bounds for " + src);
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize =
                calculateInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, TARGET_LONGEST_EDGE_PX);

        Bitmap sampled;
        try (InputStream decodeStream = openInputStream(resolver, src)) {
            if (decodeStream == null) {
                throw new IOException("Unable to open input stream for " + src);
            }
            sampled = BitmapFactory.decodeStream(decodeStream, null, decodeOptions);
        }
        if (sampled == null) {
            throw new IOException("Unable to decode image for " + src);
        }

        Bitmap rotated = applyExifRotation(sampled, orientation);
        Bitmap scaled = scaleToLongestEdge(rotated, TARGET_LONGEST_EDGE_PX);

        if (!destDir.exists() && !destDir.mkdirs()) {
            recycleAll(sampled, rotated, scaled);
            throw new IOException("Unable to create destination directory " + destDir);
        }

        File outFile = new File(destDir, UUID.randomUUID() + ".jpg");
        boolean success = false;
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw new IOException("Bitmap#compress reported failure for " + outFile);
            }
            success = true;
        } finally {
            recycleAll(sampled, rotated, scaled);
            if (!success) {
                // Best-effort cleanup — no partial file left behind.
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
        }
        return outFile;
    }

    /** Downsampling factor (power of two) that gets decode dimensions close to,
     * without going below, {@code targetLongestEdge} — final precise sizing
     * happens in {@link #scaleToLongestEdge}. */
    private static int calculateInSampleSize(int width, int height, int targetLongestEdge) {
        int longest = Math.max(width, height);
        int sampleSize = 1;
        while (longest / (sampleSize * 2) >= targetLongestEdge) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /** Rotates/flips per EXIF orientation; returns the same instance when no
     * transform is needed (ORIENTATION_NORMAL / ORIENTATION_UNDEFINED). */
    @NonNull
    private static Bitmap applyExifRotation(@NonNull Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1f, 1f);
                break;
            default:
                return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /** Scales down so the longest edge is at most {@code targetLongestEdge}px;
     * returns the same instance when already within bounds (never upscales). */
    @NonNull
    private static Bitmap scaleToLongestEdge(@NonNull Bitmap bitmap, int targetLongestEdge) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        if (longest <= targetLongestEdge) {
            return bitmap;
        }
        float scale = (float) targetLongestEdge / longest;
        int newWidth = Math.max(1, Math.round(width * scale));
        int newHeight = Math.max(1, Math.round(height * scale));
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    /** Missing/unreadable EXIF is not a failure of the whole decode — fails
     * soft to ORIENTATION_NORMAL and logs, per AD-14. */
    private static int readExifOrientation(@NonNull ContentResolver resolver, @NonNull Uri src) {
        try (InputStream stream = openInputStream(resolver, src)) {
            if (stream == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            return new ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            Log.w(TAG, "Unable to read EXIF orientation for " + src + "; assuming normal.", e);
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** Wraps {@link ContentResolver#openInputStream} so a revoked URI grant or
     * other provider-side failure (thrown as an unchecked {@link RuntimeException},
     * e.g. {@code SecurityException}) surfaces as {@link IOException} like every
     * other failure in this class, instead of escaping past the caller's
     * IOException-only catch (AD-14, never crash). */
    @Nullable
    private static InputStream openInputStream(@NonNull ContentResolver resolver, @NonNull Uri src)
            throws IOException {
        try {
            return resolver.openInputStream(src);
        } catch (RuntimeException e) {
            throw new IOException("Unable to open input stream for " + src, e);
        }
    }

    /** Fetches and decodes a photo over a plain {@link HttpURLConnection} (this
     * project has no image-loading library), moved verbatim from the former
     * {@code ReportDetailActivity.fetchBitmap} (Story 3.1) so {@code
     * ui.detail.ReportDetailActivity} and {@code ui.board.BoardListAdapter}
     * (Story 3.3) share one implementation. Returns {@code null} on any I/O
     * failure — callers fall back to a placeholder, never crash (AD-14). */
    @Nullable
    public static Bitmap fetchBitmap(@NonNull String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.connect();
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (IOException e) {
            Log.w(TAG, "Photo fetch failed for " + urlString, e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** De-duplicates by identity (a rotate/scale step may return the same
     * instance it was given) so a bitmap is never recycled twice. */
    private static void recycleAll(Bitmap... bitmaps) {
        Set<Bitmap> seen = new HashSet<>();
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && seen.add(bitmap) && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }
}

package hr.ferit.lostandfound.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Verifies {@link ImageUtils#downscaleToJpeg} against the Story 2.2 I/O matrix
 * rows that don't need a device: downscale math, EXIF-rotation on fixture
 * bitmaps, and the corrupt/missing-source failure paths. Uses Robolectric so
 * real {@code BitmapFactory} / {@code Bitmap#compress} run on real JPEG bytes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class ImageUtilsTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private final Application application = RuntimeEnvironment.getApplication();

    @Test
    public void downscaleToJpeg_largeImage_scalesToLongestEdge1024px() throws IOException {
        File source = createJpegFile("source.jpg", 2000, 1000);
        File destDir = tempFolder.newFolder("out-large");

        File output = ImageUtils.downscaleToJpeg(application, Uri.fromFile(source), destDir);

        assertTrue(output.exists());
        int[] size = decodedSize(output);
        assertEquals(1024, Math.max(size[0], size[1]));
        assertTrue(Math.max(size[0], size[1]) <= ImageUtils.TARGET_LONGEST_EDGE_PX);
    }

    @Test
    public void downscaleToJpeg_smallImage_isNotUpscaled() throws IOException {
        File source = createJpegFile("small.jpg", 200, 100);
        File destDir = tempFolder.newFolder("out-small");

        File output = ImageUtils.downscaleToJpeg(application, Uri.fromFile(source), destDir);

        int[] size = decodedSize(output);
        assertEquals(200, size[0]);
        assertEquals(100, size[1]);
    }

    @Test
    public void downscaleToJpeg_exifRotated_outputIsUpright() throws IOException {
        // Landscape source (800x600) tagged ROTATE_90: the upright image is
        // portrait (600x800) — the output must reflect that, not the raw
        // landscape pixel shape.
        File source = createJpegFile("rotated.jpg", 800, 600);
        ExifInterface exif = new ExifInterface(source.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        File destDir = tempFolder.newFolder("out-rotated");

        File output = ImageUtils.downscaleToJpeg(application, Uri.fromFile(source), destDir);

        int[] size = decodedSize(output);
        assertEquals(600, size[0]);
        assertEquals(800, size[1]);
    }

    // NOTE — "Corrupt/undecodable image" I/O-matrix row (BitmapFactory.decode*
    // returning null): Robolectric's BitmapFactory shadow cannot reproduce this
    // — it always falls back to a fake, non-null placeholder bitmap on decode
    // failure instead of the real device's null return, so this row cannot be
    // exercised by a JVM test in this harness. Verified by inspection instead:
    // ImageUtils.downscaleToJpeg throws IOException whenever the sampled
    // decode result is null (see the `if (sampled == null)` guard), which
    // PhotoCaptureController.processImage catches and turns into onPhotoError
    // — never a crash.

    @Test
    public void downscaleToJpeg_missingSource_throwsIOException() throws IOException {
        File missing = new File(tempFolder.getRoot(), "does-not-exist.jpg");
        File destDir = tempFolder.newFolder("out-missing");

        try {
            ImageUtils.downscaleToJpeg(application, Uri.fromFile(missing), destDir);
            fail("Expected IOException for a missing source.");
        } catch (IOException expected) {
            // expected
        }
    }

    private File createJpegFile(String name, int width, int height) throws IOException {
        File file = tempFolder.newFile(name);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    /** {@code [width, height]} of the decoded file, bounds-only (no full decode). */
    private int[] decodedSize(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return new int[] {options.outWidth, options.outHeight};
    }
}

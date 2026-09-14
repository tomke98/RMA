package hr.ferit.lostandfound.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.Task;

/**
 * Sole owner of the one-shot Fused location fix (AD-10). Two callers only:
 * {@code ui.report.LocationPickerActivity} (this story) and {@code BoardActivity}
 * (Story 3.2) — both need the identical "one foreground fix or Osijek" behaviour,
 * so it lives here rather than duplicated per-screen. No continuous updates, no
 * {@code LocationRequest} loop, no background permission.
 *
 * <p>Holds no {@code Activity} state: takes a {@link Context} and a callback, so it
 * survives being called from either screen without adapting to either one's
 * lifecycle beyond "call while resumed".
 */
public final class LocationHelper {

    private static final String TAG = "LocationHelper";

    private LocationHelper() {
    }

    /** Delivers a nullable fix on the main thread. Null means "no fix available". */
    public interface FixCallback {
        void onFix(@Nullable LatLng location);
    }

    /** {@code true} when either FINE or COARSE location is currently granted. */
    public static boolean hasLocationPermission(@NonNull Context context) {
        boolean fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    /**
     * Requests exactly one foreground fix: {@code getCurrentLocation} (fresh,
     * honours a cold GPS) falling back to {@code getLastLocation} when it returns
     * null (emulator, location just toggled on). Both can be null — the caller
     * treats that as "no fix", not an error, and falls back to
     * {@link Constants#OSIJEK_CENTER}.
     *
     * <p>Caller must already hold FINE or COARSE permission — check with
     * {@link #hasLocationPermission(Context)} first; this method does not request
     * it.
     */
    @MainThread
    @SuppressWarnings("MissingPermission") // caller-checked, see Javadoc above
    public static void requestSingleFix(@NonNull Context context, @NonNull FixCallback callback) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "requestSingleFix called without location permission; returning null.");
            callback.onFix(null);
            return;
        }

        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);
        CancellationTokenSource cancellationSource = new CancellationTokenSource();

        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationSource.getToken())
                    .addOnCompleteListener(ContextCompat.getMainExecutor(context), currentTask -> {
                        LatLng fresh = toLatLng(currentTask);
                        if (fresh != null) {
                            callback.onFix(fresh);
                            return;
                        }
                        // getCurrentLocation returned null or failed — fall back to the
                        // last-known fix (e.g. emulator, GPS just toggled on).
                        try {
                            client.getLastLocation()
                                    .addOnCompleteListener(ContextCompat.getMainExecutor(context), lastTask -> {
                                        LatLng last = toLatLng(lastTask);
                                        if (last == null) {
                                            Log.w(TAG, "No location fix available (current + last both null).");
                                        }
                                        callback.onFix(last);
                                    });
                        } catch (SecurityException e) {
                            // Permission revoked between the hasLocationPermission()
                            // check and this call — treat like any other failed fix.
                            Log.w(TAG, "Location permission revoked mid-request (getLastLocation).", e);
                            callback.onFix(null);
                        }
                    });
        } catch (SecurityException e) {
            // Permission revoked between the hasLocationPermission() check above
            // and this call — treat like any other failed fix, not a crash (AD-14).
            Log.w(TAG, "Location permission revoked mid-request (getCurrentLocation).", e);
            callback.onFix(null);
        }
    }

    /** Great-circle distance between two points in metres, per {@link
     * android.location.Location#distanceBetween}. Used by {@code
     * ui.board.BoardListAdapter} (Story 3.3) to show each row's distance from
     * the same one-shot fix the Board's map already resolved — no second
     * fix request. */
    public static float distanceMeters(@NonNull LatLng a, @NonNull LatLng b) {
        float[] result = new float[1];
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result);
        return result[0];
    }

    @Nullable
    private static LatLng toLatLng(@NonNull Task<android.location.Location> task) {
        if (!task.isSuccessful()) {
            Log.w(TAG, "Location task failed.", task.getException());
            return null;
        }
        android.location.Location location = task.getResult();
        if (location == null) {
            return null;
        }
        return new LatLng(location.getLatitude(), location.getLongitude());
    }
}

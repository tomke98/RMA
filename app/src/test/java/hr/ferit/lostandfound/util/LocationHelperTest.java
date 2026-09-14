package hr.ferit.lostandfound.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.os.Build;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Verifies {@link LocationHelper#hasLocationPermission(android.content.Context)}'s
 * FINE-or-COARSE rule (frozen boundary: an Android 12+ COARSE-only grant counts as
 * granted). Uses Robolectric's shadow permission APIs so no device is needed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class LocationHelperTest {

    private final Application application = RuntimeEnvironment.getApplication();

    @Test
    public void hasLocationPermission_fineGranted_returnsTrue() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);

        assertTrue(LocationHelper.hasLocationPermission(application));
    }

    @Test
    public void hasLocationPermission_coarseOnlyGranted_returnsTrue() {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);

        assertTrue(LocationHelper.hasLocationPermission(application));
    }

    @Test
    public void hasLocationPermission_neitherGranted_returnsFalse() {
        Shadows.shadowOf(application).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);

        assertFalse(LocationHelper.hasLocationPermission(application));
    }

    @Test
    public void distanceMeters_knownCoordinatePair_matchesHandComputedDistance() {
        // Two Osijek-area points 0.01 degrees of latitude apart, same longitude.
        // 1 degree of latitude is ~111 km everywhere on Earth, so 0.01 degree is
        // ~1110 m; a 50 m delta covers the gap between that spherical estimate
        // and Location#distanceBetween's ellipsoidal (WGS84) calculation.
        LatLng a = new LatLng(45.5550d, 18.6955d);
        LatLng b = new LatLng(45.5650d, 18.6955d);

        float distance = LocationHelper.distanceMeters(a, b);

        assertEquals(1110f, distance, 50f);
    }
}

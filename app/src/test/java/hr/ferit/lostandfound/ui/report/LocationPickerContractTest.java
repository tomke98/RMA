package hr.ferit.lostandfound.ui.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Verifies {@link LocationPickerActivity.Contract#createIntent} /
 * {@link LocationPickerActivity.Contract#parseResult} round-trip, per the spec's
 * Unit-test task and I/O matrix rows that don't need a device: seed in -> intent
 * extras, {@code RESULT_OK} + extras -> {@link PickedLocation}, and
 * {@code RESULT_CANCELED} -> null. Uses Robolectric (rather than a fully plain JVM
 * test) because {@code Intent} extras and {@code LatLng} (a real {@code Parcelable})
 * need a functioning Android runtime to round-trip.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class LocationPickerContractTest {

    private final LocationPickerActivity.Contract contract = new LocationPickerActivity.Contract();

    @Test
    public void createIntent_withSeed_putsSeedExtras() {
        LatLng seed = new LatLng(45.5d, 18.6d);

        Intent intent = contract.createIntent(RuntimeEnvironment.getApplication(), seed);

        assertTrue(intent.hasExtra(LocationPickerActivity.EXTRA_SEED_LAT));
        assertTrue(intent.hasExtra(LocationPickerActivity.EXTRA_SEED_LNG));
        assertEquals(45.5d, intent.getDoubleExtra(LocationPickerActivity.EXTRA_SEED_LAT, 0d), 0d);
        assertEquals(18.6d, intent.getDoubleExtra(LocationPickerActivity.EXTRA_SEED_LNG, 0d), 0d);
    }

    @Test
    public void createIntent_withNullSeed_carriesNoSeedExtras() {
        Intent intent = contract.createIntent(RuntimeEnvironment.getApplication(), null);

        assertFalse(intent.hasExtra(LocationPickerActivity.EXTRA_SEED_LAT));
        assertFalse(intent.hasExtra(LocationPickerActivity.EXTRA_SEED_LNG));
    }

    @Test
    public void parseResult_ok_returnsPickedLocation() {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(LocationPickerActivity.EXTRA_LAT, 45.55d);
        resultIntent.putExtra(LocationPickerActivity.EXTRA_LNG, 18.69d);
        resultIntent.putExtra(LocationPickerActivity.EXTRA_LABEL, "Kod fontane");

        PickedLocation result = contract.parseResult(Activity.RESULT_OK, resultIntent);

        assertEquals(45.55d, result.getLat(), 0d);
        assertEquals(18.69d, result.getLng(), 0d);
        assertEquals("Kod fontane", result.getLabel());
    }

    @Test
    public void parseResult_ok_withBlankLabelExtraAbsent_returnsNullLabel() {
        // confirm() coalesces a blank field to null before setResult, but always
        // calls putExtra(EXTRA_LABEL, label) — so the real intent carries the key
        // with a null value, not an absent key. This test exercises the absent-key
        // case directly; either shape must parse to a null label.
        Intent resultIntent = new Intent();
        resultIntent.putExtra(LocationPickerActivity.EXTRA_LAT, 45.55d);
        resultIntent.putExtra(LocationPickerActivity.EXTRA_LNG, 18.69d);

        PickedLocation result = contract.parseResult(Activity.RESULT_OK, resultIntent);

        assertNull(result.getLabel());
    }

    @Test
    public void parseResult_canceled_returnsNull() {
        PickedLocation result = contract.parseResult(Activity.RESULT_CANCELED, new Intent());

        assertNull(result);
    }

    @Test
    public void parseResult_okWithNullIntent_returnsNull() {
        PickedLocation result = contract.parseResult(Activity.RESULT_OK, null);

        assertNull(result);
    }
}

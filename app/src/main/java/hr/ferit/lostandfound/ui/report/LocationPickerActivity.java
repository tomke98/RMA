package hr.ferit.lostandfound.ui.report;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.textfield.TextInputEditText;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.util.Constants;
import hr.ferit.lostandfound.util.LocationHelper;

/**
 * Standalone map screen (AD-9) that lets the user set a report's
 * {@code { lat, lng, locationLabel? }}. Reached only through {@link Contract} via
 * {@code registerForActivityResult}, never {@code androidx.navigation}. No
 * {@code com.google.firebase.*} import, no repository call, no {@code ViewModel} —
 * this screen has no data-layer interaction (follows the {@code BiometricLockActivity}
 * / {@code BoardActivity} no-VM precedent).
 *
 * <p><b>Pin model (frozen decision):</b> the pin is a static, non-interactive
 * {@code ImageView} fixed at the screen centre; the user repositions it by panning
 * the map underneath, not by dragging a {@code Marker}. The confirmed coordinate is
 * {@code GoogleMap.getCameraPosition().target} read at the instant the user taps
 * {@code Postavi lokaciju} — never cached from a camera-idle callback.
 *
 * <p>Location comes from exactly one foreground {@link LocationHelper} fix per
 * request, requested only while this screen is resumed. Permission denial, a
 * dismissed system dialog, or a null fix all fall back to
 * {@link Constants#OSIJEK_CENTER} with the {@code lokacija_bez_dozvole} rationale
 * line — the pin stays fully usable and "Trenutna lokacija" can always retry
 * (AD-10 / no-dead-end).
 */
public class LocationPickerActivity extends AppCompatActivity {

    private static final String TAG = "LocationPickerActivity";

    /** Camera zoom when centred on a real fix (device or seed). */
    public static final float ZOOM_FIX = 16f;

    /** Camera zoom when centred on the {@link Constants#OSIJEK_CENTER} fallback. */
    public static final float ZOOM_FALLBACK = 13f;

    public static final String EXTRA_SEED_LAT = "hr.ferit.lostandfound.extra.LOCATION_SEED_LAT";
    public static final String EXTRA_SEED_LNG = "hr.ferit.lostandfound.extra.LOCATION_SEED_LNG";
    public static final String EXTRA_LAT = "hr.ferit.lostandfound.extra.LOCATION_LAT";
    public static final String EXTRA_LNG = "hr.ferit.lostandfound.extra.LOCATION_LNG";
    public static final String EXTRA_LABEL = "hr.ferit.lostandfound.extra.LOCATION_LABEL";

    private static final String STATE_FIX_ATTEMPTED = "fixAttempted";
    private static final String STATE_SHOW_RATIONALE = "showRationale";

    @Nullable
    private GoogleMap map;

    private TextView rationaleText;
    private TextInputEditText labelInput;
    private MaterialButton confirmButton;
    private Chip currentLocationButton;

    @Nullable
    private Double seedLat;
    @Nullable
    private Double seedLng;

    /**
     * {@code true} once a fix (or the fallback) has been attempted for this
     * screen instance. Guards against re-firing the permission request / Fused
     * call on rotation (config-change safety); survives rotation via
     * {@link #onSaveInstanceState}. Not touched by the "Trenutna lokacija"
     * control, which may always retry.
     */
    private boolean fixAttempted;

    /** Mirrors the rationale line's visibility; a plain {@code TextView} does not
     * save this itself, so it is carried explicitly across rotation. */
    private boolean showRationale;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> onPermissionResult());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_picker);

        if (savedInstanceState != null) {
            fixAttempted = savedInstanceState.getBoolean(STATE_FIX_ATTEMPTED, false);
            showRationale = savedInstanceState.getBoolean(STATE_SHOW_RATIONALE, false);
        }

        if (getIntent().hasExtra(EXTRA_SEED_LAT) && getIntent().hasExtra(EXTRA_SEED_LNG)) {
            seedLat = getIntent().getDoubleExtra(EXTRA_SEED_LAT, 0d);
            seedLng = getIntent().getDoubleExtra(EXTRA_SEED_LNG, 0d);
        }

        rationaleText = findViewById(R.id.rationaleText);
        labelInput = findViewById(R.id.labelInput);
        applyRationaleVisibility();

        // android:accessibilityHeading is API 28+; back it with the AndroidX call
        // so the screen title is a heading on minSdk 24-27 too.
        ViewCompat.setAccessibilityHeading(findViewById(R.id.screenTitle), true);

        confirmButton = findViewById(R.id.confirmButton);
        confirmButton.setEnabled(false); // enabled once onMapReady fires
        confirmButton.setOnClickListener(v -> confirm());

        currentLocationButton = findViewById(R.id.currentLocationButton);
        currentLocationButton.setOnClickListener(v -> attemptFixOrPermission());

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this::onMapReady);
        } else {
            // Should never happen — the fragment is declared in the layout — but
            // fail soft rather than NPE (AD-14).
            Log.w(TAG, "SupportMapFragment missing from activity_location_picker.xml.");
        }
    }

    private void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        confirmButton.setEnabled(true);

        if (seedLat != null && seedLng != null && !fixAttempted) {
            // Launched with a seed (Story 2.4 GPS-seed / re-edit): start there, no
            // automatic fix request. "Trenutna lokacija" is still available. Guarded
            // by !fixAttempted so a rotation after the user has already panned away
            // from the seed does not snap the camera back to it.
            fixAttempted = true;
            moveCamera(new LatLng(seedLat, seedLng), ZOOM_FIX);
        } else if (seedLat == null && !fixAttempted) {
            fixAttempted = true;
            attemptFixOrPermission();
        }
        // else: rotation with a fix (or seed) already attempted — the map
        // fragment retains its own camera position across the configuration
        // change, so nothing to do here beyond the rationale line already
        // re-applied in onCreate.
    }

    /** Requests permission if needed, then a fix; always safe to call again — this
     * is what "Trenutna lokacija" re-invokes after a prior denial (no dead end).
     * Disables the chip for the duration of the request so repeated taps can't
     * race two in-flight requests; re-enabled on both terminal outcomes. */
    private void attemptFixOrPermission() {
        currentLocationButton.setEnabled(false);
        if (LocationHelper.hasLocationPermission(this)) {
            requestFix();
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void onPermissionResult() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // Ignore the raw single-permission boolean: on Android 12+ the user may
        // grant COARSE only (the "approximate" choice) while FINE reads denied.
        // hasLocationPermission() ORs both, matching the "COARSE-only is granted"
        // rule. A dismissed dialog reads as denied either way.
        if (LocationHelper.hasLocationPermission(this)) {
            requestFix();
        } else {
            Log.w(TAG, "Location permission denied or dismissed; falling back to Osijek centre.");
            showFallback();
        }
    }

    private void requestFix() {
        LocationHelper.requestSingleFix(this, latLng -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (latLng != null) {
                showRationale = false;
                applyRationaleVisibility();
                moveCamera(latLng, ZOOM_FIX);
                currentLocationButton.setEnabled(true);
            } else {
                showFallback();
            }
        });
    }

    private void showFallback() {
        showRationale = true;
        applyRationaleVisibility();
        moveCamera(Constants.OSIJEK_CENTER, ZOOM_FALLBACK);
        rationaleText.announceForAccessibility(getString(R.string.lokacija_bez_dozvole));
        currentLocationButton.setEnabled(true);
    }

    private void applyRationaleVisibility() {
        rationaleText.setVisibility(showRationale ? View.VISIBLE : View.GONE);
    }

    private void moveCamera(@NonNull LatLng target, float zoom) {
        if (map != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom));
        }
    }

    private void confirm() {
        if (map == null) {
            // Map not ready yet; nothing to confirm from. Practically unreachable
            // since the confirm button is visible only after layout, well after
            // getMapAsync typically resolves, but guarded rather than NPE-ing.
            Log.w(TAG, "Confirm tapped before the map was ready; ignoring.");
            return;
        }

        LatLng target = map.getCameraPosition().target;
        String label = labelInput.getText() == null ? null : labelInput.getText().toString().trim();
        if (TextUtils.isEmpty(label)) {
            label = null;
        }

        Intent result = new Intent();
        result.putExtra(EXTRA_LAT, target.latitude);
        result.putExtra(EXTRA_LNG, target.longitude);
        result.putExtra(EXTRA_LABEL, label);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_FIX_ATTEMPTED, fixAttempted);
        outState.putBoolean(STATE_SHOW_RATIONALE, showRationale);
    }

    // System Back / up: no setResult() call happens on that path, so the default
    // RESULT_CANCELED stands and finish() runs via the platform's default back
    // handling — the caller keeps whatever location it already held.

    /**
     * Typed transport for launching this screen with
     * {@code registerForActivityResult}. Input is an optional seed {@link LatLng}
     * (null lets the screen self-seed from a fix or the Osijek fallback); output is
     * a {@link PickedLocation}, or null when the user backs out without confirming.
     */
    public static final class Contract extends ActivityResultContract<LatLng, PickedLocation> {

        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, @Nullable LatLng seed) {
            Intent intent = new Intent(context, LocationPickerActivity.class);
            if (seed != null) {
                intent.putExtra(EXTRA_SEED_LAT, seed.latitude);
                intent.putExtra(EXTRA_SEED_LNG, seed.longitude);
            }
            return intent;
        }

        @Nullable
        @Override
        public PickedLocation parseResult(int resultCode, @Nullable Intent intent) {
            if (resultCode != Activity.RESULT_OK || intent == null) {
                return null;
            }
            double lat = intent.getDoubleExtra(EXTRA_LAT, 0d);
            double lng = intent.getDoubleExtra(EXTRA_LNG, 0d);
            String label = intent.getStringExtra(EXTRA_LABEL);
            return new PickedLocation(lat, lng, label);
        }
    }
}

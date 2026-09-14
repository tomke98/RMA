package hr.ferit.lostandfound.ui.board;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.List;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.ui.categories.FollowedCategoriesActivity;
import hr.ferit.lostandfound.ui.detail.ReportDetailActivity;
import hr.ferit.lostandfound.ui.entry.EntryActivity;
import hr.ferit.lostandfound.ui.report.CreateReportActivity;
import hr.ferit.lostandfound.ui.reports.MyReportsActivity;
import hr.ferit.lostandfound.util.Connectivity;
import hr.ferit.lostandfound.util.Constants;
import hr.ferit.lostandfound.util.LocationHelper;
import hr.ferit.lostandfound.util.ReportDisplay;
import hr.ferit.lostandfound.util.ServiceLocator;
import hr.ferit.lostandfound.util.ViewModelFactory;

/**
 * The Board (Story 3.2, AD-3/AD-17): the signed-in landing screen. A {@link
 * SupportMapFragment} renders one marker per {@code status == "open"} report
 * (lost vs found distinguished by colour + a short Croatian text glyph, never
 * colour-only), centred on a one-shot GPS fix or {@link Constants#OSIJEK_CENTER}.
 * A {@link MaterialToolbar} carries the overflow sign-out item and an {@link
 * ExtendedFloatingActionButton} "Prijavi" opens a {@link BottomSheetDialog}
 * with the two existing report-type actions, replacing the Epic-2 temporary
 * buttons this Activity used to show.
 *
 * <p>Map setup mirrors {@code ui.report.LocationPickerActivity}: {@link
 * SupportMapFragment#getMapAsync}, {@link LocationHelper} for the one-shot fix
 * + fallback, and an {@link ActivityResultLauncher} for the permission
 * request. Report data comes from exactly one {@link BoardViewModel}, itself a
 * thin wrapper over the process-wide {@code BoardRepository} singleton — no
 * per-screen Firestore listener here.
 *
 * <p>Story 4.2 requests {@code POST_NOTIFICATIONS} (API 33+) on first entry,
 * via a second {@link ActivityResultLauncher} that exactly mirrors the
 * location-permission flow: a rationale banner shown only after a denial.
 */
public class BoardActivity extends AppCompatActivity {

    private static final String TAG = "BoardActivity";

    /** Camera zoom when centred on a real fix. Matches LocationPickerActivity. */
    private static final float ZOOM_FIX = 15f;

    /** Camera zoom when centred on the {@link Constants#OSIJEK_CENTER} fallback. */
    private static final float ZOOM_FALLBACK = 13f;

    private static final String STATE_FIX_ATTEMPTED = "fixAttempted";
    private static final String STATE_SHOW_LOCATION_RATIONALE = "showLocationRationale";
    private static final String STATE_LEGEND_DISMISSED = "legendDismissed";
    private static final String STATE_ACTIVE_PANE = "activePane";
    private static final String STATE_CURRENT_FIX = "currentFix";
    private static final String STATE_NOTIFICATION_PERMISSION_ATTEMPTED = "notificationPermissionAttempted";
    private static final String STATE_SHOW_NOTIFICATION_RATIONALE = "showNotificationRationale";

    /** Retained so it can be dismissed in {@link #onDestroy()} (no window leak on rotation). */
    @Nullable
    private AlertDialog signOutDialog;

    /** Retained so it can be dismissed in {@link #onDestroy()} (no window leak on rotation). */
    @Nullable
    private BottomSheetDialog prijaviSheet;

    private BoardViewModel viewModel;

    @Nullable
    private GoogleMap map;

    private CircularProgressIndicator progress;
    private View errorGroup;
    private TextView errorText;
    private MaterialButton retryButton;
    private TextView emptyText;
    private ChipGroup legendGroup;
    private ImageButton legendCloseButton;
    private View locationRationaleGroup;
    private MaterialButton enableLocationButton;
    private View notificationRationaleGroup;
    private MaterialButton enableNotificationsButton;
    private ExtendedFloatingActionButton fab;

    /** Karta/Popis toggle (Story 3.3): both panes stay alive and fed from the
     * same {@link BoardViewModel} stream; toggling only flips visibility. */
    private MaterialButtonToggleGroup viewToggleGroup;
    private RecyclerView reportList;
    private BoardListAdapter listAdapter;

    /** Story 3.4: "Zadnje ažurirano" label, independent of the LOADING/SUCCESS/
     * ERROR switch in {@link #render}; visible iff offline and a synced
     * timestamp exists. */
    private TextView offlineLabel;

    /** Most recent {@code max(syncedAt)} from the Room mirror, {@code null}
     * while no cache exists yet. */
    @Nullable
    private Long lastSyncedAtMillis;

    /** {@code true} once "Popis" is the active pane; survives rotation via
     * {@link #onSaveInstanceState}, defaulting to {@code false} (map) on a
     * fresh instance. */
    private boolean listPaneActive;

    /** Cached, built once and reused across every lost/found marker (never per marker). */
    private BitmapDescriptor lostMarkerIcon;
    private BitmapDescriptor foundMarkerIcon;

    /**
     * {@code true} once a fix (or the fallback) has been attempted for this
     * screen instance. Guards against re-firing the permission request / Fused
     * call on rotation; survives rotation via {@link #onSaveInstanceState}.
     * "Omogući lokaciju" always bypasses this guard (no dead end).
     */
    private boolean fixAttempted;

    /** Mirrors the location-rationale row's visibility across rotation. */
    private boolean showLocationRationale;

    /** Guards {@link #attemptNotificationPermissionIfNeeded()} against
     * re-firing the POST_NOTIFICATIONS request on rotation; survives rotation
     * via {@link #onSaveInstanceState} (Story 4.2, mirrors {@link #fixAttempted}). */
    private boolean notificationPermissionAttempted;

    /** Mirrors the notification-rationale row's visibility across rotation. */
    private boolean showNotificationRationale;

    /** Legend visibility for this Activity instance only — no persistence
     * (survives rotation because it is saved/restored the same as the other UI
     * booleans here, but a fresh sign-in/new instance always starts shown). */
    private boolean legendDismissed;

    /** The most recent report list, cached so a redraw can happen once the map
     * becomes ready even if data arrived first. */
    @Nullable
    private List<Report> pendingReports;

    /** The most recent one-shot GPS fix, used only to compute each list row's
     * distance (Story 3.3); never a second {@link LocationHelper#requestSingleFix}
     * call — set from the same fix the map already resolves. */
    @Nullable
    private LatLng currentFix;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> onPermissionResult());

    /** Story 4.2: mirrors {@link #permissionLauncher} exactly, for POST_NOTIFICATIONS. */
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), this::onNotificationPermissionResult);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_board);

        if (savedInstanceState != null) {
            fixAttempted = savedInstanceState.getBoolean(STATE_FIX_ATTEMPTED, false);
            showLocationRationale = savedInstanceState.getBoolean(STATE_SHOW_LOCATION_RATIONALE, false);
            legendDismissed = savedInstanceState.getBoolean(STATE_LEGEND_DISMISSED, false);
            listPaneActive = savedInstanceState.getBoolean(STATE_ACTIVE_PANE, false);
            currentFix = savedInstanceState.getParcelable(STATE_CURRENT_FIX);
            notificationPermissionAttempted =
                    savedInstanceState.getBoolean(STATE_NOTIFICATION_PERMISSION_ATTEMPTED, false);
            showNotificationRationale = savedInstanceState.getBoolean(STATE_SHOW_NOTIFICATION_RATIONALE, false);
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItemClick);

        progress = findViewById(R.id.progress);
        errorGroup = findViewById(R.id.errorGroup);
        errorText = findViewById(R.id.errorText);
        retryButton = findViewById(R.id.retryButton);
        emptyText = findViewById(R.id.emptyText);
        legendGroup = findViewById(R.id.legendGroup);
        legendCloseButton = findViewById(R.id.legendCloseButton);
        locationRationaleGroup = findViewById(R.id.locationRationaleGroup);
        enableLocationButton = findViewById(R.id.enableLocationButton);
        notificationRationaleGroup = findViewById(R.id.notificationRationaleGroup);
        enableNotificationsButton = findViewById(R.id.enableNotificationsButton);
        fab = findViewById(R.id.fab);
        viewToggleGroup = findViewById(R.id.viewToggleGroup);
        reportList = findViewById(R.id.reportList);
        offlineLabel = findViewById(R.id.offlineLabel);

        applyLocationRationaleVisibility();
        applyNotificationRationaleVisibility();
        applyLegendVisibility();

        retryButton.setOnClickListener(v -> viewModel.retry());
        enableLocationButton.setOnClickListener(v -> attemptFixOrPermission());
        enableNotificationsButton.setOnClickListener(v -> requestNotificationPermission());
        legendCloseButton.setOnClickListener(v -> {
            legendDismissed = true;
            applyLegendVisibility();
        });
        fab.setOnClickListener(v -> showPrijaviSheet());

        setUpListPane();
        if (currentFix != null) {
            listAdapter.setCurrentFix(currentFix);
        }

        buildMarkerIcons();
        announceMarkerCount(0);

        attemptNotificationPermissionIfNeeded();

        viewModel = new ViewModelProvider(this, new ViewModelFactory()).get(BoardViewModel.class);
        viewModel.resource().observe(this, this::render);
        viewModel.lastSyncedAt().observe(this, this::onLastSyncedAtChanged);

        SupportMapFragment mapFragment =
                (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this::onMapReady);
        } else {
            // Should never happen — the fragment is declared in the layout — but
            // fail soft rather than NPE (AD-14).
            Log.w(TAG, "SupportMapFragment missing from activity_board.xml.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-evaluates connectivity on return to foreground (e.g. airplane mode
        // toggled while backgrounded) without any new connectivity plumbing —
        // reuses the same Connectivity.isOnline() gate as everywhere else.
        applyOfflineLabelVisibility();
    }

    private boolean onToolbarMenuItemClick(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_sign_out) {
            confirmSignOut();
            return true;
        } else if (item.getItemId() == R.id.action_followed_categories) {
            startActivity(new Intent(this, FollowedCategoriesActivity.class));
            return true;
        } else if (item.getItemId() == R.id.action_my_reports) {
            startActivity(new Intent(this, MyReportsActivity.class));
            return true;
        }
        return false;
    }

    // ---- Karta/Popis toggle (Story 3.3) -------------------------------------

    private void setUpListPane() {
        listAdapter = new BoardListAdapter(this::onListRowClick);
        reportList.setLayoutManager(new LinearLayoutManager(this));
        reportList.setAdapter(listAdapter);

        viewToggleGroup.check(listPaneActive ? R.id.listToggleButton : R.id.mapToggleButton);
        applyActivePaneVisibility();

        viewToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            // Never re-subscribes to BoardViewModel: both panes are already fed
            // by the same stream, this only flips which one is visible.
            listPaneActive = checkedId == R.id.listToggleButton;
            applyActivePaneVisibility();
        });
    }

    private void applyActivePaneVisibility() {
        reportList.setVisibility(listPaneActive ? View.VISIBLE : View.GONE);
        Fragment mapFragment = getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null && mapFragment.getView() != null) {
            mapFragment.getView().setVisibility(listPaneActive ? View.GONE : View.VISIBLE);
        }
    }

    private void onListRowClick(@NonNull String reportId) {
        Intent intent = new Intent(this, ReportDetailActivity.class);
        intent.putExtra(Constants.EXTRA_REPORT_ID, reportId);
        startActivity(intent);
    }

    // ---- Map setup (mirrors LocationPickerActivity) ------------------------

    private void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.setOnMarkerClickListener(this::onMarkerClick);

        if (!fixAttempted) {
            fixAttempted = true;
            attemptFixOrPermission();
        } else if (showLocationRationale) {
            moveCamera(Constants.OSIJEK_CENTER, ZOOM_FALLBACK);
        }
        // else: rotation with a fix already attempted and no rationale showing —
        // the map fragment retains its own camera position across the
        // configuration change, so nothing to do here.

        if (pendingReports != null) {
            redrawMarkers(pendingReports);
        }
    }

    /** Requests permission if needed, then a fix; always safe to call again —
     * this is what "Omogući lokaciju" re-invokes after a prior denial. */
    private void attemptFixOrPermission() {
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
        // grant COARSE only. hasLocationPermission() ORs both.
        if (LocationHelper.hasLocationPermission(this)) {
            requestFix();
        } else {
            Log.w(TAG, "Location permission denied or dismissed; falling back to Osijek centre.");
            showLocationDenied();
        }
    }

    private void requestFix() {
        LocationHelper.requestSingleFix(this, latLng -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (latLng != null) {
                showLocationRationale = false;
                applyLocationRationaleVisibility();
                moveCamera(latLng, ZOOM_FIX);
                currentFix = latLng;
                listAdapter.setCurrentFix(currentFix);
                listAdapter.notifyDataSetChanged();
            } else {
                showLocationDenied();
            }
        });
    }

    private void showLocationDenied() {
        showLocationRationale = true;
        applyLocationRationaleVisibility();
        moveCamera(Constants.OSIJEK_CENTER, ZOOM_FALLBACK);
    }

    private void applyLocationRationaleVisibility() {
        applyRationaleBannerVisibility();
    }

    // ---- POST_NOTIFICATIONS permission (Story 4.2) -------------------------
    // Mirrors the location-permission flow above exactly: an
    // ActivityResultLauncher<String> request, with a rationale banner shown
    // only after a denial.

    /** Requests POST_NOTIFICATIONS on first entry to this screen instance,
     * API 33+ only (no runtime prompt exists or is needed below API 33). A
     * no-op if the permission is already granted, or already attempted this
     * screen instance (guarded by {@link #notificationPermissionAttempted},
     * survives rotation). */
    private void attemptNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (notificationPermissionAttempted) {
            return;
        }
        notificationPermissionAttempted = true;
        if (hasNotificationPermission()) {
            return;
        }
        requestNotificationPermission();
    }

    /** Also what "Omogući obavijesti" re-invokes after a prior denial. */
    private void requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private boolean hasNotificationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onNotificationPermissionResult(boolean granted) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!granted) {
            Log.w(TAG, "Notification permission denied or dismissed; showing rationale.");
        }
        showNotificationRationale = !granted;
        applyNotificationRationaleVisibility();
    }

    private void applyNotificationRationaleVisibility() {
        applyRationaleBannerVisibility();
    }

    /** {@code bottomRationaleContainer} shows at most one rationale banner at a
     * time -- the notification banner takes priority over the location banner
     * -- so a user who has denied both permissions never sees the combined,
     * double-height banner crowd the FAB. */
    private void applyRationaleBannerVisibility() {
        notificationRationaleGroup.setVisibility(showNotificationRationale ? View.VISIBLE : View.GONE);
        boolean showLocation = showLocationRationale && !showNotificationRationale;
        locationRationaleGroup.setVisibility(showLocation ? View.VISIBLE : View.GONE);
    }

    private void applyLegendVisibility() {
        int visibility = legendDismissed ? View.GONE : View.VISIBLE;
        legendGroup.setVisibility(visibility);
        legendCloseButton.setVisibility(visibility);
    }

    private void moveCamera(@NonNull LatLng target, float zoom) {
        if (map != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom));
        }
    }

    // ---- Report data (BoardViewModel) --------------------------------------

    private void render(@Nullable Resource<List<Report>> resource) {
        if (resource == null) {
            return;
        }
        switch (resource.status) {
            case LOADING:
                progress.setVisibility(View.VISIBLE);
                errorGroup.setVisibility(View.GONE);
                emptyText.setVisibility(View.GONE);
                break;
            case SUCCESS:
                progress.setVisibility(View.GONE);
                errorGroup.setVisibility(View.GONE);
                List<Report> reports = resource.data;
                emptyText.setVisibility(reports == null || reports.isEmpty() ? View.VISIBLE : View.GONE);
                redrawMarkers(reports);
                listAdapter.submitReports(reports);
                break;
            case ERROR:
                progress.setVisibility(View.GONE);
                emptyText.setVisibility(View.GONE);
                errorGroup.setVisibility(View.VISIBLE);
                if (map != null) {
                    map.clear();
                }
                listAdapter.submitReports(null);
                break;
        }
    }

    /** Story 3.4: independent of {@link #render}'s LOADING/SUCCESS/ERROR switch
     * — connectivity, not {@code resource.status}, gates {@link #offlineLabel}. */
    private void onLastSyncedAtChanged(@Nullable Long syncedAtMillis) {
        lastSyncedAtMillis = syncedAtMillis;
        applyOfflineLabelVisibility();
    }

    private void applyOfflineLabelVisibility() {
        if (lastSyncedAtMillis != null && !Connectivity.isOnline(this)) {
            offlineLabel.setText(getString(
                    R.string.zadnje_azurirano, ReportDisplay.relativeSyncTime(lastSyncedAtMillis)));
            offlineLabel.setVisibility(View.VISIBLE);
        } else {
            offlineLabel.setVisibility(View.GONE);
        }
    }

    private void redrawMarkers(@Nullable List<Report> reports) {
        if (map == null) {
            pendingReports = reports;
            return;
        }
        pendingReports = null;
        map.clear();
        int count = 0;
        if (reports != null) {
            for (Report report : reports) {
                boolean found = CreateReportActivity.REPORT_TYPE_FOUND.equals(report.getType());
                Marker marker = map.addMarker(new MarkerOptions()
                        .position(new LatLng(report.getLat(), report.getLng()))
                        .icon(found ? foundMarkerIcon : lostMarkerIcon)
                        .title(getString(found ? R.string.prijava_tip_pronadeno : R.string.prijava_tip_izgubljeno)));
                if (marker != null) {
                    marker.setTag(report.getId());
                    count++;
                }
            }
        }
        announceMarkerCount(count);
    }

    private void announceMarkerCount(int count) {
        androidx.fragment.app.Fragment mapFragment = getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null && mapFragment.getView() != null) {
            mapFragment.getView().setContentDescription(getString(R.string.ploca_karta_opis, count));
        }
    }

    private boolean onMarkerClick(@NonNull Marker marker) {
        Object tag = marker.getTag();
        if (tag instanceof String) {
            Intent intent = new Intent(this, ReportDetailActivity.class);
            intent.putExtra(Constants.EXTRA_REPORT_ID, (String) tag);
            startActivity(intent);
        }
        // Consume the tap: no default info window, no camera re-centre.
        return true;
    }

    /** Builds the two lost/found marker bitmaps once; cached as fields and
     * reused across every marker of that type (never rebuilt per marker). No
     * icon asset files exist in this project (same constraint spec-3-1 hit for
     * photo placeholders), so a small filled circle + short Croatian glyph is
     * drawn onto a Canvas-backed Bitmap. Colour + glyph + the marker title
     * together satisfy the "never colour-only" rule. */
    private void buildMarkerIcons() {
        lostMarkerIcon = BitmapDescriptorFactory.fromBitmap(createMarkerBitmap(
                this, R.color.status_lost, R.color.on_status_lost, "IZG"));
        foundMarkerIcon = BitmapDescriptorFactory.fromBitmap(createMarkerBitmap(
                this, R.color.status_found, R.color.on_status_found, "PRO"));
    }

    @NonNull
    private static Bitmap createMarkerBitmap(@NonNull Context context,
                                              int backgroundColorRes,
                                              int textColorRes,
                                              @NonNull String glyph) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(40 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        @ColorInt int backgroundColor = ContextCompat.getColor(context, backgroundColorRes);
        @ColorInt int textColor = ContextCompat.getColor(context, textColorRes);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(backgroundColor);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(density * 1.5f);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - borderPaint.getStrokeWidth() / 2f, borderPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(textColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(size * 0.3f);
        textPaint.setFakeBoldText(true);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float textY = size / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(glyph, size / 2f, textY, textPaint);

        return bitmap;
    }

    // ---- "Prijavi" FAB + bottom sheet --------------------------------------

    private void showPrijaviSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_prijavi, null);
        dialog.setContentView(sheetView);
        sheetView.findViewById(R.id.reportLostRow).setOnClickListener(v -> {
            dialog.dismiss();
            launchCreateReport(CreateReportActivity.REPORT_TYPE_LOST);
        });
        sheetView.findViewById(R.id.reportFoundRow).setOnClickListener(v -> {
            dialog.dismiss();
            launchCreateReport(CreateReportActivity.REPORT_TYPE_FOUND);
        });
        prijaviSheet = dialog;
        dialog.show();
    }

    private void launchCreateReport(@NonNull String reportType) {
        Intent intent = new Intent(this, CreateReportActivity.class);
        intent.putExtra(CreateReportActivity.EXTRA_REPORT_TYPE, reportType);
        startActivity(intent);
    }

    // ---- Sign-out (kept verbatim from the Epic-2 placeholder; Story 1.3) ---

    private void confirmSignOut() {
        signOutDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.odjava)
                .setMessage(R.string.odjava_potvrda_tekst)
                .setNegativeButton(R.string.odustani, null)
                .setPositiveButton(R.string.odjava, (dialog, which) -> signOut())
                .show();
    }

    private void signOut() {
        // AD-1: no FirebaseAuth in ui.*. AuthRepository.signOut() only clears the
        // session; App's AuthStateListener (AD-17) runs ServiceLocator.reset().
        ServiceLocator.authRepository().signOut();

        // FirebaseAuth.signOut() clears currentUser synchronously, so the routing
        // below already reads no session. The AuthStateListener's
        // ServiceLocator.reset() is posted async and may land after
        // EntryActivity.route() has rebuilt a singleton — harmless: reset()
        // stops/nulls the per-user listeners (BoardRepository included), so a
        // rebuilt-then-nulled holder is functionally identical.
        //
        // Route through EntryActivity so the AD-12 table decides the next screen
        // (signed out -> SignInActivity). CLEAR_TASK leaves no back-stack path here.
        Intent intent = new Intent(this, EntryActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_FIX_ATTEMPTED, fixAttempted);
        outState.putBoolean(STATE_SHOW_LOCATION_RATIONALE, showLocationRationale);
        outState.putBoolean(STATE_LEGEND_DISMISSED, legendDismissed);
        outState.putBoolean(STATE_ACTIVE_PANE, listPaneActive);
        outState.putParcelable(STATE_CURRENT_FIX, currentFix);
        outState.putBoolean(STATE_NOTIFICATION_PERMISSION_ATTEMPTED, notificationPermissionAttempted);
        outState.putBoolean(STATE_SHOW_NOTIFICATION_RATIONALE, showNotificationRationale);
    }

    @Override
    protected void onDestroy() {
        if (signOutDialog != null && signOutDialog.isShowing()) {
            signOutDialog.dismiss();
        }
        signOutDialog = null;
        if (prijaviSheet != null && prijaviSheet.isShowing()) {
            prijaviSheet.dismiss();
        }
        prijaviSheet = null;
        if (listAdapter != null) {
            listAdapter.shutdown();
        }
        super.onDestroy();
    }
}

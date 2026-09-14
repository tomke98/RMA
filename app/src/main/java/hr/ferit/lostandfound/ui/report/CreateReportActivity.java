package hr.ferit.lostandfound.ui.report;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Category;
import hr.ferit.lostandfound.databinding.ActivityCreateReportBinding;
import hr.ferit.lostandfound.ui.report.ReportViewModel.FieldError;
import hr.ferit.lostandfound.ui.report.ReportViewModel.Outcome;
import hr.ferit.lostandfound.util.Connectivity;
import hr.ferit.lostandfound.util.ViewModelFactory;

/**
 * Single create-report screen, driven by {@link #EXTRA_REPORT_TYPE}: lost
 * (Story 2.3) requires category + description (&gt;= 10 chars) + location,
 * photo optional; found (Story 2.4) requires category + location + photo,
 * description optional. Renders {@link ReportViewModel} state and embeds
 * {@link LocationPickerActivity.Contract} + {@link PhotoCaptureController}
 * exactly as built in Stories 2.1/2.2. Imports no {@code com.google.firebase.*}
 * type: all Firebase work lives behind {@code ReportRepository}.
 */
public class CreateReportActivity extends AppCompatActivity {

    public static final String EXTRA_REPORT_TYPE = "hr.ferit.lostandfound.extra.REPORT_TYPE";
    public static final String REPORT_TYPE_LOST = "lost";
    public static final String REPORT_TYPE_FOUND = "found";

    /** Spinner position 0 is the "choose a category" prompt, not a real value. */
    private static final int CATEGORY_PROMPT_POSITION = 0;

    private static final String STATE_HAS_LOCATION = "hasPickedLocation";
    private static final String STATE_LOCATION_LAT = "pickedLocationLat";
    private static final String STATE_LOCATION_LNG = "pickedLocationLng";
    private static final String STATE_LOCATION_LABEL = "pickedLocationLabel";
    private static final String STATE_PHOTO_LOCAL_PATH = "photoLocalPath";
    private static final String STATE_PENDING_CAMERA_URI = "pendingCameraUri";
    private static final String STATE_PENDING_CAMERA_FILE = "pendingCameraFile";

    private ActivityCreateReportBinding binding;
    private ReportViewModel viewModel;
    private PhotoCaptureController photoCaptureController;

    /** Set in {@link #onCreate} from {@link #EXTRA_REPORT_TYPE}; defaults to lost if absent. */
    private String reportType = REPORT_TYPE_LOST;

    @Nullable
    private PickedLocation pickedLocation;

    @Nullable
    private String photoLocalPath;

    private final ActivityResultLauncher<LatLng> locationPickerLauncher =
            registerForActivityResult(new LocationPickerActivity.Contract(), this::onLocationPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCreateReportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String extraType = getIntent().getStringExtra(EXTRA_REPORT_TYPE);
        reportType = REPORT_TYPE_FOUND.equals(extraType) ? REPORT_TYPE_FOUND : REPORT_TYPE_LOST;

        viewModel = new ViewModelProvider(this, new ViewModelFactory()).get(ReportViewModel.class);
        photoCaptureController = new PhotoCaptureController(this, new PhotoListener());

        ViewCompat.setAccessibilityHeading(binding.screenTitle, true);
        applyModeCopy();

        setUpCategorySpinner();

        binding.setLocationButton.setOnClickListener(v -> launchLocationPicker());
        binding.takePhotoButton.setOnClickListener(v -> photoCaptureController.takePhoto());
        binding.pickPhotoButton.setOnClickListener(v -> photoCaptureController.pickPhoto());
        binding.removePhotoButton.setOnClickListener(v -> clearPhoto());
        binding.changePhotoButton.setOnClickListener(v -> clearPhoto());
        binding.submitButton.setOnClickListener(v -> onSubmit());

        viewModel.fieldErrors().observe(this, this::renderFieldErrors);
        viewModel.outcome().observe(this, this::renderOutcome);

        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
        }
    }

    /** Restores what a rotation or process death would otherwise silently drop:
     * the picked location, an attached photo, and an in-flight camera capture. */
    private void restoreState(@NonNull Bundle savedInstanceState) {
        if (savedInstanceState.getBoolean(STATE_HAS_LOCATION, false)) {
            pickedLocation = new PickedLocation(
                    savedInstanceState.getDouble(STATE_LOCATION_LAT),
                    savedInstanceState.getDouble(STATE_LOCATION_LNG),
                    savedInstanceState.getString(STATE_LOCATION_LABEL));
            binding.locationSummary.setText(getString(R.string.prijava_lokacija_postavljena));
        }

        String restoredPhotoPath = savedInstanceState.getString(STATE_PHOTO_LOCAL_PATH);
        if (restoredPhotoPath != null) {
            applyPhotoAttached(restoredPhotoPath);
        }

        Uri pendingCameraUri;
        File pendingCameraFile;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingCameraUri = savedInstanceState.getParcelable(STATE_PENDING_CAMERA_URI, Uri.class);
            pendingCameraFile = savedInstanceState.getSerializable(STATE_PENDING_CAMERA_FILE, File.class);
        } else {
            pendingCameraUri = savedInstanceState.getParcelable(STATE_PENDING_CAMERA_URI);
            pendingCameraFile = (File) savedInstanceState.getSerializable(STATE_PENDING_CAMERA_FILE);
        }
        if (pendingCameraUri != null) {
            photoCaptureController.restorePendingCamera(pendingCameraUri, pendingCameraFile);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pickedLocation != null) {
            outState.putBoolean(STATE_HAS_LOCATION, true);
            outState.putDouble(STATE_LOCATION_LAT, pickedLocation.getLat());
            outState.putDouble(STATE_LOCATION_LNG, pickedLocation.getLng());
            outState.putString(STATE_LOCATION_LABEL, pickedLocation.getLabel());
        }
        outState.putString(STATE_PHOTO_LOCAL_PATH, photoLocalPath);
        outState.putParcelable(STATE_PENDING_CAMERA_URI, photoCaptureController.getPendingCameraUri());
        outState.putSerializable(STATE_PENDING_CAMERA_FILE, photoCaptureController.getPendingCameraFile());
    }

    /** Swaps the type-specific screen title, photo section title, and description hint. */
    private void applyModeCopy() {
        if (REPORT_TYPE_FOUND.equals(reportType)) {
            binding.screenTitle.setText(R.string.prijava_pronadeno_naslov);
            binding.photoTitle.setText(R.string.prijava_fotografija_naslov_obavezno);
            binding.descriptionLayout.setHint(getString(R.string.prijava_opis_hint_neobavezno));
        } else {
            binding.screenTitle.setText(R.string.prijava_izgubljeno_naslov);
            binding.photoTitle.setText(R.string.prijava_fotografija_naslov);
            binding.descriptionLayout.setHint(getString(R.string.prijava_opis_hint));
        }
    }

    private void setUpCategorySpinner() {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.prijava_kategorija_hint));
        String[] labels = getResources().getStringArray(R.array.kategorije_nazivi);
        items.addAll(Arrays.asList(labels));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.categorySpinner.setAdapter(adapter);
    }

    private void launchLocationPicker() {
        LatLng seed = pickedLocation != null
                ? new LatLng(pickedLocation.getLat(), pickedLocation.getLng())
                : null;
        locationPickerLauncher.launch(seed);
    }

    private void onLocationPicked(@Nullable PickedLocation result) {
        if (result == null) {
            // Back without confirming: keep whatever location was already set.
            return;
        }
        pickedLocation = result;
        binding.locationSummary.setText(getString(R.string.prijava_lokacija_postavljena));
    }

    /** Shows the thumbnail for {@code path} and swaps the attach/attached button
     * groups; shared by a fresh capture and by state restoration after a
     * configuration change or process death. */
    private void applyPhotoAttached(@NonNull String path) {
        photoLocalPath = path;
        binding.photoThumbnail.setImageURI(Uri.fromFile(new File(path)));
        binding.photoThumbnail.setVisibility(View.VISIBLE);
        binding.photoAttachButtons.setVisibility(View.GONE);
        binding.photoAttachedButtons.setVisibility(View.VISIBLE);
    }

    private void clearPhoto() {
        photoLocalPath = null;
        binding.photoThumbnail.setVisibility(View.GONE);
        binding.photoThumbnail.setImageDrawable(null);
        binding.photoAttachButtons.setVisibility(View.VISIBLE);
        binding.photoAttachedButtons.setVisibility(View.GONE);
    }

    private void onSubmit() {
        Category category = selectedCategory();
        Double lat = pickedLocation != null ? pickedLocation.getLat() : null;
        Double lng = pickedLocation != null ? pickedLocation.getLng() : null;
        String locationLabel = pickedLocation != null ? pickedLocation.getLabel() : null;

        viewModel.submit(
                reportType,
                category,
                textOf(binding.descriptionInput),
                lat,
                lng,
                locationLabel,
                photoLocalPath,
                Connectivity.isOnline(this));
    }

    @Nullable
    private Category selectedCategory() {
        int position = binding.categorySpinner.getSelectedItemPosition();
        if (position == Spinner.INVALID_POSITION || position == CATEGORY_PROMPT_POSITION) {
            return null;
        }
        return Category.values()[position - 1];
    }

    private void renderFieldErrors(@Nullable List<FieldError> errors) {
        clearFieldError(binding.categoryError);
        binding.descriptionLayout.setError(null);
        clearFieldError(binding.locationError);
        clearFieldError(binding.photoError);
        if (errors == null || errors.isEmpty()) {
            return;
        }
        for (FieldError error : errors) {
            switch (error.field) {
                case CATEGORY:
                    showFieldError(binding.categoryError, error.messageRes);
                    break;
                case DESCRIPTION:
                    binding.descriptionLayout.setError(getString(error.messageRes));
                    break;
                case LOCATION:
                    showFieldError(binding.locationError, error.messageRes);
                    break;
                case PHOTO:
                    showFieldError(binding.photoError, error.messageRes);
                    break;
                default:
                    break; // New Field values need a matching case here.
            }
        }
    }

    private void renderOutcome(@Nullable Outcome outcome) {
        if (outcome == null) {
            return;
        }
        switch (outcome) {
            case LOADING:
                setFormEnabled(false);
                binding.progress.setVisibility(View.VISIBLE);
                binding.getRoot().announceForAccessibility(getString(R.string.prijava_slanje_u_tijeku));
                break;
            case SUCCESS:
                viewModel.consumeOutcome();
                Snackbar.make(binding.getRoot(), R.string.prijava_uspjeh, Snackbar.LENGTH_LONG).show();
                finish();
                break;
            case OFFLINE:
                viewModel.consumeOutcome();
                restoreForm();
                Snackbar.make(binding.getRoot(), R.string.radnja_zahtijeva_internet, Snackbar.LENGTH_LONG).show();
                break;
            case ERROR:
                viewModel.consumeOutcome();
                restoreForm();
                Snackbar.make(binding.getRoot(), R.string.opca_greska, Snackbar.LENGTH_LONG).show();
                break;
        }
    }

    private void restoreForm() {
        binding.progress.setVisibility(View.INVISIBLE);
        setFormEnabled(true);
    }

    private void setFormEnabled(boolean enabled) {
        binding.categorySpinner.setEnabled(enabled);
        binding.descriptionInput.setEnabled(enabled);
        binding.setLocationButton.setEnabled(enabled);
        binding.takePhotoButton.setEnabled(enabled);
        binding.pickPhotoButton.setEnabled(enabled);
        binding.removePhotoButton.setEnabled(enabled);
        binding.changePhotoButton.setEnabled(enabled);
        binding.submitButton.setEnabled(enabled);
    }

    private static void showFieldError(TextView view, int messageRes) {
        view.setText(messageRes);
        view.setVisibility(View.VISIBLE);
    }

    private static void clearFieldError(TextView view) {
        view.setVisibility(View.GONE);
    }

    private static String textOf(EditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }

    /** Wires {@link PhotoCaptureController} results into the embedded photo control. */
    private final class PhotoListener implements PhotoCaptureController.PhotoResultListener {

        @Override
        public void onPhotoReady(@NonNull PhotoResult result) {
            applyPhotoAttached(result.getLocalFilePath());
        }

        @Override
        public void onPhotoCancelled() {
            // No-op: whatever photo state existed before stays as-is.
        }

        @Override
        public void onPhotoError(@NonNull String messageKey) {
            Snackbar.make(binding.getRoot(), messageKey, Snackbar.LENGTH_LONG).show();
        }
    }
}

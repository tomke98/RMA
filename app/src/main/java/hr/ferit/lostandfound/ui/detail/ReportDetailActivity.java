package hr.ferit.lostandfound.ui.detail;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.databinding.ActivityReportDetailBinding;
import hr.ferit.lostandfound.ui.report.CreateReportActivity;
import hr.ferit.lostandfound.util.Constants;
import hr.ferit.lostandfound.util.ImageUtils;
import hr.ferit.lostandfound.util.ReportDisplay;
import hr.ferit.lostandfound.util.ViewModelFactory;

/**
 * Report detail + immediate phone reveal (Story 3.1). Launched with
 * {@link Constants#EXTRA_REPORT_ID}; loads the report via
 * {@link ReportDetailViewModel}, renders it, and lets a viewer who is not the
 * reporter reveal {@code report.reporterPhone} inline with a dial-only
 * ({@code ACTION_DIAL}, never {@code ACTION_CALL}) option. The reveal is a pure
 * UI toggle — no Firestore write, no status change.
 *
 * <p>{@code revealed} is transient UI state (not persisted, not part of the
 * ViewModel's {@code Resource<Report>}): it resets on process death exactly
 * like other transient toggles in this codebase — a lost reveal-state on
 * rotation just means one extra tap.
 */
public class ReportDetailActivity extends AppCompatActivity {

    private static final String TAG = "ReportDetailActivity";

    private ActivityReportDetailBinding binding;
    private ReportDetailViewModel viewModel;

    private String reportId;

    /** Transient reveal toggle; see the class doc. Never persisted. */
    private boolean revealed;

    /** Dedicated to the placeholder-photo network fetch below (KEEP: mirrors the
     * prior implementation's single-thread-executor + HttpURLConnection /
     * BitmapFactory approach — this project has no image-loading library). */
    private final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityReportDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String extraId = getIntent().getStringExtra(Constants.EXTRA_REPORT_ID);
        if (extraId == null || extraId.trim().isEmpty()) {
            // Programmer error, not a user-facing state: no caller exists yet that
            // could omit/blank this (Stories 3.2-3.4 are unbuilt).
            Log.w(TAG, "Started with a missing/blank EXTRA_REPORT_ID; finishing.");
            finish();
            return;
        }
        reportId = extraId;

        ViewCompat.setAccessibilityHeading(binding.screenTitle, true);

        viewModel = new ViewModelProvider(this, new ViewModelFactory()).get(ReportDetailViewModel.class);

        binding.retryButton.setOnClickListener(v -> viewModel.retry());
        binding.revealButton.setOnClickListener(v -> onRevealTapped());
        binding.callButton.setOnClickListener(v -> onCallTapped());

        viewModel.resource().observe(this, this::render);

        // Gate on the ViewModel's own held state, never on savedInstanceState: a
        // restored (non-null) savedInstanceState after process death still comes
        // with a freshly re-created, data-less ViewModel, and gating on
        // savedInstanceState == null there would skip the load and leave the
        // screen permanently blank.
        if (viewModel.resource().getValue() == null) {
            viewModel.load(reportId);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        photoExecutor.shutdownNow();
    }

    private void onRevealTapped() {
        revealed = true;
        Resource<Report> current = viewModel.resource().getValue();
        if (current != null && current.isSuccess() && current.data != null) {
            renderPhoneArea(current.data);
        }
    }

    private void onCallTapped() {
        Resource<Report> current = viewModel.resource().getValue();
        if (current == null || current.data == null) {
            return;
        }
        String phone = current.data.getReporterPhone();
        if (phone == null) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone)));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No activity to handle ACTION_DIAL.", e);
        }
    }

    private void render(@Nullable Resource<Report> resource) {
        if (resource == null) {
            return;
        }
        switch (resource.status) {
            case LOADING:
                renderLoading();
                break;
            case SUCCESS:
                if (resource.data != null) {
                    renderContent(resource.data);
                }
                break;
            case ERROR:
                renderError(resource.message);
                break;
        }
    }

    private void renderLoading() {
        binding.progress.setVisibility(View.VISIBLE);
        binding.errorGroup.setVisibility(View.GONE);
        binding.contentGroup.setVisibility(View.GONE);
    }

    private void renderError(@Nullable String message) {
        binding.progress.setVisibility(View.GONE);
        binding.contentGroup.setVisibility(View.GONE);
        binding.errorGroup.setVisibility(View.VISIBLE);

        boolean notFound = ReportDetailViewModel.ERROR_NOT_FOUND.equals(message);
        binding.errorText.setText(notFound ? R.string.prijava_nije_pronadena : R.string.opca_greska);
        // The id is fixed, so a not-found result is never retryable; any other
        // failure (network/permission) gets a retry action.
        binding.retryButton.setVisibility(notFound ? View.GONE : View.VISIBLE);
    }

    private void renderContent(@NonNull Report report) {
        binding.progress.setVisibility(View.GONE);
        binding.errorGroup.setVisibility(View.GONE);
        binding.contentGroup.setVisibility(View.VISIBLE);

        boolean found = CreateReportActivity.REPORT_TYPE_FOUND.equals(report.getType());
        String categoryLabel = ReportDisplay.categoryLabel(getResources(), report.getCategory());
        CharSequence age = ReportDisplay.relativeAge(report.getCreatedAt());

        binding.typeChip.setText(found ? R.string.prijava_tip_pronadeno : R.string.prijava_tip_izgubljeno);
        binding.typeChip.setChipBackgroundColorResource(found ? R.color.status_found : R.color.status_lost);
        binding.typeChip.setTextColor(getColor(found ? R.color.on_status_found : R.color.on_status_lost));
        binding.categoryText.setText(categoryLabel);
        binding.descriptionText.setText(report.getDescription());
        binding.locationText.setText(report.getLocationLabel());
        binding.ageText.setText(age);

        String typeWord = getString(found
                ? R.string.prijava_tip_pronadeno_opis
                : R.string.prijava_tip_izgubljeno_opis);
        binding.photoImage.setContentDescription(
                getString(R.string.prijava_fotografija_dinamicki_opis, categoryLabel, typeWord, age));

        loadPhoto(report.getPhotoUrl(), categoryLabel);
        renderPhoneArea(report);
    }

    private void renderPhoneArea(@NonNull Report report) {
        boolean ownReport = viewModel.isOwnReport(report);
        if (ownReport) {
            binding.ownReportText.setVisibility(View.VISIBLE);
            binding.revealButton.setVisibility(View.GONE);
            binding.revealedGroup.setVisibility(View.GONE);
        } else if (revealed) {
            binding.ownReportText.setVisibility(View.GONE);
            binding.revealButton.setVisibility(View.GONE);
            binding.revealedGroup.setVisibility(View.VISIBLE);
            binding.phoneText.setText(getString(R.string.prijava_broj_telefona, report.getReporterPhone()));
        } else {
            binding.ownReportText.setVisibility(View.GONE);
            binding.revealButton.setVisibility(View.VISIBLE);
            binding.revealedGroup.setVisibility(View.GONE);
        }
    }

    /** No photo: shows the category-label placeholder glyph (no icon assets exist
     * in this project). A photo: fetches it off {@link #photoExecutor} via
     * {@link ImageUtils#fetchBitmap} (a plain {@link java.net.HttpURLConnection}
     * + {@link android.graphics.BitmapFactory} decode — no image library exists
     * in this project) and falls back to the same placeholder on any failure. */
    private void loadPhoto(@Nullable String photoUrl, @NonNull String categoryLabel) {
        if (photoUrl == null) {
            showPhotoPlaceholder(categoryLabel);
            return;
        }
        binding.photoGlyphText.setVisibility(View.GONE);
        binding.photoImage.setImageDrawable(null);
        photoExecutor.execute(() -> {
            Bitmap bitmap = ImageUtils.fetchBitmap(photoUrl);
            runOnUiThread(() -> {
                if (bitmap != null) {
                    binding.photoImage.setImageBitmap(bitmap);
                } else {
                    showPhotoPlaceholder(categoryLabel);
                }
            });
        });
    }

    private void showPhotoPlaceholder(@NonNull String categoryLabel) {
        binding.photoImage.setImageDrawable(null);
        binding.photoGlyphText.setText(categoryLabel);
        binding.photoGlyphText.setVisibility(View.VISIBLE);
    }
}

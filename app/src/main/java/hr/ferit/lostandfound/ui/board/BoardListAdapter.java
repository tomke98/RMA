package hr.ferit.lostandfound.ui.board;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.ui.report.CreateReportActivity;
import hr.ferit.lostandfound.util.ImageUtils;
import hr.ferit.lostandfound.util.LocationHelper;
import hr.ferit.lostandfound.util.ReportDisplay;

/**
 * Board list pane adapter (Story 3.3). Plain {@link RecyclerView.Adapter} —
 * this is the first RecyclerView in the project and, mirroring {@code
 * BoardActivity.redrawMarkers}'s own no-diffing convention for markers, {@link
 * #submitReports} simply replaces the held list and calls {@link
 * #notifyDataSetChanged()} rather than computing a {@code DiffUtil} patch.
 *
 * <p>Holds no Firestore reference: it is fed exclusively by {@code
 * BoardActivity} from the single {@code BoardViewModel} stream both panes
 * share, so {@link #submitReports} never triggers a reload.
 */
public class BoardListAdapter extends RecyclerView.Adapter<BoardListAdapter.RowHolder> {

    /** Row-click callback, fired with the tapped report's id. */
    public interface OnReportClickListener {
        void onReportClick(@NonNull String reportId);
    }

    /** Dedicated to the row photo fetch, mirroring {@code ReportDetailActivity}'s
     * single-thread-executor + {@link ImageUtils#fetchBitmap} pattern. */
    private final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();

    @NonNull
    private final OnReportClickListener clickListener;

    @NonNull
    private List<Report> reports = new ArrayList<>();

    @Nullable
    private LatLng currentFix;

    public BoardListAdapter(@NonNull OnReportClickListener clickListener) {
        this.clickListener = clickListener;
    }

    /** Replaces the held report list; no reload, no second Firestore read — the
     * data always comes from the caller's already-live {@code BoardViewModel}
     * stream. */
    public void submitReports(@Nullable List<Report> reports) {
        this.reports = reports == null ? new ArrayList<>() : new ArrayList<>(reports);
        notifyDataSetChanged();
    }

    /** Sets (or clears, with {@code null}) the one-shot fix used to compute each
     * row's distance; the caller is responsible for calling {@link
     * #notifyDataSetChanged()} afterwards (matches the map's own re-render
     * convention). */
    public void setCurrentFix(@Nullable LatLng currentFix) {
        this.currentFix = currentFix;
    }

    @Nullable
    public LatLng getCurrentFix() {
        return currentFix;
    }

    /** Releases the photo-fetch executor; call from the host Activity's {@code
     * onDestroy()}. */
    public void shutdown() {
        photoExecutor.shutdownNow();
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_report_card, parent, false);
        return new RowHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        holder.bind(reports.get(position), currentFix, clickListener, photoExecutor);
    }

    @Override
    public int getItemCount() {
        return reports.size();
    }

    static class RowHolder extends RecyclerView.ViewHolder {

        private final ImageView photoImage;
        private final TextView photoGlyphText;
        private final TextView categoryText;
        private final Chip typeChip;
        private final TextView ageText;
        private final TextView distanceText;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            photoImage = itemView.findViewById(R.id.photoImage);
            photoGlyphText = itemView.findViewById(R.id.photoGlyphText);
            categoryText = itemView.findViewById(R.id.categoryText);
            typeChip = itemView.findViewById(R.id.typeChip);
            ageText = itemView.findViewById(R.id.ageText);
            distanceText = itemView.findViewById(R.id.distanceText);
        }

        void bind(@NonNull Report report,
                  @Nullable LatLng currentFix,
                  @NonNull OnReportClickListener clickListener,
                  @NonNull ExecutorService photoExecutor) {
            Resources resources = itemView.getResources();
            boolean found = CreateReportActivity.REPORT_TYPE_FOUND.equals(report.getType());
            String categoryLabel = ReportDisplay.categoryLabel(resources, report.getCategory());
            CharSequence age = ReportDisplay.relativeAge(report.getCreatedAt());

            categoryText.setText(categoryLabel);
            typeChip.setText(found ? R.string.prijava_tip_pronadeno : R.string.prijava_tip_izgubljeno);
            typeChip.setChipBackgroundColorResource(found ? R.color.status_found : R.color.status_lost);
            typeChip.setTextColor(itemView.getContext().getColor(
                    found ? R.color.on_status_found : R.color.on_status_lost));
            ageText.setText(age);

            String typeWord = resources.getString(found
                    ? R.string.prijava_tip_pronadeno_opis
                    : R.string.prijava_tip_izgubljeno_opis);
            photoImage.setContentDescription(
                    resources.getString(R.string.prijava_fotografija_dinamicki_opis, categoryLabel, typeWord, age));

            bindDistance(report, currentFix, resources);
            bindPhoto(report, categoryLabel, photoExecutor);

            String reportId = report.getId();
            itemView.setOnClickListener(v -> {
                if (reportId != null) {
                    clickListener.onReportClick(reportId);
                }
            });
        }

        private void bindDistance(@NonNull Report report, @Nullable LatLng currentFix,
                                   @NonNull Resources resources) {
            if (!showsDistance(currentFix)) {
                distanceText.setVisibility(View.GONE);
                return;
            }
            float meters = LocationHelper.distanceMeters(
                    currentFix, new LatLng(report.getLat(), report.getLng()));
            String distance = meters >= 1000f
                    ? resources.getString(R.string.ploca_udaljenost_km, meters / 1000f)
                    : resources.getString(R.string.ploca_udaljenost_m, Math.round(meters));
            distanceText.setText(distance);
            distanceText.setVisibility(View.VISIBLE);
        }

        /** {@code true} exactly when the row should show a distance string —
         * i.e. a one-shot fix is available. Extracted so {@code
         * BoardListAdapterTest} can verify the matrix's "distance shown when a
         * fix is available, hidden (never '0 m') when it is not" rows directly,
         * without needing app string-resource resolution in the test. */
        static boolean showsDistance(@Nullable LatLng currentFix) {
            return currentFix != null;
        }

        /** Recycle-safe photo binding: the {@link ImageView} is tagged with the
         * bound report's id before the async fetch starts; the main-thread
         * callback discards the result if the holder has since been rebound to a
         * different report (its tag will differ). */
        private void bindPhoto(@NonNull Report report, @NonNull String categoryLabel,
                                @NonNull ExecutorService photoExecutor) {
            String reportId = report.getId();
            String photoUrl = report.getPhotoUrl();

            photoImage.setTag(reportId);

            if (photoUrl == null) {
                showPlaceholder(categoryLabel);
                return;
            }

            photoGlyphText.setVisibility(View.GONE);
            photoImage.setImageDrawable(null);
            photoExecutor.execute(() -> {
                Bitmap bitmap = ImageUtils.fetchBitmap(photoUrl);
                itemView.post(() -> {
                    if (!Objects.equals(photoImage.getTag(), reportId)) {
                        // Recycled to a different report while the fetch was in
                        // flight; that rebind already started its own fetch.
                        return;
                    }
                    if (bitmap != null) {
                        photoImage.setImageBitmap(bitmap);
                    } else {
                        showPlaceholder(categoryLabel);
                    }
                });
            });
        }

        private void showPlaceholder(@NonNull String categoryLabel) {
            photoImage.setImageDrawable(null);
            photoGlyphText.setText(categoryLabel);
            photoGlyphText.setVisibility(View.VISIBLE);
        }
    }
}

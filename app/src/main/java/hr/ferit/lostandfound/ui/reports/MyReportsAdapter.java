package hr.ferit.lostandfound.ui.reports;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.repo.ReportRepository;
import hr.ferit.lostandfound.ui.report.CreateReportActivity;
import hr.ferit.lostandfound.util.ReportDisplay;

/**
 * "Moje prijave" list adapter (Story 4.3), mirroring {@code
 * BoardListAdapter}'s plain-{@link RecyclerView.Adapter} structure without its
 * photo-fetch machinery (not needed here — no photo in this row per the
 * spec's Boundaries & Constraints). {@link #submitReports} replaces the held
 * list and calls {@link #notifyDataSetChanged()}, no {@code DiffUtil}, same
 * convention as the Board list.
 *
 * <p>An open row shows a "Zatvori" {@link MaterialButton} firing {@link
 * OnCloseClickListener}; a closed row is greyed out with a "Zatvoreno" label
 * and no action.
 */
public class MyReportsAdapter extends RecyclerView.Adapter<MyReportsAdapter.RowHolder> {

    /** "Zatvori" tap callback, fired with the tapped report's id. */
    public interface OnCloseClickListener {
        void onCloseClick(@NonNull String reportId);
    }

    @NonNull
    private final OnCloseClickListener closeClickListener;

    @NonNull
    private List<Report> reports = new ArrayList<>();

    public MyReportsAdapter(@NonNull OnCloseClickListener closeClickListener) {
        this.closeClickListener = closeClickListener;
    }

    /** Replaces the held report list; no reload, matches {@code
     * BoardListAdapter.submitReports}'s convention. */
    public void submitReports(@Nullable List<Report> reports) {
        this.reports = reports == null ? new ArrayList<>() : new ArrayList<>(reports);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_my_report, parent, false);
        return new RowHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        holder.bind(reports.get(position), closeClickListener);
    }

    @Override
    public int getItemCount() {
        return reports.size();
    }

    static class RowHolder extends RecyclerView.ViewHolder {

        private final TextView categoryText;
        private final Chip typeChip;
        private final TextView statusText;
        private final TextView ageText;
        private final MaterialButton closeButton;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            categoryText = itemView.findViewById(R.id.categoryText);
            typeChip = itemView.findViewById(R.id.typeChip);
            statusText = itemView.findViewById(R.id.statusText);
            ageText = itemView.findViewById(R.id.ageText);
            closeButton = itemView.findViewById(R.id.closeButton);
        }

        void bind(@NonNull Report report, @NonNull OnCloseClickListener closeClickListener) {
            Resources resources = itemView.getResources();
            boolean found = CreateReportActivity.REPORT_TYPE_FOUND.equals(report.getType());
            boolean open = isOpen(report.getStatus());
            String categoryLabel = ReportDisplay.categoryLabel(resources, report.getCategory());
            CharSequence age = ReportDisplay.relativeAge(report.getCreatedAt());

            categoryText.setText(categoryLabel);
            typeChip.setText(found ? R.string.prijava_tip_pronadeno : R.string.prijava_tip_izgubljeno);
            typeChip.setChipBackgroundColorResource(found ? R.color.status_found : R.color.status_lost);
            typeChip.setTextColor(itemView.getContext().getColor(
                    found ? R.color.on_status_found : R.color.on_status_lost));
            ageText.setText(age);
            statusText.setText(open ? R.string.otvoreno : R.string.zatvoreno);

            float alpha = open ? 1f : 0.5f;
            categoryText.setAlpha(alpha);
            typeChip.setAlpha(alpha);
            statusText.setAlpha(alpha);
            ageText.setAlpha(alpha);

            closeButton.setVisibility(open ? View.VISIBLE : View.GONE);
            // Category + age distinguishes rows for a TalkBack user even when
            // several open reports share the same category (code review,
            // second pass), mirroring BoardListAdapter.RowHolder's per-row
            // photoImage.setContentDescription precedent.
            closeButton.setContentDescription(
                    resources.getString(R.string.zatvori_gumb_opis, categoryLabel, age));

            String reportId = report.getId();
            closeButton.setOnClickListener(v -> {
                if (reportId != null) {
                    closeClickListener.onCloseClick(reportId);
                }
            });
        }

        /** {@code true} exactly when a row should show the "Zatvori" action --
         * i.e. the report's status is still {@link ReportRepository#STATUS_OPEN}.
         * Extracted so {@code MyReportsAdapterTest} can verify the open-vs-closed
         * branch directly, mirroring {@code BoardListAdapter.RowHolder#showsDistance}'s
         * pattern. */
        static boolean isOpen(@Nullable String status) {
            return ReportRepository.STATUS_OPEN.equals(status);
        }
    }
}

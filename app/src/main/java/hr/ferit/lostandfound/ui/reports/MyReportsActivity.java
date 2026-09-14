package hr.ferit.lostandfound.ui.reports;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.util.ViewModelFactory;

/**
 * "Moje prijave" (Story 4.3): every {@code reports} document with {@code
 * reporterId == currentUid}, newest first, reached from {@code
 * BoardActivity}'s overflow menu. Toolbar back navigation mirrors {@code
 * FollowedCategoriesActivity}; the "Zatvori" confirmation dialog and its
 * dialog-dismiss-on-{@link #onDestroy()} pattern mirrors {@code
 * BoardActivity.confirmSignOut}.
 *
 * <p>Loads via {@link MyReportsViewModel}, a thin wrapper over {@code
 * ReportRepository}'s one-time {@code get()} (no listener). On a successful
 * close, the ViewModel mutates its held list in place and re-emits {@link
 * Resource#success} — no full list reload.
 */
public class MyReportsActivity extends AppCompatActivity {

    private MyReportsViewModel viewModel;

    private RecyclerView reportList;
    private MyReportsAdapter adapter;
    private CircularProgressIndicator progress;
    private View emptyText;
    private View errorGroup;
    private MaterialButton retryButton;

    /** Retained so it can be dismissed in {@link #onDestroy()} (no window leak
     * on rotation), mirroring {@code BoardActivity.signOutDialog}. */
    @Nullable
    private AlertDialog closeConfirmDialog;

    /** Set right before {@link MyReportsViewModel#close} is invoked, so the
     * next {@code SUCCESS} the ViewModel posts (its in-place update, no
     * re-query) is recognised as a close outcome and gets its own confirming
     * Snackbar rather than being mistaken for a fresh load. Cleared as soon as
     * that SUCCESS is rendered. */
    @Nullable
    private String pendingCloseId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_reports);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        reportList = findViewById(R.id.reportList);
        progress = findViewById(R.id.progress);
        emptyText = findViewById(R.id.emptyText);
        errorGroup = findViewById(R.id.errorGroup);
        retryButton = findViewById(R.id.retryButton);

        adapter = new MyReportsAdapter(this::onCloseClick);
        reportList.setLayoutManager(new LinearLayoutManager(this));
        reportList.setAdapter(adapter);

        retryButton.setOnClickListener(v -> viewModel.retry());

        viewModel = new ViewModelProvider(this, new ViewModelFactory()).get(MyReportsViewModel.class);
        viewModel.resource().observe(this, this::render);

        if (viewModel.resource().getValue() == null) {
            viewModel.load();
        }
    }

    @Override
    protected void onDestroy() {
        if (closeConfirmDialog != null && closeConfirmDialog.isShowing()) {
            closeConfirmDialog.dismiss();
        }
        closeConfirmDialog = null;
        super.onDestroy();
    }

    private void onCloseClick(@NonNull String reportId) {
        closeConfirmDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.zatvori)
                .setMessage(R.string.zatvori_prijavu_potvrda_tekst)
                .setNegativeButton(R.string.odustani, null)
                .setPositiveButton(R.string.zatvori, (dialog, which) -> {
                    pendingCloseId = reportId;
                    viewModel.close(reportId);
                })
                .show();
    }

    private void render(@Nullable Resource<List<Report>> resource) {
        if (resource == null) {
            return;
        }
        switch (resource.status) {
            case LOADING:
                renderLoading();
                break;
            case SUCCESS:
                renderList(resource.data);
                if (pendingCloseId != null) {
                    pendingCloseId = null;
                    Snackbar.make(reportList, R.string.zatvori_prijavu_uspjeh, Snackbar.LENGTH_LONG).show();
                }
                break;
            case ERROR:
                if (MyReportsViewModel.ERROR_CLOSE.equals(resource.message)) {
                    // The list is still good (unchanged); only the close action
                    // failed -- keep showing the list, just surface a retryable
                    // Snackbar (fail-soft, no crash, matches the spec's "Close
                    // failure" row).
                    pendingCloseId = null;
                    renderList(resource.data);
                    Snackbar.make(reportList, R.string.zatvori_prijavu_greska, Snackbar.LENGTH_LONG).show();
                } else {
                    renderError();
                }
                break;
        }
    }

    private void renderLoading() {
        progress.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        errorGroup.setVisibility(View.GONE);
        reportList.setVisibility(View.GONE);
    }

    private void renderList(@Nullable List<Report> reports) {
        progress.setVisibility(View.GONE);
        errorGroup.setVisibility(View.GONE);
        boolean empty = reports == null || reports.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        reportList.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.submitReports(reports);
    }

    private void renderError() {
        progress.setVisibility(View.GONE);
        emptyText.setVisibility(View.GONE);
        reportList.setVisibility(View.GONE);
        errorGroup.setVisibility(View.VISIBLE);
    }
}

package hr.ferit.lostandfound.notify;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.TaskStackBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Category;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.repo.BoardRepository;
import hr.ferit.lostandfound.ui.board.BoardActivity;
import hr.ferit.lostandfound.ui.detail.ReportDetailActivity;
import hr.ferit.lostandfound.util.Constants;
import hr.ferit.lostandfound.util.FollowedCategoriesStore;
import hr.ferit.lostandfound.util.ReportDisplay;

/**
 * The epic's single dedicated component for "new report in a followed
 * category" local notifications (spec-4-2). Piggybacks on {@link
 * BoardRepository}'s existing single Firestore listener via {@link
 * BoardRepository.AddedReportsListener} -- never a second listener, never FCM,
 * {@code WorkManager}, a background service, or polling.
 *
 * <p>Tracks an in-memory "seen" report-id set for the lifetime of one {@link
 * #start()}/{@link #stop()} cycle (one per signed-in session, wired through
 * {@code ServiceLocator}). The very first {@link #onAddedReports} callback
 * after {@link #start()} only seeds that set -- it never posts a notification,
 * since it may simply be replaying every already-open report on a fresh
 * listener attach. Every later callback's reports are candidates: a
 * notification fires only when the id isn't already "seen", {@code
 * Category.valueOf(report.getCategory())} is in the current uid's followed set
 * ({@link FollowedCategoriesStore#getFollowed}), and {@code
 * report.getReporterId()} isn't the current uid -- then the id is marked
 * "seen" regardless of whether a notification fired.
 *
 * <p>No persisted "seen" set across process death (in-memory only, reseeded on
 * next {@link #start()}) -- a deliberate simplification, not a bug, given the
 * no-background-delivery constraint already makes exhaustive history
 * irrelevant.
 */
public class CategoryNotifier implements BoardRepository.AddedReportsListener {

    private static final String TAG = "CategoryNotifier";

    @NonNull
    private final Context appContext;

    @NonNull
    private final BoardRepository boardRepository;

    @NonNull
    private final FollowedCategoriesStore followedCategoriesStore;

    @Nullable
    private final String uid;

    @NonNull
    private final Set<String> seenReportIds = new HashSet<>();

    private boolean seeded;

    public CategoryNotifier(@NonNull Context context,
                             @NonNull BoardRepository boardRepository,
                             @NonNull FollowedCategoriesStore followedCategoriesStore,
                             @Nullable String uid) {
        this.appContext = context.getApplicationContext();
        this.boardRepository = boardRepository;
        this.followedCategoriesStore = followedCategoriesStore;
        this.uid = uid;
    }

    /** Subscribes to {@link BoardRepository}'s added-report hook. Idempotent:
     * replacing an existing subscription with itself is harmless. */
    public void start() {
        boardRepository.setAddedReportsListener(this);
    }

    /**
     * Unsubscribes and clears the "seen" set (Story 4.2's sign-out teardown,
     * wired from {@code ServiceLocator.reset()} alongside {@code
     * BoardRepository.stop()}) -- no notification must ever fire for a
     * different account's session using this instance's stale state.
     */
    public void stop() {
        boardRepository.setAddedReportsListener(null);
        seenReportIds.clear();
        seeded = false;
    }

    @Override
    public void onAddedReports(@NonNull List<Report> addedReports) {
        if (!seeded) {
            seeded = true;
            for (Report report : addedReports) {
                if (report.getId() != null) {
                    seenReportIds.add(report.getId());
                }
            }
            return;
        }
        for (Report report : addedReports) {
            handleAdded(report);
        }
    }

    private void handleAdded(@NonNull Report report) {
        String id = report.getId();
        if (id == null || seenReportIds.contains(id)) {
            return;
        }
        boolean shouldNotify = matches(report);
        seenReportIds.add(id);
        if (shouldNotify) {
            postNotification(report);
        }
    }

    private boolean matches(@NonNull Report report) {
        if (uid == null || uid.equals(report.getReporterId())) {
            return false;
        }
        Category category;
        try {
            category = Category.valueOf(report.getCategory());
        } catch (IllegalArgumentException | NullPointerException e) {
            // Malformed/unknown category -- treated as "not followed", never a
            // crash (AD-14).
            return false;
        }
        return followedCategoriesStore.getFollowed(uid).contains(category);
    }

    /**
     * Builds and posts the actual {@link android.app.Notification}. Package-visible
     * and non-final (rather than {@code private}) purely so {@code
     * CategoryNotifierTest} can override it with a call-recording stub -- this
     * project's unit tests run without {@code includeAndroidResources} (see
     * {@code BoardListAdapterTest}'s class doc), so a real {@link
     * android.content.res.Resources} lookup like {@link
     * ReportDisplay#categoryLabel} is not available in that environment; the
     * matching/id-tracking logic above it is fully exercised regardless.
     */
    @VisibleForTesting
    void postNotification(@NonNull Report report) {
        Intent detailIntent = new Intent(appContext, ReportDetailActivity.class);
        detailIntent.putExtra(Constants.EXTRA_REPORT_ID, report.getId());
        Intent boardIntent = new Intent(appContext, BoardActivity.class);
        // Repeated notification taps must reuse the existing BoardActivity
        // instance rather than pushing a new one onto the synthetic back
        // stack each time (TaskStackBuilder alone doesn't dedupe across
        // separate PendingIntent invocations).
        boardIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int requestCode = report.getId().hashCode();
        // Built with an explicit synthetic back stack (BoardActivity as
        // parent, ReportDetailActivity on top) rather than a bare
        // FLAG_ACTIVITY_NEW_TASK intent, so system Back from the detail
        // screen returns to the Board instead of exiting to the launcher --
        // the tap can originate outside any app task (from the notification
        // drawer, possibly with the app process not currently in any task).
        // BoardActivity added directly (rather than via
        // addNextIntentWithParentStack) since ReportDetailActivity declares
        // no parentActivityName in the manifest for that API to read.
        PendingIntent pendingIntent = TaskStackBuilder.create(appContext)
                .addNextIntent(boardIntent)
                .addNextIntent(detailIntent)
                .getPendingIntent(
                        requestCode,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String categoryLabel = ReportDisplay.categoryLabel(appContext.getResources(), report.getCategory());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                appContext, NotificationChannels.CHANNEL_ID_CATEGORY_REPORTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(appContext.getString(R.string.obavijest_nova_prijava_naslov))
                .setContentText(appContext.getString(R.string.obavijest_nova_prijava_tekst, categoryLabel))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        try {
            NotificationManagerCompat.from(appContext).notify(requestCode, builder.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted (API 33+): platform behaviour is a
            // silent no-op, but guard anyway so this can never crash (AD-14) --
            // matching-logic / id tracking above already ran regardless.
            Log.w(TAG, "Notification not posted; POST_NOTIFICATIONS not granted.", e);
        }
    }

    @VisibleForTesting
    boolean isSeeded() {
        return seeded;
    }

    @VisibleForTesting
    boolean hasSeen(@NonNull String reportId) {
        return seenReportIds.contains(reportId);
    }
}

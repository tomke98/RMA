package hr.ferit.lostandfound.data.repo;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import hr.ferit.lostandfound.data.local.AppExecutors;
import hr.ferit.lostandfound.data.local.BoardReportDao;
import hr.ferit.lostandfound.data.local.BoardReportEntity;
import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.data.model.Resource;
import hr.ferit.lostandfound.util.Connectivity;
import hr.ferit.lostandfound.util.Constants;

/**
 * Owns the single Board Firestore snapshot listener (AD-3, AD-17): every
 * {@code status == "open"} report, newest first, capped at
 * {@link Constants#BOARD_LIMIT}. Exactly one {@link ListenerRegistration} lives
 * here — {@code BoardActivity} (Story 3.2) and the Story 3.3 list pane both
 * observe {@link #resource()} rather than attaching their own listeners.
 * Mirrors {@code ReportRepository}'s Firebase-free-upward style: no
 * {@code com.google.firebase.*} type leaks past this class boundary, {@code
 * ui.board} only ever sees {@link Resource}/{@link Report}.
 *
 * <p>Story 3.4 (AD-13): every listener success is fully mirrored into the
 * {@code board_report} Room table (a delete-all + insert-all
 * {@code @Transaction}, always on {@link AppExecutors#diskIO()}), then read
 * back from Room before being published — Room is a read-through mirror, the
 * listener remains the sole writer. A cold/offline {@link #start()} publishes
 * straight from that cache so the Board never shows an empty screen when it
 * has previously-synced rows.
 *
 * <p>{@link #start()} is idempotent: a no-op while a listener is already
 * attached. Firestore treats a listener error as terminal for that
 * registration (no further callbacks arrive on it), so the error branch also
 * clears the registration reference, letting a later {@link #start()} call —
 * e.g. from {@code BoardViewModel#retry()} — attach a fresh listener.
 */
public class BoardRepository {

    private static final String TAG = "BoardRepository";

    /**
     * Notified with every {@code DocumentChange.Type.ADDED} report from each
     * snapshot delivered on this same listener registration (Story 4.2, no
     * second Firestore listener) -- including the very first snapshot after a
     * fresh attach, where Firestore reports every existing document as ADDED.
     * {@code CategoryNotifier} (the sole subscriber) is responsible for
     * treating its own first callback as a seed-only pass.
     */
    public interface AddedReportsListener {
        void onAddedReports(@NonNull List<Report> addedReports);
    }

    private static final String COLLECTION_REPORTS = "reports";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_CREATED_AT = "createdAt";

    /** {@link Resource#message} sentinel for any listener-attach/read failure. */
    public static final String ERROR_GENERIC = "ERROR_GENERIC";

    @NonNull
    private final FirebaseFirestore firestore;

    @NonNull
    private final BoardReportDao dao;

    @NonNull
    private final Context context;

    private final MutableLiveData<Resource<List<Report>>> resource = new MutableLiveData<>();

    private final MutableLiveData<Long> lastSyncedAt = new MutableLiveData<>();

    @Nullable
    private ListenerRegistration registration;

    @Nullable
    private AddedReportsListener addedReportsListener;

    /**
     * Tracks whether the current listener registration has delivered its first
     * snapshot to {@link #addedReportsListener} yet -- reset on every fresh
     * {@link #start()}/{@link #stop()} cycle. {@code CategoryNotifier} relies on
     * its first callback being a seed-only pass, so that callback must fire on
     * the true first snapshot even when it contains zero {@code ADDED} changes
     * (an empty board on fresh attach), not merely on the first snapshot that
     * happens to be non-empty.
     */
    private boolean deliveredFirstSnapshot;

    public BoardRepository(@NonNull FirebaseFirestore firestore,
                            @NonNull BoardReportDao dao,
                            @NonNull Context context) {
        this.firestore = firestore;
        this.dao = dao;
        this.context = context.getApplicationContext();
    }

    @NonNull
    public LiveData<Resource<List<Report>>> resource() {
        return resource;
    }

    /**
     * {@code max(syncedAt)} from {@code board_report}, refreshed alongside every
     * mirror and the offline cache read in {@link #start()}. {@code null} while
     * the table is empty (no cache exists yet).
     */
    @NonNull
    public LiveData<Long> lastSyncedAt() {
        return lastSyncedAt;
    }

    /**
     * Registers (or clears, with {@code null}) the single {@link
     * AddedReportsListener} for this instance -- {@code CategoryNotifier}'s
     * subscription (Story 4.2). Replaces any previously-set listener; this
     * repository never fans out to more than one at a time, which is all the
     * app currently needs.
     */
    public void setAddedReportsListener(@Nullable AddedReportsListener listener) {
        this.addedReportsListener = listener;
    }

    /**
     * Attaches the frozen Board query's snapshot listener:
     * {@code reports.whereEqualTo("status", "open").orderBy("createdAt", DESCENDING).limit(BOARD_LIMIT)}.
     * No-op while a listener is already attached (rotation-safe: {@code
     * BoardViewModel} survives configuration change and never calls this twice
     * for a healthy listener).
     *
     * <p>If the device is offline when this is called, the last-mirrored {@code
     * board_report} rows are published immediately (independently of the
     * listener, which may not deliver anything until connectivity returns) so a
     * cold/offline start never renders an empty screen.
     */
    public void start() {
        if (registration != null) {
            return;
        }
        deliveredFirstSnapshot = false;
        resource.setValue(Resource.loading());

        if (!Connectivity.isOnline(context)) {
            AppExecutors.diskIO().execute(() -> {
                try {
                    List<BoardReportEntity> cached = dao.getAllOrderedByCreatedAtDesc();
                    resource.postValue(Resource.success(toReports(cached)));
                } catch (RuntimeException e) {
                    // A Room/SQLite failure here must not crash the app (AD-14).
                    Log.w(TAG, "Offline board_report cache read failed.", e);
                    resource.postValue(Resource.error(ERROR_GENERIC));
                    return;
                }
                try {
                    lastSyncedAt.postValue(dao.getMaxSyncedAt());
                } catch (RuntimeException e) {
                    // The cache read above already published successfully; a
                    // failure here must not overwrite it with an error overlay.
                    Log.w(TAG, "Offline board_report max(syncedAt) read failed.", e);
                }
            });
        }

        Query query = firestore.collection(COLLECTION_REPORTS)
                .whereEqualTo(FIELD_STATUS, ReportRepository.STATUS_OPEN)
                .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
                .limit(Constants.BOARD_LIMIT);

        registration = query.addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                Log.w(TAG, "Board snapshot listener failed.", error);
                // Firestore will not deliver further events on this registration;
                // drop the reference so a later start() (retry) reattaches.
                registration = null;
                // While offline, the cache branch above may have already published
                // a working cached view; don't clobber it with an error overlay
                // (the "cold start, offline" matrix row must never show the error
                // screen when a usable cache exists).
                if (Connectivity.isOnline(context)) {
                    resource.setValue(Resource.error(ERROR_GENERIC));
                }
                return;
            }
            // Runs unconditionally, ahead of the board resource's own
            // toObjects() mapping below, so a malformed batch there can never
            // skip CategoryNotifier's callback and reopen the seed-round bug
            // (per-document mapping failures inside notifyAddedReports are
            // already swallowed there).
            notifyAddedReports(snapshot);

            List<Report> reports;
            try {
                reports = snapshot != null
                        ? snapshot.toObjects(Report.class)
                        : Collections.emptyList();
            } catch (RuntimeException e) {
                // A malformed document (e.g. a field of the wrong type) makes
                // toObjects() throw; never let that crash the app on
                // Firestore's callback thread (AD-14 "never a crash").
                Log.w(TAG, "Board snapshot deserialization failed.", e);
                resource.setValue(Resource.error(ERROR_GENERIC));
                return;
            }
            mirrorAndPublish(reports);
        });
    }

    /**
     * Sources {@link CategoryNotifier}'s added-report events straight from this
     * listener's {@code snapshot.getDocumentChanges()} -- never a second query.
     * A malformed document here must not crash the app (AD-14) nor interfere
     * with the {@link #resource()} publish above, so failures are swallowed
     * with a warning log.
     *
     * <p>Always delivers the very first snapshot of a fresh {@link #start()}
     * cycle, even with zero {@code ADDED} changes (an empty board on attach) --
     * {@code CategoryNotifier} treats its first callback as seed-only, so that
     * callback must correspond to the listener's true first snapshot, not just
     * the first one that happens to be non-empty.
     */
    private void notifyAddedReports(@Nullable QuerySnapshot snapshot) {
        if (snapshot == null || addedReportsListener == null) {
            return;
        }
        List<Report> added = new ArrayList<>();
        for (DocumentChange change : snapshot.getDocumentChanges()) {
            if (change.getType() != DocumentChange.Type.ADDED) {
                continue;
            }
            try {
                added.add(change.getDocument().toObject(Report.class));
            } catch (RuntimeException e) {
                // A single malformed document must not discard the rest of this
                // snapshot's added reports; skip just this one (AD-14).
                Log.w(TAG, "Board added-report change mapping failed.", e);
            }
        }
        boolean isFirstSnapshot = !deliveredFirstSnapshot;
        deliveredFirstSnapshot = true;
        if (!added.isEmpty() || isFirstSnapshot) {
            addedReportsListener.onAddedReports(added);
        }
    }

    /**
     * Full delete-all + insert-all of {@code board_report} in one
     * {@code @Transaction} on {@link AppExecutors#diskIO()}, then reads the
     * mirror back and publishes it — {@code BoardActivity} renders {@code
     * board_report}, never the raw snapshot list.
     */
    private void mirrorAndPublish(@NonNull List<Report> reports) {
        AppExecutors.diskIO().execute(() -> {
            try {
                long syncedAt = System.currentTimeMillis();
                List<BoardReportEntity> entities = new ArrayList<>(reports.size());
                for (Report report : reports) {
                    entities.add(toEntity(report, syncedAt));
                }
                dao.replaceAll(entities);

                List<BoardReportEntity> mirrored = dao.getAllOrderedByCreatedAtDesc();
                resource.postValue(Resource.success(toReports(mirrored)));
            } catch (RuntimeException e) {
                // A Room/SQLite failure here must not crash the app (AD-14).
                Log.w(TAG, "board_report mirror failed.", e);
                resource.postValue(Resource.error(ERROR_GENERIC));
                return;
            }
            try {
                lastSyncedAt.postValue(dao.getMaxSyncedAt());
            } catch (RuntimeException e) {
                // The mirror above already published successfully; a failure
                // here must not overwrite it with an error overlay.
                Log.w(TAG, "board_report max(syncedAt) read failed.", e);
            }
        });
    }

    // Package-visible (not private) so BoardRepositoryMappingTest can exercise
    // the createdAt-coalescing and round-trip mapping directly.
    @NonNull
    static BoardReportEntity toEntity(@NonNull Report report, long syncedAt) {
        BoardReportEntity entity = new BoardReportEntity();
        entity.id = report.getId() != null ? report.getId() : "";
        entity.type = report.getType();
        entity.category = report.getCategory();
        entity.description = report.getDescription();
        entity.photoUrl = report.getPhotoUrl();
        entity.lat = report.getLat();
        entity.lng = report.getLng();
        entity.locationLabel = report.getLocationLabel();
        entity.reporterId = report.getReporterId();
        entity.reporterPhone = report.getReporterPhone();
        Timestamp createdAt = report.getCreatedAt();
        // A just-written report's @ServerTimestamp may not have resolved yet;
        // coalesce to now rather than 0/crash.
        entity.createdAtMillis = createdAt != null ? createdAt.toDate().getTime() : System.currentTimeMillis();
        entity.syncedAt = syncedAt;
        return entity;
    }

    @NonNull
    static List<Report> toReports(@NonNull List<BoardReportEntity> rows) {
        List<Report> reports = new ArrayList<>(rows.size());
        for (BoardReportEntity row : rows) {
            reports.add(toReport(row));
        }
        return reports;
    }

    @NonNull
    static Report toReport(@NonNull BoardReportEntity entity) {
        Report report = new Report();
        report.setId(entity.id);
        report.setType(entity.type);
        report.setCategory(entity.category);
        report.setDescription(entity.description);
        report.setPhotoUrl(entity.photoUrl);
        report.setLat(entity.lat);
        report.setLng(entity.lng);
        report.setLocationLabel(entity.locationLabel);
        report.setReporterId(entity.reporterId);
        report.setReporterPhone(entity.reporterPhone);
        report.setCreatedAt(new Timestamp(new Date(entity.createdAtMillis)));
        return report;
    }

    /**
     * Removes the listener (AD-17), clears the LiveData back to no value, and
     * wipes {@code board_report} on {@link AppExecutors#diskIO()} — the locked
     * sign-out decision: no cached row (including {@code reporterPhone})
     * survives into a second account signing in on the same device.
     */
    public void stop() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        resource.setValue(null);
        AppExecutors.diskIO().execute(() -> {
            try {
                dao.deleteAll();
            } catch (RuntimeException e) {
                // Sign-out is already tearing down state; a wipe failure must not
                // crash the app (AD-14) — the next sign-in's first mirror will
                // still fully replace whatever is left.
                Log.w(TAG, "board_report wipe on sign-out failed.", e);
            }
        });
    }
}

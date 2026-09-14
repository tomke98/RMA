package hr.ferit.lostandfound.data.repo;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import hr.ferit.lostandfound.data.model.Report;
import hr.ferit.lostandfound.util.Constants;

/**
 * Sole writer of the {@code reports} collection and {@code report_photos/}
 * Storage objects (AD-7). Mirrors {@link AuthRepository}'s Firebase-free
 * callback style — no {@code com.google.firebase.*} type leaks past
 * {@link CreateCallback}.
 *
 * <p><b>Fixed create sequence (AD-7), never {@code add()} then {@code update()}:</b>
 * <ol>
 *     <li>mint the id via {@code collection("reports").document().getId()};</li>
 *     <li>read {@code users/<uid>.phone} (a plain Firestore {@code get()}) and copy
 *         it onto the draft as {@code reporterPhone}, alongside {@code reporterId}
 *         = the current Auth uid;</li>
 *     <li>if a local photo path is supplied, upload it to
 *         {@code report_photos/<id>.jpg} and read its download URL into
 *         {@code photoUrl} — no photo leaves {@code photoUrl} {@code null};</li>
 *     <li>write the complete document with a single {@code document(id).set(report)}.</li>
 * </ol>
 * A photo upload failure aborts before the document write (nothing written). A
 * document-write failure after a successful photo upload best-effort deletes the
 * uploaded Storage object. Either way no orphaned Storage object survives a
 * failed create.
 *
 * <p>The local photo file handed in has already been downscaled to a JPEG by
 * {@code util.ImageUtils.downscaleToJpeg} inside {@code PhotoCaptureController}
 * (Story 2.2) at capture/pick time — that step needs a {@link android.content.Context}
 * and a source {@code Uri}, neither of which this repository holds (its
 * constructor is deliberately {@code (FirebaseFirestore, FirebaseStorage)} only,
 * per AD-1/AD-2: no Android framework type crosses into {@code data.repo} beyond
 * the Firebase clients). This repository therefore uploads that already-downscaled
 * local file as-is via {@link StorageReference#putFile(Uri)} rather than
 * re-invoking {@code ImageUtils.downscaleToJpeg}.
 */
public class ReportRepository {

    private static final String TAG = "ReportRepository";

    private static final String COLLECTION_REPORTS = "reports";
    private static final String COLLECTION_USERS = "users";
    private static final String STORAGE_FOLDER = "report_photos";

    private static final String FIELD_PHONE = "phone";

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CLOSED = "closed";

    private static final String FIELD_REPORTER_ID = "reporterId";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_STATUS = "status";

    /** Firebase-free result channel for {@link #create}. */
    public interface CreateCallback {

        /** The document (and, if supplied, the photo) were written successfully. */
        void onSuccess();

        /**
         * Any failure — reading the user's phone, uploading the photo, or writing
         * the document. No partial/orphaned document or Storage object remains.
         */
        void onError(@NonNull Exception e);
    }

    /** Firebase-free result channel for {@link #getById}. */
    public interface GetCallback {

        /** The document exists and deserialised cleanly into a {@link Report}. */
        void onSuccess(@NonNull Report report);

        /** No document exists at this id — distinct from {@link #onError}, and
         * not retryable (the id is fixed). */
        void onNotFound();

        /**
         * A read failure (network/permission), or a document that exists but
         * failed to deserialise (a data problem, not a missing-document one —
         * never routed to {@link #onNotFound()}).
         */
        void onError(@NonNull Exception e);
    }

    /** Firebase-free result channel for {@link #getByReporterId}. */
    public interface GetListCallback {

        /** The query succeeded; may be empty. Malformed documents are skipped
         * (logged), never abort the whole batch. */
        void onSuccess(@NonNull List<Report> reports);

        /** The query itself failed (network/permission). */
        void onError(@NonNull Exception e);
    }

    /** Firebase-free result channel for {@link #close}. */
    public interface CloseCallback {

        /** The document's {@code status} field was updated to {@link #STATUS_CLOSED}. */
        void onSuccess();

        /** The update failed (network/permission); the document is unchanged. */
        void onError(@NonNull Exception e);
    }

    @NonNull
    private final FirebaseFirestore firestore;
    @NonNull
    private final FirebaseStorage storage;

    public ReportRepository(@NonNull FirebaseFirestore firestore, @NonNull FirebaseStorage storage) {
        this.firestore = firestore;
        this.storage = storage;
    }

    /**
     * Runs the fixed AD-7 create sequence for {@code draft}. {@code draft} is
     * expected to already carry {@code type}, {@code category}, {@code description},
     * {@code lat}/{@code lng} and (optionally) {@code locationLabel} — this method
     * fills in {@code reporterId}, {@code reporterPhone}, {@code status} and
     * (if {@code localPhotoPath != null}) {@code photoUrl}.
     */
    public void create(@NonNull Report draft, @Nullable String localPhotoPath, @NonNull CreateCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "create() called with no signed-in user.");
            callback.onError(new IllegalStateException("No signed-in user."));
            return;
        }
        String uid = user.getUid();
        String id = firestore.collection(COLLECTION_REPORTS).document().getId();

        firestore.collection(COLLECTION_USERS).document(uid).get()
                .addOnSuccessListener(snapshot -> {
                    String phone = snapshot.getString(FIELD_PHONE);
                    if (phone == null || phone.trim().isEmpty()) {
                        Log.w(TAG, "users/" + uid + " has no phone; refusing to write a report with no reporterPhone.");
                        callback.onError(new IllegalStateException("Missing users/" + uid + ".phone."));
                        return;
                    }

                    draft.setReporterId(uid);
                    draft.setReporterPhone(phone);
                    draft.setStatus(STATUS_OPEN);

                    if (localPhotoPath != null) {
                        uploadPhotoThenWrite(id, localPhotoPath, draft, callback);
                    } else {
                        draft.setPhotoUrl(null);
                        writeDocument(id, null, draft, callback);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Failed to read users/" + uid + " for reporterPhone.", e);
                    callback.onError(e);
                });
    }

    private void uploadPhotoThenWrite(@NonNull String id,
                                      @NonNull String localPhotoPath,
                                      @NonNull Report draft,
                                      @NonNull CreateCallback callback) {
        StorageReference photoRef = photoRef(id);
        Uri fileUri = Uri.fromFile(new File(localPhotoPath));

        photoRef.putFile(fileUri)
                .addOnSuccessListener(taskSnapshot -> photoRef.getDownloadUrl()
                        .addOnSuccessListener(downloadUrl -> {
                            draft.setPhotoUrl(downloadUrl.toString());
                            writeDocument(id, photoRef, draft, callback);
                        })
                        .addOnFailureListener(e -> {
                            Log.w(TAG, "getDownloadUrl failed after upload for " + id + "; cleaning up.", e);
                            deleteQuietly(photoRef);
                            callback.onError(e);
                        }))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Photo upload failed for " + id + "; aborting before document write.", e);
                    callback.onError(e);
                });
    }

    private void writeDocument(@NonNull String id,
                               @Nullable StorageReference uploadedPhotoRef,
                               @NonNull Report draft,
                               @NonNull CreateCallback callback) {
        DocumentReference docRef = firestore.collection(COLLECTION_REPORTS).document(id);
        docRef.set(draft)
                .addOnSuccessListener(unused -> callback.onSuccess())
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Document write failed for " + id + ".", e);
                    if (uploadedPhotoRef != null) {
                        deleteQuietly(uploadedPhotoRef);
                    }
                    callback.onError(e);
                });
    }

    /**
     * Loads one {@code reports} document by id (Story 3.1). Unconditional direct
     * Firestore {@code get()} — no cache-check branch: {@code BoardRepository}/
     * {@code board_report} don't exist yet (Story 3.2/3.4).
     */
    public void getById(@NonNull String id, @NonNull GetCallback callback) {
        firestore.collection(COLLECTION_REPORTS).document(id).get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) {
                        callback.onNotFound();
                        return;
                    }
                    Report report;
                    try {
                        report = snapshot.toObject(Report.class);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "toObject() threw for existing document " + id
                                + "; reporting as an error, not not-found.", e);
                        callback.onError(e);
                        return;
                    }
                    if (report == null) {
                        Log.w(TAG, "toObject() returned null for existing document " + id
                                + "; reporting as an error, not not-found.");
                        callback.onError(new IllegalStateException("Unable to deserialize report " + id));
                        return;
                    }
                    callback.onSuccess(report);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "getById(" + id + ") failed.", e);
                    callback.onError(e);
                });
    }

    /**
     * Loads every {@code reports} document with {@code reporterId == uid},
     * newest first (Story 4.3). A one-time {@code get()} — no listener, mirrors
     * {@link #getById}'s non-listener style rather than {@code
     * BoardRepository}'s snapshot-listener pattern. Capped at {@link
     * Constants#BOARD_LIMIT}, mirroring {@code BoardRepository}'s own query
     * limit (code review, second pass). A single malformed document's {@code
     * toObject(Report.class)} is skipped (logged) rather than aborting the
     * whole batch, mirroring {@code BoardRepository.notifyAddedReports}'s
     * per-document try/catch.
     */
    public void getByReporterId(@NonNull String uid, @NonNull GetListCallback callback) {
        firestore.collection(COLLECTION_REPORTS)
                .whereEqualTo(FIELD_REPORTER_ID, uid)
                .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
                .limit(Constants.BOARD_LIMIT)
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<Report> reports = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshot) {
                        try {
                            Report report = doc.toObject(Report.class);
                            reports.add(report);
                        } catch (RuntimeException e) {
                            Log.w(TAG, "getByReporterId(" + uid + "): skipping malformed document "
                                    + doc.getId() + ".", e);
                        }
                    }
                    callback.onSuccess(reports);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "getByReporterId(" + uid + ") failed.", e);
                    callback.onError(e);
                });
    }

    /**
     * Sets {@code status = STATUS_CLOSED} on one {@code reports} document via a
     * single {@code docRef.update(...)} (Story 4.3) — the only permitted
     * mutation of an existing report in this epic.
     */
    public void close(@NonNull String id, @NonNull CloseCallback callback) {
        firestore.collection(COLLECTION_REPORTS).document(id)
                .update(FIELD_STATUS, STATUS_CLOSED)
                .addOnSuccessListener(unused -> callback.onSuccess())
                .addOnFailureListener(e -> {
                    Log.w(TAG, "close(" + id + ") failed.", e);
                    callback.onError(e);
                });
    }

    @NonNull
    private StorageReference photoRef(@NonNull String id) {
        return storage.getReference().child(STORAGE_FOLDER + "/" + id + ".jpg");
    }

    private static void deleteQuietly(@NonNull StorageReference ref) {
        ref.delete().addOnFailureListener(e -> Log.w(TAG, "Best-effort Storage cleanup failed.", e));
    }
}

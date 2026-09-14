package hr.ferit.lostandfound.data.repo;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Transaction;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sole writer of the {@code users} and {@code usernames} collections (AD-18).
 *
 * <p>{@link #register} performs exactly one {@code createUserWithEmailAndPassword}
 * followed, on success, by exactly one Firestore {@code runTransaction} that:
 * <ol>
 *     <li>reads {@code usernames/<lowercased-username>};</li>
 *     <li>if it exists, aborts with a "taken" sentinel;</li>
 *     <li>otherwise writes {@code usernames/<lc> = { uid }} and
 *         {@code users/<uid> = { username, email, phone, createdAt }} together.</li>
 * </ol>
 * The username is lower-cased only for the {@code usernames} document id; the
 * {@code users} document keeps the original casing.
 *
 * <p>Cleanup contract (revised OQ3): on <em>any</em> failure once
 * {@code createUserWithEmailAndPassword} has already succeeded (username taken or
 * any other transaction failure) it attempts {@code currentUser.delete()}. If the
 * delete succeeds the specific outcome is delivered
 * ({@link RegisterCallback#onUsernameTaken()} / {@link RegisterCallback#onError}),
 * leaving no Auth account or {@code users/<uid>} behind. If the delete itself
 * fails it calls {@code auth.signOut()} and delivers the distinct
 * {@link RegisterCallback#onPartialAccount()} outcome — the UI then does not
 * promise a clean retry.
 *
 * <p>Results are delivered through {@link RegisterCallback}, a Firebase-free
 * channel, so no {@code com.google.firebase.*} type leaks into the view layer.
 */
public class AuthRepository {

    private static final String TAG = "AuthRepository";

    private static final String COLLECTION_USERS = "users";
    private static final String COLLECTION_USERNAMES = "usernames";

    private static final String FIELD_UID = "uid";
    private static final String FIELD_USERNAME = "username";
    private static final String FIELD_EMAIL = "email";
    private static final String FIELD_PHONE = "phone";
    private static final String FIELD_CREATED_AT = "createdAt";

    /**
     * Message carried by the {@link FirebaseFirestoreException} the transaction
     * throws when {@code usernames/<lc>} already exists. It is matched together
     * with {@link FirebaseFirestoreException.Code#ALREADY_EXISTS} in
     * {@link #isUsernameTaken} so an unrelated {@code ALREADY_EXISTS} from the
     * SDK is not mistaken for a taken username.
     */
    private static final String USERNAME_TAKEN_MESSAGE = "lost-and-found:username-taken";

    /** Firebase-free result channel for {@link #register}. */
    public interface RegisterCallback {

        /** Auth user created and both Firestore documents written. */
        void onSuccess();

        /** Username already reserved; the just-created Auth user was deleted. */
        void onUsernameTaken();

        /** The email is already registered; no Auth user was created this call. */
        void onEmailInUse();

        /**
         * A post-{@code createUser} failure occurred and the follow-up
         * {@code currentUser.delete()} also failed, so the account is
         * half-provisioned. The session has been signed out.
         */
        void onPartialAccount();

        /**
         * Any other failure. When it happens after {@code createUser} succeeded,
         * the just-created Auth user has already been deleted.
         */
        void onError(@NonNull Exception e);
    }

    /** Firebase-free result channel for {@link #signIn}. */
    public interface SignInCallback {

        /** Signed in; the Firebase Auth session is active and persisted. */
        void onSuccess();

        /**
         * The email is unknown or the password is wrong. One generic outcome so
         * the caller shows a single message without singling out a field.
         */
        void onInvalidCredentials();

        /**
         * Any other failure — a backend or configuration error, quota, or
         * connectivity that slipped past the caller's pre-check. A wrong email, a
         * wrong password, and a disabled account all arrive as
         * {@link #onInvalidCredentials()} instead (see {@link #signIn}).
         */
        void onError(@NonNull Exception e);
    }

    @NonNull
    private final FirebaseAuth auth;
    @NonNull
    private final FirebaseFirestore firestore;

    public AuthRepository(@NonNull FirebaseAuth auth, @NonNull FirebaseFirestore firestore) {
        this.auth = auth;
        this.firestore = firestore;
    }

    /**
     * Creates the account. All four values are expected already validated by the
     * caller ({@code RegisterViewModel}); {@code username} in particular must
     * match {@code ^[A-Za-z0-9._-]{3,20}$} so it is a safe Firestore document id.
     */
    public void register(@NonNull String username,
                         @NonNull String email,
                         @NonNull String password,
                         @NonNull String phone,
                         @NonNull RegisterCallback callback) {
        auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> runProfileTransaction(username, email, phone, callback))
                .addOnFailureListener(e -> {
                    if (e instanceof FirebaseAuthUserCollisionException) {
                        Log.w(TAG, "Email already registered.", e);
                        callback.onEmailInUse();
                    } else {
                        Log.w(TAG, "createUserWithEmailAndPassword failed.", e);
                        callback.onError(e);
                    }
                });
    }

    /**
     * Signs an existing user in with email + password. A wrong email, a wrong
     * password, or a disabled account is reported as the single
     * {@link SignInCallback#onInvalidCredentials()} outcome — Firebase raises
     * {@code FirebaseAuthInvalidUserException} / {@code FirebaseAuthInvalidCredentialsException}
     * for all three. Every other failure goes to
     * {@link SignInCallback#onError(Exception)}.
     */
    public void signIn(@NonNull String email,
                       @NonNull String password,
                       @NonNull SignInCallback callback) {
        auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> callback.onSuccess())
                .addOnFailureListener(e -> {
                    if (e instanceof FirebaseAuthInvalidUserException
                            || e instanceof FirebaseAuthInvalidCredentialsException) {
                        Log.w(TAG, "Sign-in rejected: invalid credentials.", e);
                        callback.onInvalidCredentials();
                    } else {
                        Log.w(TAG, "signInWithEmailAndPassword failed.", e);
                        callback.onError(e);
                    }
                });
    }

    /**
     * Clears the Firebase Auth session. The {@code App} {@code AuthStateListener}
     * (AD-17) observes the resulting {@code null} user and runs
     * {@code ServiceLocator.reset()}; this method does not touch the locator.
     */
    public void signOut() {
        auth.signOut();
    }

    /** @return whether a Firebase Auth session is currently present. */
    public boolean isSignedIn() {
        return auth.getCurrentUser() != null;
    }

    private void runProfileTransaction(@NonNull String username,
                                       @NonNull String email,
                                       @NonNull String phone,
                                       @NonNull RegisterCallback callback) {
        final FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            // createUser reported success but the session is gone: nothing to clean up.
            Log.w(TAG, "No current user right after createUser succeeded.");
            callback.onError(new IllegalStateException("No current user after createUser."));
            return;
        }

        final String uid = user.getUid();
        final String usernameId = username.toLowerCase(Locale.ROOT);
        final DocumentReference usernameRef =
                firestore.collection(COLLECTION_USERNAMES).document(usernameId);
        final DocumentReference userRef =
                firestore.collection(COLLECTION_USERS).document(uid);

        firestore.runTransaction((Transaction.Function<Void>) transaction -> {
            DocumentSnapshot existing = transaction.get(usernameRef);
            if (existing.exists()) {
                throw new FirebaseFirestoreException(
                        USERNAME_TAKEN_MESSAGE, FirebaseFirestoreException.Code.ALREADY_EXISTS);
            }

            Map<String, Object> usernameDoc = new HashMap<>();
            usernameDoc.put(FIELD_UID, uid);
            transaction.set(usernameRef, usernameDoc);

            Map<String, Object> userDoc = new HashMap<>();
            userDoc.put(FIELD_USERNAME, username); // original case
            // Firebase Auth normalises the address; store that exact value so the
            // Firestore copy never disagrees with currentUser.getEmail().
            userDoc.put(FIELD_EMAIL, user.getEmail() != null ? user.getEmail() : email);
            userDoc.put(FIELD_PHONE, phone);
            userDoc.put(FIELD_CREATED_AT, FieldValue.serverTimestamp());
            transaction.set(userRef, userDoc);
            return null;
        }).addOnSuccessListener(unused -> {
            Log.i(TAG, "Registration transaction committed for uid " + uid);
            callback.onSuccess();
        }).addOnFailureListener(e -> {
            boolean taken = isUsernameTaken(e);
            Log.w(TAG, taken
                    ? "Username taken; deleting the just-created Auth user."
                    : "Registration transaction failed; deleting the just-created Auth user.", e);
            cleanUpAfterFailure(user, taken, e, callback);
        });
    }

    /**
     * Revised-OQ3 cleanup: delete the just-created Auth user; if the delete
     * itself fails, sign out and report {@link RegisterCallback#onPartialAccount()}.
     */
    private void cleanUpAfterFailure(@NonNull FirebaseUser user,
                                     boolean taken,
                                     @NonNull Exception cause,
                                     @NonNull RegisterCallback callback) {
        user.delete()
                .addOnSuccessListener(unused -> {
                    if (taken) {
                        callback.onUsernameTaken();
                    } else {
                        callback.onError(cause);
                    }
                })
                .addOnFailureListener(deleteError -> {
                    Log.w(TAG, "currentUser.delete() failed; signing out instead.", deleteError);
                    auth.signOut();
                    callback.onPartialAccount();
                });
    }

    private static boolean isUsernameTaken(@NonNull Exception e) {
        if (!(e instanceof FirebaseFirestoreException)) {
            return false;
        }
        FirebaseFirestoreException fe = (FirebaseFirestoreException) e;
        return fe.getCode() == FirebaseFirestoreException.Code.ALREADY_EXISTS
                && USERNAME_TAKEN_MESSAGE.equals(fe.getMessage());
    }
}

package hr.ferit.lostandfound.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;

import hr.ferit.lostandfound.data.local.BoardDatabase;
import hr.ferit.lostandfound.data.repo.AuthRepository;
import hr.ferit.lostandfound.data.repo.BoardRepository;
import hr.ferit.lostandfound.data.repo.ReportRepository;
import hr.ferit.lostandfound.notify.CategoryNotifier;

/**
 * Process-wide holder for the Firebase handles and (later) the repository
 * singletons. This is the one place {@code ui} code obtains a repository, which
 * keeps {@code FirebaseFirestore} / {@code FirebaseAuth} / {@code FirebaseStorage}
 * out of the view layer (AD-1).
 *
 * <p>{@link #reset()} is called by {@code App}'s {@code AuthStateListener} whenever
 * the signed-in user becomes {@code null} or changes, so no per-user singleton
 * state (e.g. the future {@code BoardRepository} listener, AD-17) survives a
 * sign-out.
 */
public final class ServiceLocator {

    private static final String TAG = "ServiceLocator";

    private static FirebaseAuth auth;
    private static FirebaseFirestore firestore;
    private static FirebaseStorage storage;

    private static AuthRepository authRepository;
    private static ReportRepository reportRepository;
    private static BoardRepository boardRepository;
    private static FollowedCategoriesStore followedCategoriesStore;
    private static CategoryNotifier categoryNotifier;

    @Nullable
    private static Context appContext;

    private ServiceLocator() {
    }

    /**
     * Stores the process-wide {@link Context} needed for {@link BoardRepository}'s
     * Room database and {@code Connectivity.isOnline()} check. Called once from
     * {@code App.onCreate()}, before any {@link #boardRepository()} access.
     */
    public static synchronized void init(@NonNull Context context) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
    }

    @NonNull
    public static synchronized FirebaseAuth auth() {
        if (auth == null) {
            auth = FirebaseAuth.getInstance();
        }
        return auth;
    }

    @NonNull
    public static synchronized FirebaseFirestore firestore() {
        if (firestore == null) {
            firestore = FirebaseFirestore.getInstance();
        }
        return firestore;
    }

    @NonNull
    public static synchronized FirebaseStorage storage() {
        if (storage == null) {
            storage = FirebaseStorage.getInstance();
        }
        return storage;
    }

    /**
     * Lazily built {@link AuthRepository} — the sole writer of {@code users} and
     * {@code usernames} (AD-18). This is the one place {@code ui.auth} obtains it.
     */
    @NonNull
    public static synchronized AuthRepository authRepository() {
        if (authRepository == null) {
            authRepository = new AuthRepository(auth(), firestore());
        }
        return authRepository;
    }

    /**
     * Lazily built {@link ReportRepository} — the sole writer of {@code reports}
     * documents and {@code report_photos/} Storage objects (AD-7). This is the
     * one place {@code ui.report} obtains it.
     */
    @NonNull
    public static synchronized ReportRepository reportRepository() {
        if (reportRepository == null) {
            reportRepository = new ReportRepository(firestore(), storage());
        }
        return reportRepository;
    }

    /**
     * Lazily built {@link BoardRepository} — the sole owner of the Board's one
     * Firestore snapshot listener (AD-3, AD-17). This is the one place {@code
     * ui.board} obtains it.
     */
    @NonNull
    public static synchronized BoardRepository boardRepository() {
        if (boardRepository == null) {
            if (appContext == null) {
                throw new IllegalStateException("ServiceLocator.init(Context) must run before boardRepository().");
            }
            boardRepository = new BoardRepository(
                    firestore(), BoardDatabase.get(appContext).boardReportDao(), appContext);
        }
        return boardRepository;
    }

    /**
     * Lazily built {@link FollowedCategoriesStore} (Story 4.1) — the sole holder
     * of the "Praćene kategorije" {@code SharedPreferences} wrapper. This is the
     * one place {@code ui.categories} obtains it, keeping platform storage
     * access out of the Activity (AD-1).
     */
    @NonNull
    public static synchronized FollowedCategoriesStore followedCategoriesStore() {
        if (followedCategoriesStore == null) {
            if (appContext == null) {
                throw new IllegalStateException(
                        "ServiceLocator.init(Context) must run before followedCategoriesStore().");
            }
            followedCategoriesStore = new FollowedCategoriesStore(appContext);
        }
        return followedCategoriesStore;
    }

    /**
     * Lazily built {@link CategoryNotifier} (Story 4.2) -- subscribes to {@link
     * #boardRepository()}'s added-report hook the moment it is first built
     * (from {@code ViewModelFactory}, alongside the first {@code
     * BoardViewModel} of a session), so it never misses that listener's first
     * snapshot. Bound to the uid signed in at construction time, matching this
     * singleton's per-session lifetime (torn down in {@link #reset()}).
     */
    @NonNull
    public static synchronized CategoryNotifier categoryNotifier() {
        if (categoryNotifier == null) {
            if (appContext == null) {
                throw new IllegalStateException("ServiceLocator.init(Context) must run before categoryNotifier().");
            }
            FirebaseUser user = auth().getCurrentUser();
            String uid = user != null ? user.getUid() : null;
            categoryNotifier = new CategoryNotifier(appContext, boardRepository(), followedCategoriesStore(), uid);
            categoryNotifier.start();
        }
        return categoryNotifier;
    }

    /**
     * Clears cached per-user singleton state. The Firebase handles themselves are
     * stateless singletons and are re-fetched lazily on next access. Repository
     * singletons added in later stories tear down their listeners here.
     */
    public static synchronized void reset() {
        if (categoryNotifier != null) {
            categoryNotifier.stop();
        }
        if (boardRepository != null) {
            boardRepository.stop();
        }
        firestore = null;
        storage = null;
        authRepository = null;
        reportRepository = null;
        boardRepository = null;
        categoryNotifier = null;
        // auth is intentionally kept: it is the identity source the listener reads.
    }
}

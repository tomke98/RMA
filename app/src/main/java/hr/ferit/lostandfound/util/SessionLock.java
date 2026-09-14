package hr.ferit.lostandfound.util;

/**
 * Process-scoped "this session has already been unlocked" flag (AD-12) that scopes
 * the biometric prompt to genuine cold starts.
 *
 * <p>Without it, the route-through-{@code EntryActivity} that follows a successful
 * password sign-in <em>or</em> registration (Stories 1.2 / 1.3) would show a
 * fingerprint prompt the instant after the user typed their password.
 *
 * <ul>
 *     <li>{@link #markUnlocked()} — called on biometric-unlock success, and in the
 *         {@code SUCCESS} branch of {@code SignInActivity} and {@code RegisterActivity}
 *         (a just-registered user has already proved their password).</li>
 *     <li>{@link #isUnlocked()} — {@code EntryActivity} routes to
 *         {@code BiometricLockActivity} only when this is {@code false}.</li>
 *     <li>{@link #clear()} — called by {@code App}'s {@code AuthStateListener}
 *         wherever it runs {@code ServiceLocator.reset()}, so a new session starts
 *         locked.</li>
 * </ul>
 *
 * <p>Deliberately plain {@code static} state, not held in {@code ServiceLocator}
 * (which is nulled and rebuilt on every {@code reset()}). It is process state: if
 * the process is killed the next cold start is locked again.
 */
public final class SessionLock {

    private static final String TAG = "SessionLock";

    private static volatile boolean unlocked;

    private SessionLock() {
    }

    /** Marks the current process as already unlocked for this session. */
    public static void markUnlocked() {
        unlocked = true;
    }

    /** @return whether this process has been unlocked since the last {@link #clear()}. */
    public static boolean isUnlocked() {
        return unlocked;
    }

    /** Resets to the locked state; the next cold-start route hits the lock screen. */
    public static void clear() {
        unlocked = false;
    }
}

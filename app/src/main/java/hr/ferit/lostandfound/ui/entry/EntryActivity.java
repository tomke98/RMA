package hr.ferit.lostandfound.ui.entry;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.ui.auth.SignInActivity;
import hr.ferit.lostandfound.ui.board.BoardActivity;
import hr.ferit.lostandfound.ui.lock.BiometricLockActivity;
import hr.ferit.lostandfound.util.BiometricGate;
import hr.ferit.lostandfound.util.ServiceLocator;
import hr.ferit.lostandfound.util.SessionLock;

/**
 * The only {@code MAIN}/{@code LAUNCHER} activity (AD-12). It shows a brief splash,
 * reads the session through {@link hr.ferit.lostandfound.data.repo.AuthRepository}
 * (AD-1 — no direct {@code FirebaseAuth} call here), routes once, and
 * {@link #finish()}es itself so it is never on the back stack.
 *
 * <p>Routing table (Story 1.1; session check moved behind the repository in Story 1.3;
 * biometric branch added in Story 1.4):
 * <ul>
 *     <li>signed out -&gt; {@link SignInActivity}</li>
 *     <li>signed in + biometric unlock available + this process not yet unlocked
 *         -&gt; {@link BiometricLockActivity}</li>
 *     <li>signed in otherwise -&gt; {@link BoardActivity}</li>
 * </ul>
 * The unlock flag ({@link SessionLock}) keeps the lock scoped to genuine cold
 * starts: a password sign-in or registration marks the process unlocked, so the
 * route-through-{@code EntryActivity} that follows does not re-prompt.
 */
public class EntryActivity extends AppCompatActivity {

    private static final String TAG = "EntryActivity";

    /** Guards against onStart() running again before this activity finishes. */
    private boolean routed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entry);
    }

    @Override
    protected void onStart() {
        super.onStart();
        route();
    }

    private void route() {
        if (routed) {
            return;
        }
        routed = true;

        // Session check goes through AuthRepository so no Activity touches
        // FirebaseAuth directly (AD-1; closes the Story 1.1 deferred-work item).
        boolean signedIn = ServiceLocator.authRepository().isSignedIn();
        final Intent next;
        if (!signedIn) {
            Log.i(TAG, "No session; routing to SignInActivity.");
            next = new Intent(this, SignInActivity.class);
        } else if (!SessionLock.isUnlocked() && BiometricGate.canUnlock(this)) {
            // Signed-in cold start on a biometric-capable device: gate the Board
            // behind the local session lock (AD-12). BiometricGate is the single
            // source of the authenticator decision it and the prompt share.
            Log.i(TAG, "Session present but locked; routing to BiometricLockActivity.");
            next = new Intent(this, BiometricLockActivity.class);
        } else {
            Log.i(TAG, "Session present and unlocked; routing to BoardActivity.");
            next = new Intent(this, BoardActivity.class);
        }
        startActivity(next);
        finish();
    }
}

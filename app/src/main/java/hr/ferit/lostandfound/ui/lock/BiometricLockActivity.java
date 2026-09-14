package hr.ferit.lostandfound.ui.lock;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.button.MaterialButton;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.ui.board.BoardActivity;
import hr.ferit.lostandfound.ui.entry.EntryActivity;
import hr.ferit.lostandfound.util.BiometricGate;
import hr.ferit.lostandfound.util.ServiceLocator;
import hr.ferit.lostandfound.util.SessionLock;

/**
 * The one place a {@code BiometricPrompt} is constructed in the app (AD-12). It is
 * a <em>local session lock</em>, never an auth factor: the Firebase session is
 * already valid, the prompt only saves the user retyping their password on a cold
 * start. No {@code com.google.firebase.*} import, no {@code CryptoObject}, no
 * network call, no {@code ViewModel} (there is no data-layer interaction beyond the
 * existing {@code signOut()} — matching the {@code BoardActivity} placeholder
 * precedent).
 *
 * <p>Flow:
 * <ul>
 *     <li>success -&gt; mark the process unlocked, then open {@link BoardActivity}
 *         <b>directly</b> (routing back through {@link EntryActivity} would just
 *         re-evaluate the gate and bounce straight back here);</li>
 *     <li>negative button ("Koristi lozinku") -&gt; sign out and route through
 *         {@link EntryActivity}, which lands the now-signed-out user on the
 *         sign-in screen;</li>
 *     <li>other terminal error (cancel, transient lockout) -&gt; stay here with an
 *         always-visible "Koristi lozinku" button plus a "Pokušajte ponovno"
 *         button that re-invokes the prompt (hidden on permanent lockout);</li>
 *     <li>one non-matching read -&gt; no-op; the system prompt keeps waiting.</li>
 * </ul>
 *
 * <p>Prompt re-invocation is driven from {@link #onStart()} guarded by the
 * load-bearing instance flag {@link #promptPending} (review Pass 1 replaced the
 * frozen {@code onCreate} / {@code savedInstanceState == null} guard with this):
 * one guard covers the fresh start, the return from {@link #moveTaskToBack(boolean)},
 * and a rotation after a cancel, and never double-prompts — {@code promptPending}
 * blocks a second {@code authenticate()} and the biometric fragment keeps its live
 * prompt across the configuration change itself.
 */
public class BiometricLockActivity extends AppCompatActivity {

    private static final String TAG = "BiometricLockActivity";

    /** The only persisted bit of state: retry is hidden forever once this is set. */
    private static final String STATE_LOCKOUT_PERMANENT = "lockoutPermanent";

    private TextView lockMessage;
    private MaterialButton retryButton;

    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    /**
     * {@code true} between an {@code authenticate()} call and its terminal
     * callback. Non-retained by design: a fresh instance after a configuration
     * change re-drives the prompt from {@link #onStart()}, and androidx.biometric
     * ignores a duplicate {@code authenticate()} while its prompt is still showing.
     */
    private boolean promptPending;

    /** Set on {@code ERROR_LOCKOUT_PERMANENT}; survives rotation via the bundle. */
    private boolean lockoutPermanent;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_biometric_lock);

        if (savedInstanceState != null) {
            lockoutPermanent = savedInstanceState.getBoolean(STATE_LOCKOUT_PERMANENT, false);
        }

        // android:accessibilityHeading is API 28+; back it with the AndroidX call
        // so the wordmark is a heading on minSdk 24-27 too (matches the auth screens).
        ViewCompat.setAccessibilityHeading(findViewById(R.id.wordmark), true);

        lockMessage = findViewById(R.id.lockMessage);
        retryButton = findViewById(R.id.retryButton);
        retryButton.setOnClickListener(v -> authenticate());
        findViewById(R.id.usePasswordButton).setOnClickListener(v -> usePasswordFallback());

        biometricPrompt = new BiometricPrompt(
                this, ContextCompat.getMainExecutor(this), authCallback);
        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometrija_naslov))
                .setSubtitle(getString(R.string.biometrija_podnaslov))
                .setAllowedAuthenticators(BiometricGate.ALLOWED_AUTHENTICATORS)
                .setNegativeButtonText(getString(R.string.koristi_lozinku))
                // Convenience session-lock: no extra confirm tap on face-unlock devices.
                .setConfirmationRequired(false)
                .build();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // A lock is not bypassable with Back; send the task to the
                // background instead of killing it — the user is still signed in
                // and still locked on the next foreground.
                moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (SessionLock.isUnlocked()) {
            // Already unlocked this process (e.g. success just fired). Never sit on
            // a promptless lock screen: go straight to the Board like the success
            // path does.
            openBoard();
            return;
        }
        if (!promptPending && !lockoutPermanent) {
            authenticate();
        }
        if (lockoutPermanent) {
            // No prompt and no retry in this state; say why, so the default
            // "confirm your identity" subtitle does not mislead after a rotation.
            lockMessage.setText(R.string.biometrija_trajno_zakljucano);
        }
        // Retry is a pure function of the one persisted flag plus whether a prompt
        // is already in flight, so a rotation can neither resurrect nor lose it.
        retryButton.setVisibility(!lockoutPermanent && !promptPending ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_LOCKOUT_PERMANENT, lockoutPermanent);
    }

    private void authenticate() {
        lockMessage.setText(R.string.biometrija_podnaslov);
        retryButton.setVisibility(View.GONE);
        promptPending = true;
        biometricPrompt.authenticate(promptInfo);
    }

    private final BiometricPrompt.AuthenticationCallback authCallback =
            new BiometricPrompt.AuthenticationCallback() {

        @Override
        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
            promptPending = false;
            Log.i(TAG, "Biometric unlock succeeded; opening the Board.");
            SessionLock.markUnlocked();
            openBoard();
        }

        @Override
        public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
            promptPending = false;
            if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                Log.i(TAG, "User chose the password fallback from the prompt.");
                usePasswordFallback();
                return;
            }
            // Non-negative terminal error (cancel, transient or permanent lockout):
            // stay on the lock screen. Surface errString as the transient feedback
            // the frozen I/O matrix implies; keep the password fallback in reach.
            Log.w(TAG, "Biometric error " + errorCode + ": " + errString);
            if (errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                lockoutPermanent = true;
                lockMessage.setText(R.string.biometrija_trajno_zakljucano);
            } else if (TextUtils.isEmpty(errString)) {
                // Some OEMs deliver a null/blank errString; never show an empty line.
                lockMessage.setText(R.string.opca_greska);
            } else {
                lockMessage.setText(errString);
            }
            retryButton.setVisibility(lockoutPermanent ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onAuthenticationFailed() {
            // One non-matching read — non-terminal. The system prompt shows its own
            // "not recognised" and keeps waiting; nothing to do app-side.
            Log.d(TAG, "Single biometric read did not match; prompt still open.");
        }
    };

    private void openBoard() {
        // Straight to the Board, not via EntryActivity — that would re-run the
        // AD-12 gate and route right back here (AD-12 has a deliberate direct
        // BiometricLockActivity -> BoardActivity edge).
        Intent intent = new Intent(this, BoardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void usePasswordFallback() {
        // AD-1: sign-out goes through AuthRepository, never FirebaseAuth directly.
        // App's AuthStateListener (AD-17) then runs ServiceLocator.reset() and
        // SessionLock.clear() — both posted async on the main looper, order not
        // guaranteed relative to the routing below. That is still correct because
        // FirebaseAuth.getCurrentUser() nulls synchronously inside signOut(), so
        // EntryActivity.route() reads "signed out" and never consults SessionLock
        // on that branch (matches Story 1.3 BoardActivity.signOut()).
        ServiceLocator.authRepository().signOut();

        Intent intent = new Intent(this, EntryActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}

package hr.ferit.lostandfound;

import android.app.Application;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import hr.ferit.lostandfound.notify.NotificationChannels;
import hr.ferit.lostandfound.util.ServiceLocator;
import hr.ferit.lostandfound.util.SessionLock;

/**
 * Process entry point. Owns two things at bootstrap:
 * <ul>
 *     <li>the light-only lock — {@link AppCompatDelegate#MODE_NIGHT_NO} is forced
 *         so a system-dark device still renders the single light theme;</li>
 *     <li>the {@link FirebaseAuth.AuthStateListener} (AD-17) that clears
 *         {@link ServiceLocator} singleton state whenever the current user becomes
 *         {@code null} or changes, which drives sign-out in Story 1.3;</li>
 *     <li>{@link NotificationChannels#createChannels}, so Story 4.2's one
 *         notification channel exists before any {@code CategoryNotifier}
 *         {@code notify()} call.</li>
 * </ul>
 */
public class App extends Application {

    private static final String TAG = "App";

    @Nullable
    private String currentUid;

    private final FirebaseAuth.AuthStateListener authStateListener = new FirebaseAuth.AuthStateListener() {
        @Override
        public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            String newUid = user == null ? null : user.getUid();
            if (!TextUtils.equals(currentUid, newUid)) {
                Log.i(TAG, "Auth state changed; clearing singleton state.");
                currentUid = newUid;
                ServiceLocator.reset();
                // A new (or absent) session must start locked again (AD-12).
                SessionLock.clear();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        // Context must be set before any ServiceLocator.boardRepository() call.
        ServiceLocator.init(this);

        // Story 4.2: the channel must exist before any CategoryNotifier.notify() call.
        NotificationChannels.createChannels(this);

        // Touch the locator so the Firebase handles are ready before the first screen.
        FirebaseUser user = ServiceLocator.auth().getCurrentUser();
        currentUid = user == null ? null : user.getUid();
        ServiceLocator.auth().addAuthStateListener(authStateListener);
    }
}

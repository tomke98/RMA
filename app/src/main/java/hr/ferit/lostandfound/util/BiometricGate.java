package hr.ferit.lostandfound.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;

/**
 * Single source of the "is a biometric session unlock available?" decision (AD-12).
 * Mirrors {@link Connectivity}: static, {@code Context}-taking, no Firebase.
 *
 * <p>{@link #ALLOWED_AUTHENTICATORS} backs both the {@code EntryActivity} routing
 * gate and the {@code BiometricPrompt} in {@code BiometricLockActivity}, so the two
 * can never disagree about what "biometric available" means.
 *
 * <p>AD-12 erratum (approved): the frozen mask was
 * {@code BIOMETRIC_STRONG | DEVICE_CREDENTIAL}, but {@code androidx.biometric} 1.1.0
 * rejects that combination at {@code PromptInfo.build()} on API 28–29 (documented
 * unsupported), so it is narrowed to {@code BIOMETRIC_STRONG} only. A device with
 * just a lock-screen PIN/pattern and no enrolled biometric is not prompted; the
 * in-app password fallback covers it.
 */
public final class BiometricGate {

    private static final String TAG = "BiometricGate";

    /** The one authenticator set used by both the routing gate and the prompt. */
    public static final int ALLOWED_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG;

    private BiometricGate() {
    }

    /**
     * @return {@code true} when the device has strong biometric hardware that is
     * present, enrolled, and currently available — i.e. a {@code BiometricPrompt}
     * would show. {@code false} routes the signed-in user straight to the Board.
     */
    public static boolean canUnlock(@NonNull Context context) {
        return BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }
}

package hr.ferit.lostandfound.ui.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.repo.AuthRepository;

/**
 * Screen logic for {@link SignInActivity}: it checks that both fields are present,
 * then hands them to {@link AuthRepository#signIn}. Deliberately thin — a wrong
 * email or password is judged server-side and surfaces as the single
 * {@link Outcome#INVALID_CREDENTIALS}, so no field is ever singled out (AC2). An
 * email-pattern gate here would add a field-specific error path the AC forbids.
 *
 * <p>Structure mirrors {@link RegisterViewModel}. {@link #outcome()} is single-shot:
 * {@link #consumeOutcome()} clears it once the view has handled a terminal value,
 * so a configuration change replays neither the Snackbar nor the navigation.
 * {@link #fieldErrors()} deliberately survives a configuration change so a
 * still-invalid field keeps its inline error; {@link #consumeErrorFocus()}
 * suppresses only a repeated focus jump on redelivery. It imports no
 * {@code com.google.firebase.*} type and holds no Android framework state.
 */
public class SignInViewModel extends ViewModel {

    private static final String TAG = "SignInViewModel";

    /** The two form fields, top-to-bottom (drives first-error focus). */
    public enum Field {
        EMAIL,
        PASSWORD
    }

    /** Loading + terminal states the view renders. */
    public enum Outcome {
        LOADING,
        SUCCESS,
        INVALID_CREDENTIALS,
        /** Fields are valid but there is no connectivity; no Firebase call was made. */
        OFFLINE,
        ERROR
    }

    /** One inline per-field error: which field, and the string resource to show. */
    public static final class FieldError {
        public final Field field;
        @StringRes
        public final int messageRes;

        public FieldError(@NonNull Field field, @StringRes int messageRes) {
            this.field = field;
            this.messageRes = messageRes;
        }
    }

    @NonNull
    private final AuthRepository authRepository;

    private final MutableLiveData<List<FieldError>> fieldErrors = new MutableLiveData<>();
    private final MutableLiveData<Outcome> outcome = new MutableLiveData<>();

    /** Guards against a second submit while a sign-in attempt is in flight. */
    private boolean inFlight;

    /**
     * True when {@link #fieldErrors} was last set by a fresh submit, so the view
     * may move focus to the first bad field. Cleared by {@link #consumeErrorFocus()}
     * so a configuration-change redelivery does not re-steal focus.
     */
    private boolean focusPending;

    public SignInViewModel(@NonNull AuthRepository authRepository) {
        this.authRepository = authRepository;
    }

    @NonNull
    public LiveData<List<FieldError>> fieldErrors() {
        return fieldErrors;
    }

    @NonNull
    public LiveData<Outcome> outcome() {
        return outcome;
    }

    /** Called by the view after it has handled a terminal {@link Outcome}. */
    public void consumeOutcome() {
        outcome.setValue(null);
    }

    /**
     * Returns {@code true} at most once per fresh {@link #fieldErrors} emission,
     * telling the view it may move focus to the first invalid field. A redelivery
     * after a configuration change returns {@code false}.
     */
    public boolean consumeErrorFocus() {
        if (focusPending) {
            focusPending = false;
            return true;
        }
        return false;
    }

    /**
     * Checks both fields are non-empty and, if so, starts sign-in. The email is
     * trimmed; the password is not (spaces are valid password characters). A no-op
     * while an attempt is already in flight. Validation runs before the
     * connectivity check, so an offline user still sees inline field errors; only
     * a valid offline submit resolves to {@link Outcome#OFFLINE} and makes no
     * Firebase call.
     */
    public void submit(@Nullable String emailRaw, @Nullable String passwordRaw, boolean online) {
        if (inFlight) {
            return;
        }

        String email = emailRaw == null ? "" : emailRaw.trim();
        String password = passwordRaw == null ? "" : passwordRaw; // not trimmed

        List<FieldError> errors = validate(email, password);
        if (!errors.isEmpty()) {
            focusPending = true;
            fieldErrors.setValue(errors);
            return;
        }
        fieldErrors.setValue(Collections.emptyList());

        if (!online) {
            outcome.setValue(Outcome.OFFLINE);
            return;
        }

        inFlight = true;
        outcome.setValue(Outcome.LOADING);
        authRepository.signIn(email, password, new AuthRepository.SignInCallback() {
            @Override
            public void onSuccess() {
                inFlight = false;
                outcome.setValue(Outcome.SUCCESS);
            }

            @Override
            public void onInvalidCredentials() {
                inFlight = false;
                outcome.setValue(Outcome.INVALID_CREDENTIALS);
            }

            @Override
            public void onError(@NonNull Exception e) {
                inFlight = false;
                outcome.setValue(Outcome.ERROR);
            }
        });
    }

    private static List<FieldError> validate(@NonNull String email, @NonNull String password) {
        List<FieldError> errors = new ArrayList<>();
        if (email.isEmpty()) {
            errors.add(new FieldError(Field.EMAIL, R.string.registracija_polje_obavezno));
        }
        if (password.isEmpty()) {
            errors.add(new FieldError(Field.PASSWORD, R.string.registracija_polje_obavezno));
        }
        return errors;
    }
}

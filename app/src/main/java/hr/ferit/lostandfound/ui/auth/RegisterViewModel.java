package hr.ferit.lostandfound.ui.auth;

import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.repo.AuthRepository;

/**
 * Screen logic for {@link RegisterActivity}: it validates the four fields, then
 * hands a valid set to {@link AuthRepository}. It holds no Android framework
 * <em>state</em> — {@link Patterns#EMAIL_ADDRESS} is a stateless constant and the
 * per-field messages are carried as {@code @StringRes} ids the view resolves.
 * It imports no {@code com.google.firebase.*} type.
 *
 * <p>Delivery is single-shot: {@link #outcome()} is cleared with
 * {@link #consumeOutcome()} once the view has handled a terminal value, so a
 * configuration change does not replay a Snackbar or re-navigate.
 */
public class RegisterViewModel extends ViewModel {

    private static final String TAG = "RegisterViewModel";

    /** Username becomes the {@code usernames} document id, so it is tightly constrained. */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,20}$");
    /**
     * Firestore rejects any document id matching {@code __.*__} ("reserved").
     * {@link #USERNAME_PATTERN} alone still admits {@code __x__} / {@code ____},
     * which would make {@code .document(id)} throw synchronously, so it is
     * excluded here before any network call. (Pass 3 review amendment.)
     */
    private static final Pattern RESERVED_ID_PATTERN = Pattern.compile("^__.*__$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    /** The four form fields, in top-to-bottom order (drives first-error focus). */
    public enum Field {
        USERNAME,
        EMAIL,
        PASSWORD,
        PHONE
    }

    /** Terminal + loading states the view renders. */
    public enum Outcome {
        LOADING,
        SUCCESS,
        USERNAME_TAKEN,
        EMAIL_IN_USE,
        PARTIAL_ACCOUNT,
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

    /** Guards against a second submit while a registration attempt is in flight. */
    private boolean inFlight;

    /**
     * True when {@link #fieldErrors} was last set by a fresh submit (so the view
     * should move focus to the first bad field). Cleared by
     * {@link #consumeErrorFocus()} once the view has acted on it, so a
     * configuration-change redelivery of the same list does not re-steal focus.
     */
    private boolean focusPending;

    public RegisterViewModel(@NonNull AuthRepository authRepository) {
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
     * after a configuration change returns {@code false}, so the errors re-render
     * without the focus jumping again.
     */
    public boolean consumeErrorFocus() {
        if (focusPending) {
            focusPending = false;
            return true;
        }
        return false;
    }

    /**
     * Validates the raw field text and, if it passes, starts registration.
     * Identifier fields are trimmed; the password is not (spaces are valid).
     * A no-op while an attempt is already in flight.
     *
     * <p>Validation runs before the connectivity check, so an offline user still
     * sees inline field errors; only a <em>valid</em> offline submit resolves to
     * {@link Outcome#OFFLINE} and makes no Firebase call.
     */
    public void submit(@Nullable String usernameRaw,
                       @Nullable String emailRaw,
                       @Nullable String passwordRaw,
                       @Nullable String phoneRaw,
                       boolean online) {
        if (inFlight) {
            return;
        }

        String username = trimOrEmpty(usernameRaw);
        String email = trimOrEmpty(emailRaw);
        String phone = trimOrEmpty(phoneRaw);
        String password = passwordRaw == null ? "" : passwordRaw; // not trimmed

        List<FieldError> errors = validate(username, email, password, phone);
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
        authRepository.register(username, email, password, phone, new AuthRepository.RegisterCallback() {
            @Override
            public void onSuccess() {
                inFlight = false;
                outcome.setValue(Outcome.SUCCESS);
            }

            @Override
            public void onUsernameTaken() {
                inFlight = false;
                // Deliver the "taken" hint through the same field-error channel as
                // validation errors, so it re-renders (and does not re-steal focus)
                // across a configuration change like every other inline error.
                outcome.setValue(Outcome.USERNAME_TAKEN);
                focusPending = true;
                fieldErrors.setValue(Collections.singletonList(
                        new FieldError(Field.USERNAME, R.string.korisnicko_ime_zauzeto)));
            }

            @Override
            public void onEmailInUse() {
                inFlight = false;
                outcome.setValue(Outcome.EMAIL_IN_USE);
            }

            @Override
            public void onPartialAccount() {
                inFlight = false;
                outcome.setValue(Outcome.PARTIAL_ACCOUNT);
            }

            @Override
            public void onError(@NonNull Exception e) {
                inFlight = false;
                outcome.setValue(Outcome.ERROR);
            }
        });
    }

    private static List<FieldError> validate(@NonNull String username,
                                             @NonNull String email,
                                             @NonNull String password,
                                             @NonNull String phone) {
        List<FieldError> errors = new ArrayList<>();

        if (username.isEmpty()) {
            errors.add(new FieldError(Field.USERNAME, R.string.registracija_polje_obavezno));
        } else if (!USERNAME_PATTERN.matcher(username).matches()
                || RESERVED_ID_PATTERN.matcher(username).matches()) {
            errors.add(new FieldError(Field.USERNAME, R.string.registracija_neispravno_korisnicko_ime));
        }

        if (email.isEmpty()) {
            errors.add(new FieldError(Field.EMAIL, R.string.registracija_polje_obavezno));
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            errors.add(new FieldError(Field.EMAIL, R.string.registracija_neispravna_e_posta));
        }

        if (password.isEmpty()) {
            errors.add(new FieldError(Field.PASSWORD, R.string.registracija_polje_obavezno));
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            errors.add(new FieldError(Field.PASSWORD, R.string.lozinka_prekratka));
        }

        if (phone.isEmpty()) {
            errors.add(new FieldError(Field.PHONE, R.string.registracija_polje_obavezno));
        }

        return errors;
    }

    @NonNull
    private static String trimOrEmpty(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}

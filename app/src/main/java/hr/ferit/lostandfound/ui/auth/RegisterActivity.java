package hr.ferit.lostandfound.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.databinding.ActivityRegisterBinding;
import hr.ferit.lostandfound.ui.auth.RegisterViewModel.Field;
import hr.ferit.lostandfound.ui.auth.RegisterViewModel.FieldError;
import hr.ferit.lostandfound.ui.auth.RegisterViewModel.Outcome;
import hr.ferit.lostandfound.ui.entry.EntryActivity;
import hr.ferit.lostandfound.util.Connectivity;
import hr.ferit.lostandfound.util.SessionLock;
import hr.ferit.lostandfound.util.ViewModelFactory;

/**
 * The registration form. Renders {@link RegisterViewModel} state — inline
 * per-field errors, in-progress button, {@code Snackbar}s — and routes onward
 * through {@link EntryActivity} on success. Imports no {@code com.google.firebase.*}
 * type: all Firebase work lives behind {@code AuthRepository}.
 */
public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";

    private ActivityRegisterBinding binding;
    private RegisterViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this, new ViewModelFactory()).get(RegisterViewModel.class);

        // android:accessibilityHeading is API 28+; back it with the AndroidX call
        // so the wordmark is a heading on minSdk 24-27 too.
        ViewCompat.setAccessibilityHeading(binding.wordmark, true);

        binding.submitButton.setOnClickListener(v -> onSubmit());
        binding.phoneInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSubmit();
                return true;
            }
            return false;
        });

        viewModel.fieldErrors().observe(this, this::renderFieldErrors);
        viewModel.outcome().observe(this, this::renderOutcome);
    }

    private void onSubmit() {
        // Connectivity is passed through so the ViewModel validates the fields
        // first: an offline user with a bad field still gets the inline error.
        viewModel.submit(
                textOf(binding.usernameInput),
                textOf(binding.emailInput),
                textOf(binding.passwordInput),
                textOf(binding.phoneInput),
                Connectivity.isOnline(this));
    }

    private void renderFieldErrors(@Nullable List<FieldError> errors) {
        clearFieldError(binding.usernameLayout);
        clearFieldError(binding.emailLayout);
        clearFieldError(binding.passwordLayout);
        clearFieldError(binding.phoneLayout);
        if (errors == null || errors.isEmpty()) {
            return;
        }
        TextInputLayout firstBad = null;
        for (FieldError error : errors) {
            TextInputLayout layout = layoutFor(error.field);
            layout.setError(getString(error.messageRes));
            if (firstBad == null) {
                firstBad = layout;
            }
        }
        // Only move focus on a fresh submit — not when LiveData redelivers the
        // same list after a configuration change.
        if (firstBad != null && firstBad.getEditText() != null && viewModel.consumeErrorFocus()) {
            firstBad.getEditText().requestFocus();
        }
    }

    private void renderOutcome(@Nullable Outcome outcome) {
        if (outcome == null) {
            return;
        }
        switch (outcome) {
            case LOADING:
                setFormEnabled(false);
                binding.progress.setVisibility(View.VISIBLE);
                binding.getRoot().announceForAccessibility(getString(R.string.registracija_slanje));
                break;
            case SUCCESS:
                viewModel.consumeOutcome();
                // A just-registered user has proved their password; route straight
                // to the Board, not through the biometric lock (AD-12 / SessionLock).
                SessionLock.markUnlocked();
                goToEntrySignedIn();
                break;
            case USERNAME_TAKEN:
                // The inline "taken" error and focus arrive via the fieldErrors observer.
                viewModel.consumeOutcome();
                restoreForm();
                break;
            case EMAIL_IN_USE:
                viewModel.consumeOutcome();
                restoreForm();
                Snackbar.make(binding.getRoot(), R.string.registracija_e_posta_zauzeta, Snackbar.LENGTH_LONG).show();
                break;
            case PARTIAL_ACCOUNT:
                viewModel.consumeOutcome();
                restoreForm();
                Snackbar.make(binding.getRoot(), R.string.registracija_djelomicno, Snackbar.LENGTH_LONG).show();
                break;
            case OFFLINE:
                viewModel.consumeOutcome();
                Snackbar.make(binding.getRoot(), R.string.radnja_zahtijeva_internet, Snackbar.LENGTH_LONG).show();
                break;
            case ERROR:
                viewModel.consumeOutcome();
                restoreForm();
                Snackbar.make(binding.getRoot(), R.string.opca_greska, Snackbar.LENGTH_LONG).show();
                break;
        }
    }

    private void restoreForm() {
        binding.progress.setVisibility(View.INVISIBLE);
        setFormEnabled(true);
    }

    private void setFormEnabled(boolean enabled) {
        binding.usernameInput.setEnabled(enabled);
        binding.emailInput.setEnabled(enabled);
        binding.passwordInput.setEnabled(enabled);
        binding.phoneInput.setEnabled(enabled);
        binding.submitButton.setEnabled(enabled);
    }

    private void goToEntrySignedIn() {
        Intent intent = new Intent(this, EntryActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private TextInputLayout layoutFor(Field field) {
        switch (field) {
            case USERNAME:
                return binding.usernameLayout;
            case EMAIL:
                return binding.emailLayout;
            case PASSWORD:
                return binding.passwordLayout;
            case PHONE:
                return binding.phoneLayout;
            default:
                throw new IllegalArgumentException("Unhandled field: " + field);
        }
    }

    private static void clearFieldError(TextInputLayout layout) {
        layout.setError(null);
    }

    private static String textOf(EditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }
}

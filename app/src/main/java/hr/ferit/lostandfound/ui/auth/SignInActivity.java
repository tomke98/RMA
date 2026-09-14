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
import hr.ferit.lostandfound.databinding.ActivitySignInBinding;
import hr.ferit.lostandfound.ui.auth.SignInViewModel.Field;
import hr.ferit.lostandfound.ui.auth.SignInViewModel.FieldError;
import hr.ferit.lostandfound.ui.auth.SignInViewModel.Outcome;
import hr.ferit.lostandfound.ui.entry.EntryActivity;
import hr.ferit.lostandfound.util.Connectivity;
import hr.ferit.lostandfound.util.SessionLock;
import hr.ferit.lostandfound.util.ViewModelFactory;

/**
 * The email/password sign-in screen. Renders {@link SignInViewModel} state —
 * inline per-field errors, an in-progress button, {@code Snackbar}s — and routes
 * onward through {@link EntryActivity} on success, letting the AD-12 routing table
 * decide the landing screen. Imports no {@code com.google.firebase.*} type: all
 * auth work lives behind {@code AuthRepository}.
 */
public class SignInActivity extends AppCompatActivity {

    private static final String TAG = "SignInActivity";

    private ActivitySignInBinding binding;
    private SignInViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySignInBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this, new ViewModelFactory()).get(SignInViewModel.class);

        // android:accessibilityHeading is API 28+; back it with the AndroidX call
        // so the wordmark is a heading on minSdk 24-27 too.
        ViewCompat.setAccessibilityHeading(binding.wordmark, true);

        binding.submitButton.setOnClickListener(v -> onSubmit());
        binding.passwordInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSubmit();
                return true;
            }
            return false;
        });
        binding.registerButton.setOnClickListener(
                v -> startActivity(new Intent(this, RegisterActivity.class)));

        viewModel.fieldErrors().observe(this, this::renderFieldErrors);
        viewModel.outcome().observe(this, this::renderOutcome);
    }

    private void onSubmit() {
        // Connectivity is passed through so the ViewModel validates the fields
        // first: an offline user with a blank field still gets the inline error.
        viewModel.submit(
                textOf(binding.emailInput),
                textOf(binding.passwordInput),
                Connectivity.isOnline(this));
    }

    private void renderFieldErrors(@Nullable List<FieldError> errors) {
        clearFieldError(binding.emailLayout);
        clearFieldError(binding.passwordLayout);
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
                binding.getRoot().announceForAccessibility(getString(R.string.prijava_slanje));
                break;
            case SUCCESS:
                viewModel.consumeOutcome();
                // The user just proved their password; don't make them pass the
                // biometric lock on the way to the Board (AD-12 / SessionLock).
                SessionLock.markUnlocked();
                goToEntrySignedIn();
                break;
            case INVALID_CREDENTIALS:
                viewModel.consumeOutcome();
                restoreForm();
                Snackbar.make(binding.getRoot(), R.string.netocna_e_posta_ili_lozinka, Snackbar.LENGTH_LONG).show();
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
        binding.emailInput.setEnabled(enabled);
        binding.passwordInput.setEnabled(enabled);
        binding.submitButton.setEnabled(enabled);
        binding.registerButton.setEnabled(enabled);
    }

    private void goToEntrySignedIn() {
        Intent intent = new Intent(this, EntryActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private TextInputLayout layoutFor(Field field) {
        switch (field) {
            case EMAIL:
                return binding.emailLayout;
            case PASSWORD:
                return binding.passwordLayout;
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

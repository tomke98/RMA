package hr.ferit.lostandfound.ui.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import hr.ferit.lostandfound.data.model.Category;
import hr.ferit.lostandfound.data.repo.ReportRepository;
import hr.ferit.lostandfound.ui.report.ReportViewModel.Field;
import hr.ferit.lostandfound.ui.report.ReportViewModel.FieldError;
import hr.ferit.lostandfound.ui.report.ReportViewModel.Outcome;

/**
 * Validation / offline branches from the spec's I/O &amp; Edge-Case Matrix
 * ("Missing required field", "Offline at submit"), covering both the lost
 * (Story 2.3) and found (Story 2.4) validation rules. Uses a {@link ReportRepository}
 * built with {@code null} Firebase clients: every case here returns before the
 * ViewModel ever calls {@code reportRepository.create}, so the repository's
 * fields are never touched. Robolectric only because {@code ViewModel}'s
 * {@code LiveData} needs the Android test runtime present (matches
 * {@code LocationHelperTest} / {@code ImageUtilsTest} convention; no Mockito in
 * this repo).
 *
 * <p>The upload/write-failure matrix rows (photo upload fails; Firestore write
 * fails after a successful photo upload) are verified by code inspection of
 * {@link ReportRepository#create} instead of a test here — this repo has no
 * Firestore/Storage fake convention to drive them from a unit test (noted in
 * {@code deferred-work.md}).
 */
@RunWith(RobolectricTestRunner.class)
public class ReportViewModelTest {

    private final ReportViewModel viewModel = new ReportViewModel(new ReportRepository(null, null));

    @Test
    public void submit_allFieldsMissing_reportsAllThreeErrors() {
        viewModel.submit("lost", null, "", null, null, null, null, true);

        List<FieldError> errors = viewModel.fieldErrors().getValue();
        assertEquals(3, errors.size());
        assertEquals(Field.CATEGORY, errors.get(0).field);
        assertEquals(Field.DESCRIPTION, errors.get(1).field);
        assertEquals(Field.LOCATION, errors.get(2).field);
        assertNull(viewModel.outcome().getValue());
    }

    @Test
    public void submit_descriptionUnderTenChars_reportsDescriptionError() {
        viewModel.submit("lost", Category.KEYS, "too short", 45.55, 18.69, null, null, true);

        List<FieldError> errors = viewModel.fieldErrors().getValue();
        assertEquals(1, errors.size());
        assertEquals(Field.DESCRIPTION, errors.get(0).field);
    }

    @Test
    public void submit_descriptionExactlyTenChars_passesDescriptionValidation() {
        viewModel.submit("lost", Category.KEYS, "1234567890", 45.55, 18.69, null, null, false);

        // Description/category/location all valid; only connectivity is false, so
        // the outcome resolves to OFFLINE rather than a DESCRIPTION field error.
        assertTrue(viewModel.fieldErrors().getValue().isEmpty());
        assertEquals(Outcome.OFFLINE, viewModel.outcome().getValue());
    }

    @Test
    public void submit_missingLocation_reportsLocationError() {
        viewModel.submit("lost", Category.KEYS, "a valid description", null, null, null, null, true);

        List<FieldError> errors = viewModel.fieldErrors().getValue();
        assertEquals(1, errors.size());
        assertEquals(Field.LOCATION, errors.get(0).field);
    }

    @Test
    public void submit_validFieldsOffline_resolvesOfflineWithoutRepositoryCall() {
        viewModel.submit(
                "lost", Category.WALLET, "a valid description", 45.55, 18.69, "Kod fontane", null, false);

        assertEquals(Outcome.OFFLINE, viewModel.outcome().getValue());
    }

    @Test
    public void consumeOutcome_clearsOutcome() {
        viewModel.submit(
                "lost", Category.WALLET, "a valid description", 45.55, 18.69, null, null, false);
        assertEquals(Outcome.OFFLINE, viewModel.outcome().getValue());

        viewModel.consumeOutcome();

        assertNull(viewModel.outcome().getValue());
    }

    @Test
    public void submit_found_missingPhoto_reportsPhotoError() {
        viewModel.submit("found", Category.KEYS, "", 45.55, 18.69, null, null, true);

        List<FieldError> errors = viewModel.fieldErrors().getValue();
        assertEquals(1, errors.size());
        assertEquals(Field.PHOTO, errors.get(0).field);
    }

    @Test
    public void submit_found_emptyDescriptionWithPhoto_passesValidation() {
        viewModel.submit(
                "found", Category.KEYS, "", 45.55, 18.69, null, "/tmp/photo.jpg", false);

        // Description is not checked for found; category/location/photo are all
        // present, so only connectivity blocks the write.
        assertTrue(viewModel.fieldErrors().getValue().isEmpty());
        assertEquals(Outcome.OFFLINE, viewModel.outcome().getValue());
    }

    @Test
    public void submit_found_missingCategoryAndLocation_reportsBothErrorsNotDescription() {
        viewModel.submit("found", null, "", null, null, null, "/tmp/photo.jpg", true);

        List<FieldError> errors = viewModel.fieldErrors().getValue();
        assertEquals(2, errors.size());
        assertEquals(Field.CATEGORY, errors.get(0).field);
        assertEquals(Field.LOCATION, errors.get(1).field);
    }

    /**
     * Double-tap submit (I/O matrix): "second tap while first in flight is a
     * no-op." The {@code inFlight}-guarded online path calls into
     * {@code ReportRepository.create}, which needs a live {@code FirebaseAuth}
     * this repo has no fake for (same gap as the upload/write-failure rows), so
     * {@code inFlight} is set directly via reflection to exercise the guard
     * itself in isolation: with it {@code true}, a fresh, fully valid submit
     * must return immediately without touching either LiveData.
     */
    @Test
    public void submit_whileInFlight_isNoOp() throws Exception {
        java.lang.reflect.Field inFlightField = ReportViewModel.class.getDeclaredField("inFlight");
        inFlightField.setAccessible(true);
        inFlightField.set(viewModel, true);

        viewModel.submit(
                "lost", Category.WALLET, "a valid description", 45.55, 18.69, null, null, true);

        assertNull(viewModel.fieldErrors().getValue());
        assertNull(viewModel.outcome().getValue());
    }
}

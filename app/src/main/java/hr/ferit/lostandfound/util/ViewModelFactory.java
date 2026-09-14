package hr.ferit.lostandfound.util;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.google.firebase.auth.FirebaseUser;

import hr.ferit.lostandfound.ui.auth.RegisterViewModel;
import hr.ferit.lostandfound.ui.auth.SignInViewModel;
import hr.ferit.lostandfound.ui.board.BoardViewModel;
import hr.ferit.lostandfound.ui.detail.ReportDetailViewModel;
import hr.ferit.lostandfound.ui.report.ReportViewModel;
import hr.ferit.lostandfound.ui.reports.MyReportsViewModel;

/**
 * Hand-rolled {@link ViewModelProvider.Factory} (no Hilt/Dagger). It wires each
 * {@code ViewModel} from the {@link ServiceLocator}, keeping the repository
 * lookup out of the {@code Activity}. Reused by Story 1.3.
 */
public class ViewModelFactory implements ViewModelProvider.Factory {

    private static final String TAG = "ViewModelFactory";

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(RegisterViewModel.class)) {
            return (T) new RegisterViewModel(ServiceLocator.authRepository());
        }
        if (modelClass.isAssignableFrom(SignInViewModel.class)) {
            return (T) new SignInViewModel(ServiceLocator.authRepository());
        }
        if (modelClass.isAssignableFrom(ReportViewModel.class)) {
            return (T) new ReportViewModel(ServiceLocator.reportRepository());
        }
        if (modelClass.isAssignableFrom(ReportDetailViewModel.class)) {
            FirebaseUser user = ServiceLocator.auth().getCurrentUser();
            String uid = user != null ? user.getUid() : null;
            return (T) new ReportDetailViewModel(ServiceLocator.reportRepository(), uid);
        }
        if (modelClass.isAssignableFrom(MyReportsViewModel.class)) {
            FirebaseUser user = ServiceLocator.auth().getCurrentUser();
            String uid = user != null ? user.getUid() : null;
            return (T) new MyReportsViewModel(ServiceLocator.reportRepository(), uid);
        }
        if (modelClass.isAssignableFrom(BoardViewModel.class)) {
            // Ensures CategoryNotifier (Story 4.2) is subscribed to
            // BoardRepository's added-report hook no later than the Board
            // listener itself starts, so it never misses a snapshot.
            ServiceLocator.categoryNotifier();
            return (T) new BoardViewModel(ServiceLocator.boardRepository());
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}

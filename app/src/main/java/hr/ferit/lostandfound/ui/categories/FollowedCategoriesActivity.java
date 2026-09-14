package hr.ferit.lostandfound.ui.categories;

import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseUser;

import java.util.Collections;
import java.util.Set;

import hr.ferit.lostandfound.R;
import hr.ferit.lostandfound.data.model.Category;
import hr.ferit.lostandfound.util.FollowedCategoriesStore;
import hr.ferit.lostandfound.util.ServiceLocator;

/**
 * "Praćene kategorije" (Story 4.1): a fixed checklist of all 10 {@link
 * Category} members, reached from {@code BoardActivity}'s overflow menu. Each
 * row's {@code CheckBox} persists the updated followed set to {@link
 * FollowedCategoriesStore} immediately on toggle — no save button, no
 * ViewModel/Resource machinery, since reads/writes are synchronous local
 * prefs, not network (this story never touches Firestore or the Board
 * listener; that is Story 4.2).
 *
 * <p>The followed set is keyed per-UID (obtained via {@link
 * ServiceLocator#auth()}, mirroring {@code ViewModelFactory}'s pattern) so
 * switching accounts on the same device never shows or leaks another
 * account's selection.
 */
public class FollowedCategoriesActivity extends AppCompatActivity {

    @Nullable
    private String uid;

    private FollowedCategoriesStore store;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_followed_categories);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        FirebaseUser user = ServiceLocator.auth().getCurrentUser();
        uid = user != null ? user.getUid() : null;
        store = ServiceLocator.followedCategoriesStore();

        LinearLayout container = findViewById(R.id.categoriesContainer);
        buildRows(container);
    }

    private void buildRows(LinearLayout container) {
        String[] labels = getResources().getStringArray(R.array.kategorije_nazivi);
        Set<Category> followed = uid != null ? store.getFollowed(uid) : Collections.emptySet();

        for (Category category : Category.values()) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(labels[category.ordinal()]);
            checkBox.setChecked(followed.contains(category));
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> onToggle(category, isChecked));
            container.addView(checkBox);
        }
    }

    private void onToggle(Category category, boolean isChecked) {
        if (uid == null) {
            // No signed-in user to key the preference by; nothing to persist
            // (fail-soft, matches AD-14 — never crash, this screen is only
            // reachable while signed in via BoardActivity anyway).
            return;
        }
        Set<Category> followed = store.getFollowed(uid);
        if (isChecked) {
            followed.add(category);
        } else {
            followed.remove(category);
        }
        store.setFollowed(uid, followed);
    }
}

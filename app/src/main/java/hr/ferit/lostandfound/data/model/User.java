package hr.ferit.lostandfound.data.model;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.ServerTimestamp;

/**
 * A registered account (AD-4, AD-18). The {@code users} document id is the Firebase
 * Auth UID. Written only by {@code AuthRepository} in the registration transaction.
 * {@code phone} is captured at registration and never displayed in Epic 1; it is
 * later copied into {@code Report.reporterPhone}.
 */
public class User {

    private static final String TAG = "User";

    private String username;
    private String email;
    private String phone;

    @ServerTimestamp
    private Timestamp createdAt;

    /** Required by Firestore. */
    public User() {
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}

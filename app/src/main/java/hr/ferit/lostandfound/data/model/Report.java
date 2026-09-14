package hr.ferit.lostandfound.data.model;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

/**
 * A lost or found report (AD-4, AD-16). Plain Java, no framework dependencies
 * beyond the Firestore timestamp type. Firestore needs the no-arg constructor and
 * JavaBean getters/setters to (de)serialise a {@code reports} document.
 *
 * <p>Field-shape conventions (ARCHITECTURE-SPINE §Consistency Conventions):
 * {@code type} in {"lost","found"}, {@code status} in {"open","closed"} (lowercase,
 * exact); a single {@code category} string equal to {@link Category#name()};
 * flat {@code double lat/lng} (never a GeoPoint); {@code photoUrl} null only for a
 * lost report; {@code reporterPhone} denormalised and mandatory.
 */
public class Report {

    private static final String TAG = "Report";

    @DocumentId
    private String id;
    private String type;
    private String status;
    private String category;
    private String description;
    private String photoUrl;
    private double lat;
    private double lng;
    private String locationLabel;
    private String reporterId;
    private String reporterPhone;

    @ServerTimestamp
    private Timestamp createdAt;

    /** Required by Firestore. */
    public Report() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLng() {
        return lng;
    }

    public void setLng(double lng) {
        this.lng = lng;
    }

    public String getLocationLabel() {
        return locationLabel;
    }

    public void setLocationLabel(String locationLabel) {
        this.locationLabel = locationLabel;
    }

    public String getReporterId() {
        return reporterId;
    }

    public void setReporterId(String reporterId) {
        this.reporterId = reporterId;
    }

    public String getReporterPhone() {
        return reporterPhone;
    }

    public void setReporterPhone(String reporterPhone) {
        this.reporterPhone = reporterPhone;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}

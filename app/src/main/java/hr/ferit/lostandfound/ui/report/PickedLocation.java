package hr.ferit.lostandfound.ui.report;

import androidx.annotation.Nullable;

/**
 * Immutable transport value returned by {@link LocationPickerActivity.Contract}.
 * Plain data only — no framework import, no Firebase type. Stories 2.3/2.4 read
 * the three fields and pass primitives to their {@code ReportViewModel}; this type
 * is never persisted and never handed to a repository as an object.
 */
public final class PickedLocation {

    private final double lat;
    private final double lng;

    @Nullable
    private final String label;

    public PickedLocation(double lat, double lng, @Nullable String label) {
        this.lat = lat;
        this.lng = lng;
        this.label = label;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    @Nullable
    public String getLabel() {
        return label;
    }
}

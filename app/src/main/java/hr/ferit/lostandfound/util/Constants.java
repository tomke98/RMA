package hr.ferit.lostandfound.util;

import com.google.android.gms.maps.model.LatLng;

/**
 * Shared tuning constants and typed Intent extra keys. One home for values that
 * would otherwise be typed inline across screens (ARCHITECTURE-SPINE
 * §Consistency Conventions).
 */
public final class Constants {

    private static final String TAG = "Constants";

    private Constants() {
    }

    /** Map fallback centre when there is no location fix: central Osijek (AD-10). */
    public static final LatLng OSIJEK_CENTER = new LatLng(45.5550d, 18.6955d);

    /** Frozen Board query cap (AD-3). */
    public static final int BOARD_LIMIT = 200;

    /** Firestore auto-id of a report, passed to {@code ReportDetailActivity}. */
    public static final String EXTRA_REPORT_ID = "hr.ferit.lostandfound.extra.REPORT_ID";
}

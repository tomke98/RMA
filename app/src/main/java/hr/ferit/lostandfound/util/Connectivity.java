package hr.ferit.lostandfound.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

/**
 * One-line connectivity probe shared by the auth screens. "Online" means there is
 * an active network with both {@code NET_CAPABILITY_INTERNET} and
 * {@code NET_CAPABILITY_VALIDATED}, so a captive portal counts as offline
 * (AD-14 / the auth edge-case matrices). Extracted from {@code RegisterActivity}
 * in Story 1.3 so {@code SignInActivity} reuses the exact same check.
 */
public final class Connectivity {

    private static final String TAG = "Connectivity";

    private Connectivity() {
    }

    public static boolean isOnline(@NonNull Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}

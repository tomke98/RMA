package hr.ferit.lostandfound.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import hr.ferit.lostandfound.R;

/**
 * Creates the app's one notification channel (Story 4.2), used by every
 * {@link CategoryNotifier} notification. Called once from {@code
 * App.onCreate()} so the channel exists before any {@code notify()} call --
 * {@link NotificationManager#createNotificationChannel} is itself idempotent
 * (a repeat call with the same id is a safe no-op), so this can be called on
 * every process start without guarding.
 */
public final class NotificationChannels {

    /** The single channel every {@link CategoryNotifier} notification posts to. */
    public static final String CHANNEL_ID_CATEGORY_REPORTS = "category_reports";

    private NotificationChannels() {
    }

    public static void createChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Channels don't exist before API 26; notify() ignores channel id there.
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID_CATEGORY_REPORTS,
                context.getString(R.string.obavijest_kanal_naziv),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.obavijest_kanal_opis));
        manager.createNotificationChannel(channel);
    }
}

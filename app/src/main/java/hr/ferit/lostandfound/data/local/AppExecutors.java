package hr.ferit.lostandfound.data.local;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single data-layer executor for all Room access (AD-13). One
 * {@code newSingleThreadExecutor()} serialises every local read and write; no
 * {@code AsyncTask} or raw {@code Thread} is used for persistence anywhere.
 */
public final class AppExecutors {

    private static final String TAG = "AppExecutors";

    private static final ExecutorService DISK_IO = Executors.newSingleThreadExecutor();

    private AppExecutors() {
    }

    /** The one background thread every Room operation runs on. */
    public static ExecutorService diskIO() {
        return DISK_IO;
    }
}

package hr.ferit.lostandfound.data.model;

/**
 * Generic loading / success / error wrapper (AD-14). ViewModels expose
 * {@code LiveData<Resource<T>>} so a view can render a spinner, the data, or an
 * error message from one stream.
 *
 * @param <T> payload type
 */
public final class Resource<T> {

    private static final String TAG = "Resource";

    /** The three states a data request can be in. */
    public enum Status {
        LOADING,
        SUCCESS,
        ERROR
    }

    public final Status status;
    public final T data;
    public final String message;

    private Resource(Status status, T data, String message) {
        this.status = status;
        this.data = data;
        this.message = message;
    }

    public static <T> Resource<T> loading() {
        return new Resource<>(Status.LOADING, null, null);
    }

    public static <T> Resource<T> loading(T data) {
        return new Resource<>(Status.LOADING, data, null);
    }

    public static <T> Resource<T> success(T data) {
        return new Resource<>(Status.SUCCESS, data, null);
    }

    public static <T> Resource<T> error(String message) {
        return new Resource<>(Status.ERROR, null, message);
    }

    public static <T> Resource<T> error(String message, T data) {
        return new Resource<>(Status.ERROR, data, message);
    }

    public boolean isLoading() {
        return status == Status.LOADING;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }
}

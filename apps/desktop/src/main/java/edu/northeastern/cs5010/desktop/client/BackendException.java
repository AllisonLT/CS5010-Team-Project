package edu.northeastern.cs5010.desktop.client;

/** The backend answered with an error body. {@link #getMessage()} is the user-facing {@code detail}. */
public class BackendException extends RuntimeException {
    private final ApiModels.ApiError error;

    public BackendException(ApiModels.ApiError error) {
        super(error.detail());
        this.error = error;
    }

    public ApiModels.ApiError error() {
        return error;
    }
}

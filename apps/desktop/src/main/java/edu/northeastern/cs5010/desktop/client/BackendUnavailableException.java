package edu.northeastern.cs5010.desktop.client;

/** No answer from the backend at all (not running, wrong port). */
public class BackendUnavailableException extends RuntimeException {
    public BackendUnavailableException(String baseUrl, Throwable cause) {
        super("Cannot connect to the backend at " + baseUrl + ".", cause);
    }
}

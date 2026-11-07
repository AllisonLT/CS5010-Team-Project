package edu.northeastern.cs5010.desktop.ui;

import javafx.concurrent.Task;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Runs backend calls off the JavaFX thread so the window stays responsive. */
public final class Async {
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "backend-call");
        thread.setDaemon(true);
        return thread;
    });

    private Async() {
    }

    /** Runs {@code work} in the background; both callbacks run on the JavaFX thread. */
    public static <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> onFailure.accept(task.getException()));
        WORKERS.submit(task);
    }
}

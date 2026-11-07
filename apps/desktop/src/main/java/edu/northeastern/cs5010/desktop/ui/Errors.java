package edu.northeastern.cs5010.desktop.ui;

import edu.northeastern.cs5010.desktop.client.ApiModels;
import edu.northeastern.cs5010.desktop.client.BackendException;
import edu.northeastern.cs5010.desktop.client.BackendUnavailableException;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;

import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Shows backend errors to the user (docs/wireframes.md): the response's {@code detail} as the message, and the
 * list of field or row problems in the dialog's expandable section. A lost connection is handed to the main
 * window, which replaces the screen with a Retry page.
 */
public final class Errors {
    private static Consumer<BackendUnavailableException> connectionLost = e -> { };

    private Errors() {
    }

    static void onConnectionLost(Consumer<BackendUnavailableException> handler) {
        connectionLost = handler;
    }

    public static void show(Throwable error) {
        if (error instanceof BackendUnavailableException lost) {
            connectionLost.accept(lost);
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Smart Cold Storage Monitor");
        if (error instanceof BackendException backend) {
            ApiModels.ApiError body = backend.error();
            alert.setHeaderText(body.title());
            alert.setContentText(body.detail());
            if (body.errors() != null && !body.errors().isEmpty()) {
                TextArea list = new TextArea(body.errors().stream().map(Errors::describe)
                        .collect(Collectors.joining("\n")));
                list.setEditable(false);
                list.setWrapText(true);
                alert.getDialogPane().setExpandableContent(list);
                alert.getDialogPane().setExpanded(true);
            }
        } else {
            alert.setHeaderText("Something went wrong");
            alert.setContentText(error.getMessage() == null ? error.toString() : error.getMessage());
        }
        alert.showAndWait();
    }

    static String describe(ApiModels.FieldError e) {
        StringBuilder text = new StringBuilder();
        if (e.row() != null) {
            text.append("Line ").append(e.row()).append(", ");
        }
        if (e.field() != null && !"file".equals(e.field())) {
            text.append(e.field()).append(": ");
        }
        return text.append(e.message()).toString();
    }
}

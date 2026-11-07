package edu.northeastern.cs5010.desktop.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small shared building blocks for screens. */
final class Views {
    static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private Views() {
    }

    static VBox page(String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("heading");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        VBox page = new VBox(12, heading);
        page.setPadding(new Insets(16));
        return page;
    }

    static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    static String local(Instant instant) {
        return instant == null ? "" : LOCAL_TIME.format(instant);
    }

    /** Dataset timestamps are naive local times; show them with a space instead of 'T'. */
    static String naive(String timestamp) {
        return timestamp == null ? "" : timestamp.replace('T', ' ');
    }

    static String celsius(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%.1f °C", value);
    }

    static String count(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}

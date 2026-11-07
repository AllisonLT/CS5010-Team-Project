package edu.northeastern.cs5010.desktop.ui;

import edu.northeastern.cs5010.desktop.client.BackendClient;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** Home screen: counts and shortcuts. */
class HomeView {
    private final VBox page = Views.page("Home");

    HomeView(BackendClient client, Consumer<Screen> open) {
        Label datasets = new Label("Datasets uploaded: …");
        Button upload = new Button("Upload a dataset");
        upload.setOnAction(e -> open.accept(Screen.DATASETS));
        Button forecast = new Button("Run a forecast");
        forecast.setOnAction(e -> open.accept(Screen.FORECAST));
        Button shelfLife = new Button("Estimate shelf life");
        shelfLife.setOnAction(e -> open.accept(Screen.SHELF_LIFE));

        page.getChildren().addAll(datasets, new HBox(12, upload, forecast, shelfLife),
                Views.wrapped("These predictions support decisions only. They are not food safety certifications."));

        Async.run(client::datasets, list -> datasets.setText("Datasets uploaded: " + list.size()),
                error -> datasets.setText("Datasets uploaded: unavailable"));
    }

    Node node() {
        return page;
    }
}

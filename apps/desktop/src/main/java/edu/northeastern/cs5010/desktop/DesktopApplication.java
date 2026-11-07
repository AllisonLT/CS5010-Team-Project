package edu.northeastern.cs5010.desktop;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.stage.Stage;

public class DesktopApplication extends Application {
    @Override
    public void start(Stage stage) {
        VBox content = new VBox(16,
                new Label("Smart Cold Storage Monitor"),
                new Label("Development scaffold: forecasting and spoilage features are not implemented."),
                new Label("Next: dataset selection, forecasting, products, history, and audit review."));
        content.setPadding(new Insets(24));
        stage.setTitle("Smart Cold Storage Monitor");
        stage.setScene(new Scene(content, 760, 320));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

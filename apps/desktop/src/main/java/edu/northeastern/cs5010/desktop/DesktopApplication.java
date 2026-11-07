package edu.northeastern.cs5010.desktop;

import edu.northeastern.cs5010.desktop.client.BackendClient;
import edu.northeastern.cs5010.desktop.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Desktop entry point. The backend URL comes from {@code BACKEND_URL} or {@code -Dbackend.url}, defaulting to
 * {@value BackendClient#DEFAULT_URL}.
 */
public class DesktopApplication extends Application {
    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(BackendClient.fromEnvironment());
        stage.setTitle("Smart Cold Storage Monitor");
        stage.setScene(new Scene(window.root(), 1000, 680));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

package edu.northeastern.cs5010.desktop.ui;

import edu.northeastern.cs5010.desktop.client.ApiModels;
import edu.northeastern.cs5010.desktop.client.BackendClient;
import edu.northeastern.cs5010.desktop.client.BackendUnavailableException;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Window layout from docs/wireframes.md: navigation list on the left, the selected screen in the center, and a
 * status bar for the backend, MongoDB, and the ML service at the bottom. If the backend cannot be reached, the
 * whole window shows a Retry page instead.
 */
public class MainWindow {
    private final BackendClient client;
    private final BorderPane root = new BorderPane();
    private final BorderPane app = new BorderPane();
    private final ListView<Screen> navigation = new ListView<>();
    private final Label backendStatus = new Label("Backend: …");
    private final Label databaseStatus = new Label("Database: …");
    private final Label mlStatus = new Label("ML service: …");
    private final Map<Screen, Supplier<Node>> factories = new EnumMap<>(Screen.class);
    private final Map<Screen, Node> screens = new EnumMap<>(Screen.class);

    public MainWindow(BackendClient client) {
        this.client = client;
        Errors.onConnectionLost(this::showConnectionLost);

        factories.put(Screen.HOME, () -> new HomeView(client, this::open).node());
        factories.put(Screen.DATASETS, () -> new DatasetsView(client).node());
        factories.put(Screen.FORECAST, () -> new ForecastView(client).node());
        factories.put(Screen.SHELF_LIFE, () -> placeholder(Screen.SHELF_LIFE, "#38"));
        factories.put(Screen.HISTORY, () -> placeholder(Screen.HISTORY, "#39"));
        factories.put(Screen.AUDIT, () -> placeholder(Screen.AUDIT, "#40"));

        navigation.getItems().setAll(Screen.values());
        navigation.setPrefWidth(190);
        navigation.getSelectionModel().selectedItemProperty().addListener((obs, old, screen) -> {
            if (screen != null) {
                show(screen);
            }
        });

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refreshStatus());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(24, backendStatus, databaseStatus, mlStatus, spacer, refresh);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setStyle("-fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");

        app.setLeft(navigation);
        app.setBottom(statusBar);
        root.setCenter(app);

        Timeline poll = new Timeline(new KeyFrame(Duration.seconds(30), e -> refreshStatus()));
        poll.setCycleCount(Timeline.INDEFINITE);
        poll.play();
        navigation.getSelectionModel().select(Screen.HOME);
        refreshStatus();
    }

    public BorderPane root() {
        return root;
    }

    /** Opens a screen as if it had been clicked in the navigation list. */
    void open(Screen screen) {
        navigation.getSelectionModel().select(screen);
    }

    private void show(Screen screen) {
        // Datasets can change on other screens, so those screens are rebuilt each time they are opened.
        Node node = screen == Screen.HOME || screen == Screen.DATASETS || screen == Screen.FORECAST
                ? factories.get(screen).get()
                : screens.computeIfAbsent(screen, s -> factories.get(s).get());
        app.setCenter(node);
    }

    private void refreshStatus() {
        Async.run(client::status, this::showStatus, error -> {
            if (error instanceof BackendUnavailableException lost) {
                showConnectionLost(lost);
            } else {
                backendStatus.setText("Backend: ERROR");
            }
        });
    }

    private void showStatus(ApiModels.Status status) {
        if (root.getCenter() != app) {
            root.setCenter(app);
            show(navigation.getSelectionModel().getSelectedItem());
        }
        backendStatus.setText("Backend: " + status.backend());
        databaseStatus.setText("Database: " + status.database());
        String ml = switch (status.mlService()) {
            case "NOT_READY" -> "NOT READY (models missing)";
            default -> status.mlService();
        };
        mlStatus.setText("ML service: " + ml);
        mlStatus.setStyle("UP".equals(status.mlService()) ? "" : "-fx-text-fill: #b00020;");
        databaseStatus.setStyle("UP".equals(status.database()) ? "" : "-fx-text-fill: #b00020;");
    }

    private void showConnectionLost(BackendUnavailableException error) {
        Label message = Views.wrapped(error.getMessage()
                + " Start it with the command in docs/development.md, then click Retry.");
        Button retry = new Button("Retry");
        retry.setOnAction(e -> refreshStatus());
        VBox page = new VBox(16, message, retry);
        page.setAlignment(Pos.CENTER);
        page.setPadding(new Insets(48));
        page.setMaxWidth(560);
        root.setCenter(page);
    }

    private static Node placeholder(Screen screen, String issue) {
        VBox page = Views.page(screen.toString());
        page.getChildren().add(Views.wrapped("This screen is planned for a later milestone (issue " + issue + ")."));
        return page;
    }
}

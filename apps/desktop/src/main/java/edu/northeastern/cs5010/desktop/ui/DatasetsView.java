package edu.northeastern.cs5010.desktop.ui;

import edu.northeastern.cs5010.desktop.client.ApiModels;
import edu.northeastern.cs5010.desktop.client.BackendClient;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Datasets screen (docs/wireframes.md, section 2): upload, list, details, delete. */
class DatasetsView {
    private static final Pattern SENSOR_IN_NAME = Pattern.compile("sensor[_\\- ]?(\\d+)", Pattern.CASE_INSENSITIVE);

    private final BackendClient client;
    private final VBox page = Views.page("Datasets");
    private final TableView<ApiModels.Dataset> table = new TableView<>();
    private final VBox details = new VBox(4);
    private final Button upload = new Button("Upload CSV…");
    private final Button delete = new Button("Delete");
    private final ProgressIndicator working = new ProgressIndicator();

    DatasetsView(BackendClient client) {
        this.client = client;
        working.setMaxSize(20, 20);
        working.setVisible(false);
        delete.setDisable(true);
        upload.setOnAction(e -> chooseFile());
        delete.setOnAction(e -> confirmDelete());

        table.getColumns().add(column("Name", ApiModels.Dataset::displayName, 190));
        table.getColumns().add(column("File", ApiModels.Dataset::originalFilename, 230));
        table.getColumns().add(column("Sensors", d -> String.join(", ", d.sensorIds()), 110));
        table.getColumns().add(column("Rows", d -> Views.count(d.recordCount()), 90));
        table.getColumns().add(column("Status", d -> statusText(d.status()), 100));
        table.setPlaceholder(new Label("No datasets yet. Click \"Upload CSV…\" to add one."));
        table.setPrefHeight(240);
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            delete.setDisable(selected == null);
            loadDetails(selected);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        details.setPadding(new Insets(8));
        details.setStyle("-fx-border-color: #cccccc;");
        page.getChildren().addAll(new HBox(8, spacer, working, upload, delete), table, details);
        VBox.setVgrow(table, Priority.ALWAYS);
        reload(null);
    }

    Node node() {
        return page;
    }

    private static TableColumn<ApiModels.Dataset, String> column(String title,
                                                                 Function<ApiModels.Dataset, String> value,
                                                                 double width) {
        TableColumn<ApiModels.Dataset, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    static String statusText(String status) {
        return switch (status) {
            case "READY" -> "Ready";
            case "MISSING_FILE" -> "File missing";
            case "DELETED" -> "Deleted";
            default -> status;
        };
    }

    private void reload(String selectId) {
        Async.run(client::datasets, list -> {
            table.getItems().setAll(list);
            list.stream().filter(d -> d.id().equals(selectId)).findFirst()
                    .ifPresent(d -> table.getSelectionModel().select(d));
            if (selectId == null) {
                details.getChildren().setAll(new Label("Select a dataset to see its details."));
            }
        }, Errors::show);
    }

    private void loadDetails(ApiModels.Dataset selected) {
        if (selected == null) {
            details.getChildren().setAll(new Label("Select a dataset to see its details."));
            return;
        }
        Async.run(() -> client.dataset(selected.id()), this::showDetails, Errors::show);
    }

    private void showDetails(ApiModels.Dataset d) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(4);
        int row = 0;
        grid.addRow(row++, new Label("Columns:"), new Label(String.join(", ", columnsOf(d))));
        grid.addRow(row++, new Label("Time range:"),
                new Label(Views.naive(d.timeRange().start()) + " to " + Views.naive(d.timeRange().end())));
        ApiModels.MinuteSummary m = d.minuteSummary();
        if (m != null) {
            grid.addRow(row++, new Label("Minutes:"), new Label(Views.count(m.minuteCount()) + " ("
                    + Views.count(m.missingMinuteCount()) + " missing; longest gap " + m.longestGapMinutes()
                    + (m.longestGapMinutes() == 1 ? " minute)" : " minutes)")));
            grid.addRow(row++, new Label("Temperature:"),
                    new Label(Views.celsius(m.temperatureMinC()) + " to " + Views.celsius(m.temperatureMaxC())));
        }
        grid.addRow(row++, new Label("Rows:"), new Label(Views.count(d.recordCount()) + " read, "
                + Views.count(d.acceptedRecordCount()) + " with a valid temperature"));
        VBox warnings = new VBox(2);
        if (d.warnings() == null || d.warnings().isEmpty()) {
            warnings.getChildren().add(new Label("none"));
        } else {
            d.warnings().forEach(w -> warnings.getChildren().add(Views.wrapped("• " + w.message())));
        }
        grid.addRow(row, new Label("Warnings:"), warnings);
        Label title = new Label("Details: " + d.displayName());
        title.setStyle("-fx-font-weight: bold;");
        details.getChildren().setAll(title, grid);
        if ("MISSING_FILE".equals(d.status())) {
            Label missing = Views.wrapped("The stored file for this dataset is missing. It cannot be used.");
            missing.setStyle("-fx-text-fill: #b00020;");
            details.getChildren().add(missing);
        }
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Upload dataset");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File file = chooser.showOpenDialog(page.getScene().getWindow());
        if (file != null) {
            askDetails(file.toPath()).ifPresent(choice -> send(file.toPath(), choice));
        }
    }

    private record UploadChoice(String displayName, String sensorId) {
    }

    private Optional<UploadChoice> askDetails(Path file) {
        String filename = file.getFileName().toString();
        String stem = filename.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? filename.substring(0, filename.length() - 4) : filename;
        Matcher sensor = SENSOR_IN_NAME.matcher(filename);
        TextField name = new TextField(stem);
        TextField sensorId = new TextField(sensor.find()
                ? String.format(Locale.ROOT, "SENSOR%02d", Integer.parseInt(sensor.group(1))) : "");
        sensorId.setPromptText("Needed if the file has no sensor_id column");

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("File:"), new Label(filename + " (" + size(file) + ")"));
        form.addRow(1, new Label("Display name:"), name);
        form.addRow(2, new Label("Sensor ID:"), sensorId);

        Dialog<UploadChoice> dialog = new Dialog<>();
        dialog.setTitle("Upload dataset");
        dialog.getDialogPane().setContent(form);
        ButtonType uploadButton = new ButtonType("Upload", ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, uploadButton);
        dialog.getDialogPane().lookupButton(uploadButton).disableProperty()
                .bind(name.textProperty().isEmpty());
        dialog.setResultConverter(button -> button == uploadButton
                ? new UploadChoice(name.getText(), sensorId.getText()) : null);
        return dialog.showAndWait();
    }

    private void send(Path file, UploadChoice choice) {
        setWorking(true);
        Async.run(() -> client.upload(file, choice.displayName(), choice.sensorId()), stored -> {
            setWorking(false);
            reload(stored.id());
        }, error -> {
            setWorking(false);
            Errors.show(error);
        });
    }

    private void confirmDelete() {
        ApiModels.Dataset selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete '" + selected.displayName() + "'? Products linked to it will lose their temperature "
                        + "history. Past predictions stay in the history.", ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText("Delete dataset");
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isPresent()) {
            setWorking(true);
            Async.run(() -> {
                client.deleteDataset(selected.id());
                return null;
            }, ignored -> {
                setWorking(false);
                reload(null);
            }, error -> {
                setWorking(false);
                Errors.show(error);
            });
        }
    }

    private void setWorking(boolean busy) {
        working.setVisible(busy);
        upload.setDisable(busy);
        delete.setDisable(busy || table.getSelectionModel().getSelectedItem() == null);
        upload.setText(busy ? "Working…" : "Upload CSV…");
    }

    private static String size(Path file) {
        try {
            double mb = Files.size(file) / (1024.0 * 1024.0);
            return String.format(Locale.ROOT, "%.1f MB", mb);
        } catch (Exception e) {
            return "size unknown";
        }
    }

    static List<String> columnsOf(ApiModels.Dataset d) {
        return d.columns() == null ? List.of() : d.columns();
    }
}

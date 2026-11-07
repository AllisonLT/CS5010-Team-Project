package edu.northeastern.cs5010.desktop.ui;

import edu.northeastern.cs5010.desktop.client.ApiModels;
import edu.northeastern.cs5010.desktop.client.BackendClient;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Temperature Forecast screen (docs/wireframes.md, section 3). Results are text and a table, as the PDF asks.
 * The optional chart from the wireframe is left for #37.
 */
class ForecastView {
    static final DateTimeFormatter INPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BackendClient client;
    private final VBox page = Views.page("Temperature Forecast");
    private final ComboBox<ApiModels.Dataset> dataset = new ComboBox<>();
    private final ComboBox<String> sensor = new ComboBox<>();
    private final RadioButton latest = new RadioButton("Latest available");
    private final RadioButton chosen = new RadioButton("Choose time");
    private final TextField origin = new TextField();
    private final Map<Integer, CheckBox> horizons = new HashMap<>();
    private final TextField threshold = new TextField("10.0");
    private final Label inputProblem = new Label();
    private final Button run = new Button("Run forecast");
    private final ProgressIndicator working = new ProgressIndicator();
    private final VBox results = new VBox(8);

    ForecastView(BackendClient client) {
        this.client = client;
        dataset.setPromptText("Choose a dataset");
        dataset.setConverter(new StringConverter<>() {
            @Override
            public String toString(ApiModels.Dataset d) {
                return d == null ? "" : d.displayName();
            }

            @Override
            public ApiModels.Dataset fromString(String text) {
                return null;
            }
        });
        dataset.valueProperty().addListener((obs, old, d) -> {
            sensor.getItems().setAll(d == null ? List.of() : d.sensorIds());
            sensor.getSelectionModel().selectFirst();
        });
        ToggleGroup originChoice = new ToggleGroup();
        latest.setToggleGroup(originChoice);
        chosen.setToggleGroup(originChoice);
        latest.setSelected(true);
        origin.setPromptText("2024-09-09 14:00");
        origin.disableProperty().bind(latest.selectedProperty());

        HBox horizonBoxes = new HBox(12);
        for (int h : List.of(15, 30, 60)) {
            CheckBox box = new CheckBox(h + " min");
            box.setSelected(true);
            box.selectedProperty().addListener((obs, old, now) -> validate());
            horizons.put(h, box);
            horizonBoxes.getChildren().add(box);
        }
        threshold.setPrefColumnCount(6);
        threshold.textProperty().addListener((obs, old, now) -> validate());
        origin.textProperty().addListener((obs, old, now) -> validate());
        latest.selectedProperty().addListener((obs, old, now) -> validate());
        sensor.valueProperty().addListener((obs, old, now) -> validate());
        inputProblem.setStyle("-fx-text-fill: #b00020;");
        working.setMaxSize(20, 20);
        working.setVisible(false);
        run.setOnAction(e -> runForecast());

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Dataset:"), dataset, new Label("Sensor:"), sensor);
        form.addRow(1, new Label("Forecast from:"), new HBox(12, latest, chosen, origin));
        GridPane.setColumnSpan(form.getChildren().get(form.getChildren().size() - 1), 3);
        form.addRow(2, new Label("Horizons:"), horizonBoxes);
        form.addRow(3, new Label("Threshold:"), new HBox(6, threshold, new Label("°C")));

        page.getChildren().addAll(form, new HBox(8, run, working, inputProblem), results);
        validate();
        Async.run(client::datasets, list -> dataset.getItems().setAll(
                list.stream().filter(d -> "READY".equals(d.status())).toList()), Errors::show);
    }

    Node node() {
        return page;
    }

    /** Problems with the form, or {@code null} when it can be sent. */
    static String problem(boolean hasSensor, List<Integer> horizons, String threshold, boolean useLatest,
                          String origin) {
        if (!hasSensor) {
            return "Choose a dataset and sensor.";
        }
        if (horizons.isEmpty()) {
            return "Choose at least one horizon.";
        }
        try {
            double value = Double.parseDouble(threshold.trim());
            if (!Double.isFinite(value) || value < -50 || value > 60) {
                return "The threshold must be between -50 and 60 °C.";
            }
        } catch (NumberFormatException e) {
            return "The threshold must be a number.";
        }
        if (!useLatest) {
            try {
                LocalDateTime.parse(origin.trim(), INPUT_TIME);
            } catch (DateTimeParseException e) {
                return "Enter the forecast time as yyyy-MM-dd HH:mm.";
            }
        }
        return null;
    }

    private List<Integer> selectedHorizons() {
        List<Integer> selected = new ArrayList<>();
        for (int h : List.of(15, 30, 60)) {
            if (horizons.get(h).isSelected()) {
                selected.add(h);
            }
        }
        return selected;
    }

    private void validate() {
        String problem = problem(sensor.getValue() != null, selectedHorizons(), threshold.getText(),
                latest.isSelected(), origin.getText());
        inputProblem.setText(problem == null ? "" : problem);
        run.setDisable(problem != null);
    }

    private void runForecast() {
        String originText = latest.isSelected() ? null
                : LocalDateTime.parse(origin.getText().trim(), INPUT_TIME).withSecond(0).toString() + ":00";
        ApiModels.ForecastRequest request = new ApiModels.ForecastRequest(dataset.getValue().id(), sensor.getValue(),
                originText, selectedHorizons(), Double.parseDouble(threshold.getText().trim()));
        results.getChildren().clear();
        run.setDisable(true);
        working.setVisible(true);
        Async.run(() -> client.forecast(request), result -> {
            working.setVisible(false);
            validate();
            showResult(result);
        }, error -> {
            working.setVisible(false);
            validate();
            Errors.show(error);
        });
    }

    private void showResult(ApiModels.ForecastResult r) {
        Label banner = Views.wrapped(prefix(r.warning().level()) + r.warning().message());
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setStyle("-fx-padding: 8; -fx-font-weight: bold; -fx-background-color: " + color(r.warning().level())
                + ";");

        Label current = new Label("Current reading (" + Views.naive(r.originTimestamp()) + "): "
                + Views.celsius(r.currentTemperatureC())
                + (r.currentlyAboveThreshold() ? " (at or above threshold)" : " (below threshold)"));

        Map<String, Double> actual = new HashMap<>();
        r.observedAfterOrigin().forEach(o -> actual.put(o.timestamp(), o.temperatureC()));
        boolean anyActual = actual.values().stream().anyMatch(v -> v != null);
        TableView<ApiModels.Forecast> table = new TableView<>();
        table.getColumns().add(column("Horizon", f -> f.horizonMinutes() + " min"));
        table.getColumns().add(column("Time", f -> Views.naive(f.targetTimestamp())));
        table.getColumns().add(column("Forecast", f -> Views.celsius(f.predictedTemperatureC())));
        table.getColumns().add(column("Peak estimate", f -> Views.celsius(f.predictedPeakTemperatureC())));
        table.getColumns().add(column("Crosses?", f -> f.predictedThresholdCrossing() ? "Yes" : "No"));
        if (anyActual) {
            table.getColumns().add(column("Actual", f -> Views.celsius(actual.get(f.targetTimestamp()))));
        }
        table.getColumns().get(1).setPrefWidth(160);
        table.getItems().setAll(r.forecasts());
        table.setFixedCellSize(28);
        table.setPrefHeight(28 * (r.forecasts().size() + 1) + 4);

        Label summary = new Label("Predicted minimum: " + Views.celsius(r.predictedMinTemperatureC())
                + "    Predicted maximum: " + Views.celsius(r.predictedMaxTemperatureC()));
        VBox limitations = new VBox(2);
        r.limitations().forEach(text -> limitations.getChildren().add(Views.wrapped(text)));
        Label saved = new Label(String.format(Locale.ROOT, "Saved to Prediction History.  Model: %s (%s)  •  %d ms",
                r.model().name(), r.model().version(), r.durationMs()));
        saved.setStyle("-fx-text-fill: #555555;");
        results.getChildren().setAll(banner, current, table, summary, limitations, saved);
    }

    private static TableColumn<ApiModels.Forecast, String> column(String title,
                                                                  Function<ApiModels.Forecast, String> value) {
        TableColumn<ApiModels.Forecast, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(120);
        return column;
    }

    /** Text prefix so the meaning does not depend on color alone. */
    static String prefix(String level) {
        return switch (level) {
            case "CURRENT_VIOLATION", "IMMINENT" -> "!! ";
            case "WARNING", "ADVISORY" -> "! ";
            default -> "";
        };
    }

    private static String color(String level) {
        return switch (level) {
            case "CURRENT_VIOLATION", "IMMINENT" -> "#f8d7da";
            case "WARNING" -> "#ffe5b4";
            case "ADVISORY" -> "#fff3c4";
            default -> "#e9ecef";
        };
    }
}

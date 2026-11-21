package edu.northeastern.cs5010.desktop.ui;

import edu.northeastern.cs5010.desktop.client.ApiModels;
import edu.northeastern.cs5010.desktop.client.BackendClient;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Function;

/** Lists saved predictions and opens the original forecast result. */
final class PredictionHistoryView {
    private static final String FORECAST = "TEMPERATURE_FORECAST";

    private final BackendClient client;
    private final VBox page = Views.page("Prediction History");
    private final ComboBox<String> type = new ComboBox<>();
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final HBox filters = new HBox(10);
    private final TableView<ApiModels.PredictionSummary> table = new TableView<>();
    private final Button previous = new Button("Previous");
    private final Button next = new Button("Next");
    private final Label status = new Label();
    private ApiModels.PredictionPage current;
    private Filters applied;

    PredictionHistoryView(BackendClient client) {
        this.client = client;
        type.getItems().addAll("All", typeLabel(FORECAST));
        type.getSelectionModel().selectFirst();
        from.setEditable(false);
        to.setEditable(false);
        Button apply = new Button("Apply");
        apply.setOnAction(e -> applyFilters());
        Button clear = new Button("Clear filters");
        clear.setOnAction(e -> {
            type.getSelectionModel().selectFirst();
            from.setValue(null);
            to.setValue(null);
            applyFilters();
        });
        filters.setAlignment(Pos.BOTTOM_LEFT);
        filters.getChildren().addAll(field("Type", type), field("From (local date)", from),
                field("To (local date)", to), apply, clear);

        table.setPlaceholder(new Label("No saved predictions match these filters."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("Time (local)", 150, row -> Views.local(row.createdAt()));
        column("Type", 145, row -> typeLabel(row.type()));
        column("Dataset / Product", 160, PredictionHistoryView::subject);
        column("Result", 255, ApiModels.PredictionSummary::headline);
        table.setRowFactory(view -> {
            TableRow<ApiModels.PredictionSummary> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    open(row.getItem());
                }
            });
            return row;
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        Button open = new Button("Open prediction");
        open.disableProperty().bind(table.disableProperty().or(table.getSelectionModel().selectedItemProperty().isNull()));
        open.setOnAction(e -> open(table.getSelectionModel().getSelectedItem()));
        previous.setOnAction(e -> load(applied, current.page() - 1));
        next.setOnAction(e -> load(applied, current.page() + 1));
        HBox actions = new HBox(10, open, previous, next, status);
        actions.setAlignment(Pos.CENTER_LEFT);
        page.getChildren().addAll(filters, table, actions);
        applyFilters();
    }

    Node node() {
        return page;
    }

    private void applyFilters() {
        LocalDate start = from.getValue();
        LocalDate end = to.getValue();
        if (start != null && end != null && start.isAfter(end)) {
            status.setText("From date must precede or equal To date.");
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        load(new Filters(type.getSelectionModel().getSelectedIndex() == 0 ? null : FORECAST,
                start == null ? null : start.atStartOfDay(zone).toInstant(),
                end == null ? null : end.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)), 0);
    }

    private void load(Filters query, int pageNumber) {
        setLoading(true, "Loading predictions…");
        Async.run(() -> client.predictionHistory(query.type(), query.from(), query.to(), pageNumber), result -> {
            applied = query;
            current = result;
            table.getItems().setAll(result.items());
            setLoading(false, pageStatus());
        }, error -> {
            setLoading(false, "Could not load predictions.");
            Errors.show(error);
        });
    }

    private void open(ApiModels.PredictionSummary row) {
        if (!FORECAST.equals(row.type())) {
            status.setText("The desktop viewer is available for temperature forecasts.");
            return;
        }
        setLoading(true, "Loading saved forecast…");
        Async.run(() -> client.savedForecast(row.id()), result -> {
            setLoading(false, pageStatus());
            if (page.getScene() != null) {
                showResult(row, result);
            }
        }, error -> {
            setLoading(false, "Could not open the saved forecast.");
            Errors.show(error);
        });
    }

    private void showResult(ApiModels.PredictionSummary row, ApiModels.ForecastResult result) {
        VBox content = Views.page("Saved prediction from " + Views.local(result.createdAt()));
        content.getChildren().addAll(Views.wrapped(subject(row) + " · " + result.sensorId()),
                ForecastView.resultContent(result));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(690);
        scroll.setPrefViewportHeight(390);
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(page.getScene().getWindow());
        dialog.setTitle("Saved temperature forecast");
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    private void setLoading(boolean loading, String message) {
        filters.setDisable(loading);
        table.setDisable(loading);
        previous.setDisable(loading || current == null || current.page() == 0);
        next.setDisable(loading || current == null || (current.page() + 1L) * current.size() >= current.totalItems());
        status.setText(message);
    }

    private String pageStatus() {
        return Views.count(current.totalItems()) + " saved predictions · Page " + (current.page() + 1L);
    }

    private void column(String title, double width, Function<ApiModels.PredictionSummary, String> value) {
        TableColumn<ApiModels.PredictionSummary, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    private static VBox field(String title, Node control) {
        Label label = new Label(title);
        label.setLabelFor(control);
        return new VBox(3, label, control);
    }

    private static String typeLabel(String code) {
        return switch (code) {
            case FORECAST -> "Temperature forecast";
            case "SHELF_LIFE" -> "Shelf life";
            default -> code;
        };
    }

    private static String subject(ApiModels.PredictionSummary row) {
        if (row.productId() != null) {
            return row.productName() == null ? row.productId() : row.productName();
        }
        return row.datasetName() == null ? row.datasetId() : row.datasetName();
    }

    private record Filters(String type, Instant from, Instant to) {
    }
}

package edu.northeastern.cs5010.desktop.ui;

import edu.northeastern.cs5010.desktop.client.ApiModels;
import edu.northeastern.cs5010.desktop.client.BackendClient;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Function;

/** Read-only, paged audit trail from the Java backend. */
final class AuditView {
    private static final String ALL = "All";

    private final BackendClient client;
    private final VBox root = Views.page("Audit Log");
    private final ComboBox<String> category = filter("SYSTEM", "DATASET", "PRODUCT", "PREDICTION",
            "WARNING", "ACCESS", "ML");
    private final ComboBox<String> event = filter();
    private final ComboBox<String> outcome = filter("SUCCESS", "FAILURE");
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final GridPane filters = new GridPane();
    private final TableView<ApiModels.AuditEntry> table = new TableView<>();
    private final Button previous = new Button("Previous");
    private final Button next = new Button("Next");
    private final Label status = new Label();
    private ApiModels.AuditPage current;
    private Filters applied;

    AuditView(BackendClient client) {
        this.client = client;
        Button apply = new Button("Apply");
        apply.setOnAction(e -> apply());
        Button clear = new Button("Clear filters");
        clear.setOnAction(e -> {
            category.getSelectionModel().selectFirst();
            event.getSelectionModel().selectFirst();
            outcome.getSelectionModel().selectFirst();
            from.setValue(null);
            to.setValue(null);
            apply();
        });
        filters.setHgap(12);
        filters.setVgap(6);
        filters.addRow(0, field("Category", category), field("Event", event), field("Outcome", outcome));
        filters.addRow(1, field("From (local date)", from), field("To (local date)", to),
                new VBox(4, apply, clear));
        from.setEditable(false);
        to.setEditable(false);
        category.setOnAction(e -> {
            event.getItems().setAll(ALL);
            event.getSelectionModel().selectFirst();
        });

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("Time (local)", 156, row -> Views.local(row.timestamp()));
        column("Event", 225, ApiModels.AuditEntry::eventType);
        column("Outcome", 82, ApiModels.AuditEntry::outcome);
        column("Source", 164, ApiModels.AuditEntry::source);
        table.setPlaceholder(new Label("No matching audit events."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(5);
        detail.setPromptText("Select an event to view its details.");
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) ->
                detail.setText(row == null ? "" : describe(row)));
        previous.setOnAction(e -> load(applied, current.page() - 1));
        next.setOnAction(e -> load(applied, current.page() + 1));
        root.getChildren().addAll(filters, table, new HBox(10, previous, next, status),
                field("Selected event", detail));
        apply();
    }

    Node node() {
        return root;
    }

    private void apply() {
        LocalDate start = from.getValue();
        LocalDate end = to.getValue();
        if (start != null && end != null && start.isAfter(end)) {
            status.setText("From date must precede or equal To date.");
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        load(new Filters(selected(category), selected(event), selected(outcome),
                start == null ? null : start.atStartOfDay(zone).toInstant(),
                end == null ? null : end.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)), 0);
    }

    private void load(Filters query, int page) {
        filters.setDisable(true);
        previous.setDisable(true);
        next.setDisable(true);
        status.setText("Loading audit events…");
        Async.run(() -> client.auditLogs(query.category(), query.event(), query.outcome(),
                query.from(), query.to(), page), result -> {
            applied = query;
            current = result;
            event.getItems().setAll(ALL);
            event.getItems().addAll(result.eventTypes());
            event.getSelectionModel().select(query.event() == null ? ALL : query.event());
            table.getItems().setAll(result.items());
            status.setText(Views.count(result.total()) + " events · Page " + (result.page() + 1));
            finishLoading();
        }, error -> {
            status.setText("Could not load audit events.");
            finishLoading();
            Errors.show(error);
        });
    }

    private void finishLoading() {
        filters.setDisable(false);
        previous.setDisable(current == null || current.page() == 0);
        next.setDisable(current == null || (current.page() + 1L) * current.size() >= current.total());
    }

    private void column(String title, double width, Function<ApiModels.AuditEntry, String> value) {
        TableColumn<ApiModels.AuditEntry, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }

    private static ComboBox<String> filter(String... options) {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().add(ALL);
        box.getItems().addAll(options);
        box.getSelectionModel().selectFirst();
        return box;
    }

    private static VBox field(String title, Node control) {
        Label label = new Label(title);
        label.setLabelFor(control);
        return new VBox(3, label, control);
    }

    private static String selected(ComboBox<String> box) {
        return ALL.equals(box.getValue()) ? null : box.getValue();
    }

    private static String describe(ApiModels.AuditEntry row) {
        StringBuilder text = new StringBuilder();
        line(text, "Audit ID", row.id());
        line(text, "Category", row.category());
        line(text, "Actor", row.actor());
        line(text, "Host", row.host());
        if (row.refs() != null) {
            line(text, "Dataset ID", row.refs().datasetId());
            line(text, "Product ID", row.refs().productId());
            line(text, "Prediction ID", row.refs().predictionId());
        }
        if (row.error() != null) {
            line(text, row.error().code(), row.error().message());
        }
        if (row.details() != null) {
            row.details().forEach((name, value) -> line(text, name, value));
        }
        return text.toString();
    }

    private static void line(StringBuilder text, String name, Object value) {
        if (value != null) {
            text.append(name).append(": ").append(value).append('\n');
        }
    }

    private record Filters(String category, String event, String outcome, Instant from, Instant to) {
    }
}

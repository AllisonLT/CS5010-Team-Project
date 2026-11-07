package edu.northeastern.cs5010.desktop.ui;

import edu.northeastern.cs5010.desktop.client.ApiModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Screen rules that do not need a running JavaFX toolkit. */
class FormRulesTest {
    @Test
    void forecastFormChecksEveryInput() {
        assertThat(ForecastView.problem(false, List.of(15), "10", true, "")).isEqualTo("Choose a dataset and sensor.");
        assertThat(ForecastView.problem(true, List.of(), "10", true, "")).isEqualTo("Choose at least one horizon.");
        assertThat(ForecastView.problem(true, List.of(15), "abc", true, "")).isEqualTo("The threshold must be a number.");
        assertThat(ForecastView.problem(true, List.of(15), "99", true, ""))
                .isEqualTo("The threshold must be between -50 and 60 °C.");
        assertThat(ForecastView.problem(true, List.of(15), "10", false, "2024-09-09T14:00"))
                .isEqualTo("Enter the forecast time as yyyy-MM-dd HH:mm.");
        assertThat(ForecastView.problem(true, List.of(15, 60), " 10.5 ", false, "2024-09-09 14:00")).isNull();
        assertThat(ForecastView.problem(true, List.of(30), "-4", true, "")).isNull();
    }

    @Test
    void warningPrefixDoesNotRelyOnColor() {
        assertThat(ForecastView.prefix("IMMINENT")).isEqualTo("!! ");
        assertThat(ForecastView.prefix("ADVISORY")).isEqualTo("! ");
        assertThat(ForecastView.prefix("NONE")).isEmpty();
    }

    @Test
    void errorRowsAreDescribedForTheDialog() {
        assertThat(Errors.describe(new ApiModels.FieldError("Date", 2, "'2024-09-02' is not dd.mm.yyyy")))
                .isEqualTo("Line 2, Date: '2024-09-02' is not dd.mm.yyyy");
        assertThat(Errors.describe(new ApiModels.FieldError("file", null, "The file is empty")))
                .isEqualTo("The file is empty");
    }

    @Test
    void datasetStatusLabels() {
        assertThat(DatasetsView.statusText("MISSING_FILE")).isEqualTo("File missing");
        assertThat(DatasetsView.statusText("READY")).isEqualTo("Ready");
    }
}

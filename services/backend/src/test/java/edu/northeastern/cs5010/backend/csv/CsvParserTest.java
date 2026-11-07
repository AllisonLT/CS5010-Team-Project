package edu.northeastern.cs5010.backend.csv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvParserTest {
    private final CsvParser parser = new CsvParser();

    private ParsedCsv parse(String content, String filename) throws IOException {
        return parser.parse(new StringReader(content), filename, null);
    }

    private CsvRejectedException rejected(String content, String filename) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> parse(content, filename));
        assertThat(thrown).isInstanceOf(CsvRejectedException.class);
        return (CsvRejectedException) thrown;
    }

    @Test
    void parsesZenodoSemicolonLayoutAndTakesSensorFromFilename() throws IOException {
        ParsedCsv parsed = parse("""
                Date;Time;Temperature;Humidity
                02.09.2024;09:35:25;22.1;63.4
                02.09.2024;09:35:30;22.0;63.2
                """, "SENSOR02_preprocessed.csv");

        assertThat(parsed.format()).isEqualTo(CsvFormat.SENSOR_SEMICOLON);
        assertThat(parsed.sensorIds()).containsExactly("SENSOR02");
        assertThat(parsed.columns()).containsExactly("Date", "Time", "Temperature", "Humidity");
        assertThat(parsed.recordCount()).isEqualTo(2);
        assertThat(parsed.acceptedRecordCount()).isEqualTo(2);
        assertThat(parsed.start()).isEqualTo(LocalDateTime.of(2024, 9, 2, 9, 35, 25));
        assertThat(parsed.end()).isEqualTo(LocalDateTime.of(2024, 9, 2, 9, 35, 30));
        assertThat(parsed.readings().get(0)).isEqualTo(
                new SensorReading("SENSOR02", LocalDateTime.of(2024, 9, 2, 9, 35, 25), 22.1, 63.4));
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void parsesCanonicalLayoutWithSeveralSensors() throws IOException {
        ParsedCsv parsed = parse("""
                timestamp,sensor_id,temperature_c,humidity
                2024-09-02T09:36:00,A,5.0,70
                2024-09-02T09:36:00,B,6.0,71
                2024-09-02 09:37:00,A,5.5,70
                """, "upload.csv");

        assertThat(parsed.format()).isEqualTo(CsvFormat.CANONICAL);
        assertThat(parsed.sensorIds()).containsExactly("A", "B");
        assertThat(parsed.readings()).extracting(SensorReading::sensorId).containsExactly("A", "A", "B");
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void headerIsCaseInsensitiveAndByteOrderMarkIsIgnored() throws IOException {
        ParsedCsv parsed = parse("﻿ date ; TIME ; temperature\n02.09.2024;09:35:25;7.5\n", "sensor_4.csv");

        assertThat(parsed.sensorIds()).containsExactly("SENSOR04");
        assertThat(parsed.readings().get(0).humidity()).isNull();
    }

    @Test
    void userSensorIdWinsOverFilename() throws IOException {
        ParsedCsv parsed = parser.parse(new StringReader("Date;Time;Temperature\n02.09.2024;09:35:25;7.5\n"),
                "SENSOR02.csv", "COLD_ROOM_A");

        assertThat(parsed.sensorIds()).containsExactly("COLD_ROOM_A");
    }

    @Test
    void missingAndImplausibleValuesBecomeMissingReadingsWithWarnings() throws IOException {
        ParsedCsv parsed = parse("""
                Date;Time;Temperature;Humidity
                02.09.2024;09:35:25;nan;63.4
                02.09.2024;09:35:30;;63.4
                02.09.2024;09:35:35;95.0;63.4
                02.09.2024;09:35:40;abc;63.4
                02.09.2024;09:35:45;7.0;120
                """, "SENSOR01.csv");

        assertThat(parsed.recordCount()).isEqualTo(5);
        assertThat(parsed.acceptedRecordCount()).isEqualTo(1);
        assertThat(parsed.readings()).extracting(SensorReading::temperatureC)
                .containsExactly(null, null, null, null, 7.0);
        assertThat(parsed.readings().get(4).humidity()).isNull();
        assertThat(parsed.warnings()).extracting(CsvWarning::code)
                .containsExactly("MISSING_TEMPERATURE", "TEMPERATURE_OUT_OF_RANGE", "HUMIDITY_OUT_OF_RANGE");
        assertThat(parsed.warnings().get(0).rowNumbers()).containsExactly(2, 3, 5);
        assertThat(parsed.warnings().get(1).rowNumbers()).containsExactly(4);
    }

    @Test
    void repeatedHeadersAreSkippedAndUnsortedRowsAreSorted() throws IOException {
        ParsedCsv parsed = parse("""
                Date;Time;Temperature;Humidity
                02.09.2024;09:35:30;7.0;60
                Date;Time;Temperature;Humidity
                02.09.2024;09:35:25;6.0;60
                """, "SENSOR01.csv");

        assertThat(parsed.recordCount()).isEqualTo(2);
        assertThat(parsed.readings()).extracting(SensorReading::temperatureC).containsExactly(6.0, 7.0);
        assertThat(parsed.warnings()).extracting(CsvWarning::code).containsExactly("REPEATED_HEADER", "ROWS_SORTED");
    }

    @Test
    void rejectsEmptyFile() {
        assertThat(rejected("", "SENSOR01.csv").getMessage()).isEqualTo("The file is empty.");
        assertThat(rejected("\n  \n", "SENSOR01.csv").getMessage()).isEqualTo("The file is empty.");
    }

    @Test
    void rejectsHeaderWithoutRows() {
        assertThat(rejected("Date;Time;Temperature\n", "SENSOR01.csv").getMessage())
                .isEqualTo("The file has a header but no data rows.");
    }

    @Test
    void rejectsMissingTemperatureColumn() {
        CsvRejectedException e = rejected("Date;Time;Humidity\n02.09.2024;09:35:25;60\n", "SENSOR01.csv");

        assertThat(e.getMessage()).isEqualTo("Missing required column 'Temperature'.");
    }

    @Test
    void rejectsMissingTimeColumns() {
        assertThat(rejected("when;Temperature\nx;5\n", "SENSOR01.csv").getMessage())
                .startsWith("Missing time columns");
    }

    @Test
    void rejectsMalformedTimestampsAndListsTheRows() {
        CsvRejectedException e = rejected("""
                Date;Time;Temperature
                2024-09-02;09:35:25;5
                02.09.2024;09:35:30;5
                31.02.2024;09:35:35;5
                """, "SENSOR01.csv");

        assertThat(e.getMessage()).startsWith("2 rows have problems. First problem: line 2:");
        assertThat(e.issues()).extracting(CsvIssue::row).containsExactly(2, 4);
        assertThat(e.issues().get(0).field()).isEqualTo("Date");
    }

    @Test
    void rejectsTimeZoneOffsets() {
        CsvRejectedException e = rejected("timestamp,temperature\n2024-09-02T09:35:25Z,5\n", "SENSOR01.csv");

        assertThat(e.issues().get(0).message()).contains("has a time zone");
    }

    @Test
    void rejectsShortRows() {
        CsvRejectedException e = rejected("Date;Time;Temperature\n02.09.2024;09:35:25\n", "SENSOR01.csv");

        assertThat(e.issues().get(0).message()).isEqualTo("Expected 3 columns but found 2");
    }

    @Test
    void rejectsDuplicateTimestampsForTheSameSensor() {
        CsvRejectedException e = rejected("""
                Date;Time;Temperature
                02.09.2024;09:35:25;5
                02.09.2024;09:35:25;6
                """, "SENSOR01.csv");

        assertThat(e.getMessage()).startsWith("1 duplicate timestamp found");
        assertThat(e.issues().get(0).row()).isEqualTo(3);
        assertThat(e.issues().get(0).message()).contains("also on line 2");
    }

    @Test
    void sameTimestampForDifferentSensorsIsAllowed() throws IOException {
        ParsedCsv parsed = parse("timestamp,sensor_id,temperature\n2024-09-02T09:35:25,A,5\n2024-09-02T09:35:25,B,6\n",
                "x.csv");

        assertThat(parsed.recordCount()).isEqualTo(2);
    }

    @Test
    void rejectsFileWhereNoTemperatureIsValid() {
        assertThat(rejected("Date;Time;Temperature\n02.09.2024;09:35:25;nan\n", "SENSOR01.csv").getMessage())
                .startsWith("No row has a valid temperature");
    }

    @Test
    void requiresSensorIdWhenItCannotBeFound() {
        assertThat(rejected("Date;Time;Temperature\n02.09.2024;09:35:25;5\n", "readings.csv").getMessage())
                .startsWith("Enter a sensor id");
        assertThatThrownBy(() -> parser.parse(new StringReader("Date;Time;Temperature\n02.09.2024;09:35:25;5\n"),
                "readings.csv", "bad id!"))
                .isInstanceOf(CsvRejectedException.class);
    }

    @Test
    void listsAtMostTwentyProblemRows() {
        StringBuilder csv = new StringBuilder("Date;Time;Temperature\n");
        for (int i = 0; i < 30; i++) {
            csv.append("bad;09:35:25;5\n");
        }
        CsvRejectedException e = rejected(csv.toString(), "SENSOR01.csv");

        assertThat(e.getMessage()).startsWith("30 rows have problems");
        assertThat(e.issues()).hasSize(CsvParser.MAX_LISTED_ROWS);
    }

    @Test
    void sensorIdFromFilenameMatchesMlPreprocessing() {
        assertThat(List.of("SENSOR02_preprocessed.csv", "sensor-7.csv", "Sensor 11.csv", "nothing.csv"))
                .map(CsvParser::sensorIdFromFilename)
                .containsExactly("SENSOR02", "SENSOR07", "SENSOR11", null);
    }
}

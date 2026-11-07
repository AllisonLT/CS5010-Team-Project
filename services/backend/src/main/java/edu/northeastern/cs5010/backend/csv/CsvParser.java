package edu.northeastern.cs5010.backend.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates temperature CSV uploads.
 *
 * <p>The rules match the ML preprocessing ({@code load_sensor_csv} in {@code cold_storage_ml.preprocessing})
 * so that the backend and the models read the same file the same way:
 * <ul>
 *   <li>Column names are trimmed and case-insensitive; the separator is {@code ;} or {@code ,}.</li>
 *   <li>Timestamps are {@code Date} + {@code Time} as {@code dd.MM.yyyy HH:mm:ss}, or an ISO local
 *       {@code timestamp}. An unreadable timestamp, a time zone offset, or a duplicate timestamp for the
 *       same sensor rejects the whole file.</li>
 *   <li>A temperature that is blank, not a number, or outside -40..80 °C becomes a missing reading.
 *       A humidity outside 0..100 % becomes missing. Missing values are never filled in.</li>
 *   <li>Rows out of time order are sorted. Repeated header lines are skipped.</li>
 * </ul>
 * Quoted fields containing the separator are not supported.
 */
public class CsvParser {
    public static final int MAX_LISTED_ROWS = 20;
    public static final double MIN_TEMPERATURE_C = -40.0;
    public static final double MAX_TEMPERATURE_C = 80.0;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("dd.MM.uuuu HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern SENSOR_IN_NAME = Pattern.compile("sensor[_\\- ]?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSOR_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern OFFSET = Pattern.compile("(Z|[+-]\\d{2}:?\\d{2})$");

    /**
     * @param filename         original file name, used to find a {@code SENSORnn} id
     * @param sensorIdOverride sensor id typed by the user; wins over the file name
     */
    public ParsedCsv parse(Reader source, String filename, String sensorIdOverride) throws IOException {
        List<String> lines = readLines(source);
        int headerIndex = firstNonBlank(lines);
        if (headerIndex < 0) {
            throw reject("The file is empty.", fileIssue("The file is empty"));
        }
        String headerLine = lines.get(headerIndex);
        char separator = detectSeparator(headerLine);
        List<String> columns = split(headerLine, separator);
        Layout layout = Layout.of(columns);
        String fixedSensorId = layout.sensorIdColumn < 0 ? resolveSensorId(filename, sensorIdOverride) : null;

        List<SensorReading> readings = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        List<CsvIssue> errors = new ArrayList<>();
        int errorCount = 0;
        Warnings warnings = new Warnings();
        String normalizedHeader = normalizeHeader(headerLine);

        for (int i = headerIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNumber = i + 1;
            if (line.isBlank()) {
                continue;
            }
            if (normalizeHeader(line).equals(normalizedHeader)) {
                warnings.add("REPEATED_HEADER", lineNumber);
                continue;
            }
            List<String> fields = split(line, separator);
            if (fields.size() < layout.requiredFieldCount) {
                errorCount++;
                addIssue(errors, new CsvIssue("file", lineNumber,
                        "Expected " + columns.size() + " columns but found " + fields.size()));
                continue;
            }
            String sensorId = fixedSensorId;
            if (sensorId == null) {
                sensorId = fields.get(layout.sensorIdColumn);
                if (!SENSOR_ID.matcher(sensorId).matches()) {
                    errorCount++;
                    addIssue(errors, new CsvIssue(columns.get(layout.sensorIdColumn), lineNumber,
                            "'" + sensorId + "' is not a valid sensor id (letters, digits, '_' or '-')"));
                    continue;
                }
            }
            LocalDateTime timestamp;
            try {
                timestamp = layout.timestamp(fields);
            } catch (TimestampException e) {
                errorCount++;
                addIssue(errors, new CsvIssue(e.field, lineNumber, e.getMessage()));
                continue;
            }
            Double temperature = parseNumber(fields.get(layout.temperatureColumn));
            if (temperature == null) {
                warnings.add("MISSING_TEMPERATURE", lineNumber);
            } else if (temperature < MIN_TEMPERATURE_C || temperature > MAX_TEMPERATURE_C) {
                warnings.add("TEMPERATURE_OUT_OF_RANGE", lineNumber);
                temperature = null;
            }
            Double humidity = null;
            if (layout.humidityColumn >= 0 && layout.humidityColumn < fields.size()) {
                humidity = parseNumber(fields.get(layout.humidityColumn));
                if (humidity != null && (humidity < 0 || humidity > 100)) {
                    warnings.add("HUMIDITY_OUT_OF_RANGE", lineNumber);
                    humidity = null;
                }
            }
            readings.add(new SensorReading(sensorId, timestamp, temperature, humidity));
            lineNumbers.add(lineNumber);
        }

        if (errorCount > 0) {
            throw reject(errorCount + (errorCount == 1 ? " row has a problem" : " rows have problems")
                    + ". First problem: line " + errors.get(0).row() + ": " + errors.get(0).message() + ".", errors);
        }
        if (readings.isEmpty()) {
            throw reject("The file has a header but no data rows.", fileIssue("No data rows"));
        }

        if (!inTimeOrderPerSensor(readings)) {
            warnings.add("ROWS_SORTED", null);
        }
        List<Integer> order = sortedOrder(readings);
        List<SensorReading> sorted = new ArrayList<>(readings.size());
        List<Integer> sortedLines = new ArrayList<>(readings.size());
        for (int index : order) {
            sorted.add(readings.get(index));
            sortedLines.add(lineNumbers.get(index));
        }
        checkDuplicates(sorted, sortedLines, columns, layout);

        int accepted = (int) sorted.stream().filter(r -> r.temperatureC() != null).count();
        if (accepted == 0) {
            throw reject("No row has a valid temperature between -40 and 80 °C.",
                    fileIssue("No valid temperature readings"));
        }
        Set<String> sensors = new LinkedHashSet<>();
        sorted.forEach(r -> sensors.add(r.sensorId()));
        LocalDateTime start = sorted.stream().map(SensorReading::timestamp).min(Comparator.naturalOrder()).orElseThrow();
        LocalDateTime end = sorted.stream().map(SensorReading::timestamp).max(Comparator.naturalOrder()).orElseThrow();
        return new ParsedCsv(layout.format, columns, List.copyOf(sensors), sorted.size(), accepted,
                start, end, List.copyOf(sorted), warnings.toList());
    }

    /** {@code SENSOR02} for names like {@code SENSOR02_preprocessed.csv} or {@code sensor-2.csv}. */
    public static String sensorIdFromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        Matcher matcher = SENSOR_IN_NAME.matcher(filename);
        return matcher.find() ? String.format(Locale.ROOT, "SENSOR%02d", Integer.parseInt(matcher.group(1))) : null;
    }

    private static String resolveSensorId(String filename, String override) {
        if (override != null && !override.isBlank()) {
            String trimmed = override.trim();
            if (!SENSOR_ID.matcher(trimmed).matches()) {
                throw reject("The sensor id may only contain letters, digits, '_' or '-' (at most 40).",
                        new CsvIssue("sensorId", null, "Invalid sensor id"));
            }
            return trimmed;
        }
        String fromName = sensorIdFromFilename(filename);
        if (fromName == null) {
            throw reject("Enter a sensor id: the file has no sensor_id column and its name has no SENSORnn pattern.",
                    new CsvIssue("sensorId", null, "Sensor id required"));
        }
        return fromName;
    }

    private static void checkDuplicates(List<SensorReading> sorted, List<Integer> lines,
                                        List<String> columns, Layout layout) {
        List<CsvIssue> duplicates = new ArrayList<>();
        int count = 0;
        int runStartLine = lines.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            SensorReading previous = sorted.get(i - 1);
            SensorReading current = sorted.get(i);
            if (previous.sensorId().equals(current.sensorId()) && previous.timestamp().equals(current.timestamp())) {
                count++;
                addIssue(duplicates, new CsvIssue(layout.timestampField(columns), lines.get(i),
                        "Duplicate timestamp " + current.timestamp() + " for " + current.sensorId()
                                + " (also on line " + runStartLine + ")"));
            } else {
                runStartLine = lines.get(i);
            }
        }
        if (count > 0) {
            throw reject(count + " duplicate timestamp" + (count == 1 ? "" : "s")
                    + " found. Each sensor may have only one reading per timestamp.", duplicates);
        }
    }

    private static List<Integer> sortedOrder(List<SensorReading> readings) {
        List<Integer> order = new ArrayList<>(readings.size());
        for (int i = 0; i < readings.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, String>comparing(i -> readings.get(i).sensorId())
                .thenComparing(i -> readings.get(i).timestamp()));
        return order;
    }

    private static boolean inTimeOrderPerSensor(List<SensorReading> readings) {
        Map<String, LocalDateTime> last = new HashMap<>();
        for (SensorReading reading : readings) {
            LocalDateTime previous = last.put(reading.sensorId(), reading.timestamp());
            if (previous != null && reading.timestamp().isBefore(previous)) {
                return false;
            }
        }
        return true;
    }

    static Double parseNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> readLines(Reader source) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(lines.isEmpty() && line.startsWith("﻿") ? line.substring(1) : line);
            }
        }
        return lines;
    }

    private static int firstNonBlank(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static char detectSeparator(String header) {
        if (header.indexOf(';') >= 0) {
            return ';';
        }
        if (header.indexOf(',') >= 0) {
            return ',';
        }
        throw reject("The header row must separate column names with ';' or ','.",
                fileIssue("No column separator in the header"));
    }

    private static List<String> split(String line, char separator) {
        List<String> fields = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == separator) {
                String field = line.substring(start, i).trim();
                if (field.length() >= 2 && field.startsWith("\"") && field.endsWith("\"")) {
                    field = field.substring(1, field.length() - 1).trim();
                }
                fields.add(field);
                start = i + 1;
            }
        }
        return fields;
    }

    private static String normalizeHeader(String line) {
        return line.replace("\"", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static void addIssue(List<CsvIssue> issues, CsvIssue issue) {
        if (issues.size() < MAX_LISTED_ROWS) {
            issues.add(issue);
        }
    }

    private static CsvIssue fileIssue(String message) {
        return new CsvIssue("file", null, message);
    }

    private static CsvRejectedException reject(String message, CsvIssue issue) {
        return new CsvRejectedException(message, List.of(issue));
    }

    private static CsvRejectedException reject(String message, List<CsvIssue> issues) {
        return new CsvRejectedException(message, issues);
    }

    /** Column positions for the two supported layouts. */
    private static final class Layout {
        final CsvFormat format;
        final int dateColumn;
        final int timeColumn;
        final int timestampColumn;
        final int temperatureColumn;
        final int humidityColumn;
        final int sensorIdColumn;
        final int requiredFieldCount;

        private Layout(CsvFormat format, int date, int time, int timestamp, int temperature, int humidity,
                       int sensorId) {
            this.format = format;
            this.dateColumn = date;
            this.timeColumn = time;
            this.timestampColumn = timestamp;
            this.temperatureColumn = temperature;
            this.humidityColumn = humidity;
            this.sensorIdColumn = sensorId;
            this.requiredFieldCount = 1 + Math.max(Math.max(Math.max(date, time), Math.max(timestamp, temperature)),
                    sensorId);
        }

        static Layout of(List<String> columns) {
            Map<String, Integer> index = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                index.putIfAbsent(columns.get(i).toLowerCase(Locale.ROOT), i);
            }
            int temperature = index.getOrDefault("temperature", index.getOrDefault("temperature_c", -1));
            if (temperature < 0) {
                throw reject("Missing required column 'Temperature'.",
                        new CsvIssue("file", 1, "Missing required column 'Temperature'"));
            }
            int humidity = index.getOrDefault("humidity", -1);
            int sensorId = index.getOrDefault("sensor_id", -1);
            if (index.containsKey("date") && index.containsKey("time")) {
                return new Layout(CsvFormat.SENSOR_SEMICOLON, index.get("date"), index.get("time"), -1,
                        temperature, humidity, sensorId);
            }
            if (index.containsKey("timestamp")) {
                return new Layout(CsvFormat.CANONICAL, -1, -1, index.get("timestamp"), temperature, humidity,
                        sensorId);
            }
            throw reject("Missing time columns: expected 'Date' and 'Time', or 'timestamp'.",
                    new CsvIssue("file", 1, "Missing required columns 'Date' and 'Time', or 'timestamp'"));
        }

        String timestampField(List<String> columns) {
            return timestampColumn >= 0 ? columns.get(timestampColumn) : columns.get(dateColumn);
        }

        LocalDateTime timestamp(List<String> fields) throws TimestampException {
            if (timestampColumn >= 0) {
                String text = fields.get(timestampColumn);
                if (OFFSET.matcher(text).find()) {
                    throw new TimestampException("timestamp",
                            "'" + text + "' has a time zone; use local time without an offset");
                }
                try {
                    return LocalDateTime.parse(text.replaceFirst(" ", "T"));
                } catch (DateTimeParseException e) {
                    throw new TimestampException("timestamp",
                            "'" + text + "' is not an ISO date-time such as 2024-09-02T09:35:25");
                }
            }
            String text = fields.get(dateColumn) + " " + fields.get(timeColumn);
            try {
                return LocalDateTime.parse(text, DATE_TIME);
            } catch (DateTimeParseException e) {
                throw new TimestampException("Date",
                        "'" + text + "' is not dd.mm.yyyy HH:mm:ss");
            }
        }
    }

    private static final class TimestampException extends Exception {
        final String field;

        TimestampException(String field, String message) {
            super(message);
            this.field = field;
        }
    }

    /** Collects warnings by code, keeping counts and example line numbers. */
    private static final class Warnings {
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        private final Map<String, List<Integer>> rows = new HashMap<>();

        void add(String code, Integer lineNumber) {
            counts.merge(code, 1, Integer::sum);
            List<Integer> list = rows.computeIfAbsent(code, k -> new ArrayList<>());
            if (lineNumber != null && list.size() < MAX_LISTED_ROWS) {
                list.add(lineNumber);
            }
        }

        List<CsvWarning> toList() {
            List<CsvWarning> result = new ArrayList<>();
            counts.forEach((code, count) -> result.add(new CsvWarning(code, message(code, count),
                    List.copyOf(rows.get(code)))));
            return result;
        }

        private static String message(String code, int count) {
            String rowsText = count == 1 ? "1 row" : count + " rows";
            return switch (code) {
                case "MISSING_TEMPERATURE" -> rowsText + " had no usable temperature and are kept as missing readings";
                case "TEMPERATURE_OUT_OF_RANGE" -> rowsText + " had a temperature outside -40 to 80 °C and are kept as missing readings";
                case "HUMIDITY_OUT_OF_RANGE" -> rowsText + " had a humidity outside 0 to 100 % and are kept as missing humidity";
                case "REPEATED_HEADER" -> count + " repeated header line" + (count == 1 ? " was" : "s were") + " skipped";
                case "ROWS_SORTED" -> "Rows were not in time order and have been sorted";
                default -> rowsText + ": " + code;
            };
        }
    }
}

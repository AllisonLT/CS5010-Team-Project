package edu.northeastern.cs5010.backend.csv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes {@code minute.csv}: {@code timestamp,sensor_id,temperature_c,humidity,reading_count}.
 * Missing values are empty fields.
 */
public final class MinuteCsv {
    public static final String HEADER = "timestamp,sensor_id,temperature_c,humidity,reading_count";
    public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private MinuteCsv() {
    }

    public static void write(Path file, List<MinuteReading> minutes) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            for (MinuteReading m : minutes) {
                writer.write(TIMESTAMP.format(m.timestamp()) + "," + m.sensorId() + "," + format(m.temperatureC()) + ","
                        + format(m.humidity()) + "," + m.readingCount());
                writer.newLine();
            }
        }
    }

    public static List<MinuteReading> read(Path file) throws IOException {
        List<MinuteReading> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IOException("Unexpected minute file header in " + file + ": " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(",", -1);
                result.add(new MinuteReading(f[1], LocalDateTime.parse(f[0]), parse(f[2]), parse(f[3]),
                        Integer.parseInt(f[4])));
            }
        }
        return result;
    }

    private static String format(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static Double parse(String text) {
        return text.isEmpty() ? null : Double.valueOf(text);
    }
}

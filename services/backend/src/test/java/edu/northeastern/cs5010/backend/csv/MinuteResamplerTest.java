package edu.northeastern.cs5010.backend.csv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MinuteResamplerTest {
    private static final Path PARITY = Path.of("../../docs/contracts/fixtures/minute_parity");

    private final MinuteResampler resampler = new MinuteResampler();

    @Test
    void matchesTheMlPreprocessingOnTheParityFixture() throws IOException {
        Path input = PARITY.resolve("SENSOR99_parity.csv");
        ParsedCsv parsed = new CsvParser().parse(Files.newBufferedReader(input, StandardCharsets.UTF_8),
                input.getFileName().toString(), null);

        List<MinuteReading> actual = resampler.resample(parsed.readings());
        List<MinuteReading> expected = MinuteCsv.read(PARITY.resolve("SENSOR99_parity.expected_minute.csv"));

        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            MinuteReading e = expected.get(i);
            MinuteReading a = actual.get(i);
            assertThat(a.timestamp()).as("minute %d", i).isEqualTo(e.timestamp());
            assertThat(a.sensorId()).isEqualTo(e.sensorId());
            assertThat(a.readingCount()).as("count at %s", e.timestamp()).isEqualTo(e.readingCount());
            assertSame(a.temperatureC(), e.temperatureC(), "temperature at " + e.timestamp());
            assertSame(a.humidity(), e.humidity(), "humidity at " + e.timestamp());
        }
    }

    @Test
    void binsAreRightClosedAndRightLabeled() {
        assertThat(MinuteResampler.binLabel(LocalDateTime.of(2024, 9, 2, 9, 35, 25)))
                .isEqualTo(LocalDateTime.of(2024, 9, 2, 9, 36));
        assertThat(MinuteResampler.binLabel(LocalDateTime.of(2024, 9, 2, 9, 36, 0)))
                .isEqualTo(LocalDateTime.of(2024, 9, 2, 9, 36));
    }

    @Test
    void requiresSeventyFivePercentCoverage() {
        assertThat(MinuteResampler.requiredReadings(5)).isEqualTo(9);
        assertThat(MinuteResampler.requiredReadings(60)).isEqualTo(1);
        assertThat(MinuteResampler.requiredReadings(3600)).isEqualTo(1);
    }

    @Test
    void keepsGapsAsMissingMinutesWithoutFillingThem() {
        List<SensorReading> readings = new ArrayList<>();
        readings.add(new SensorReading("A", LocalDateTime.of(2024, 1, 1, 0, 1), 5.0, null));
        readings.add(new SensorReading("A", LocalDateTime.of(2024, 1, 1, 0, 4), 8.0, null));

        List<MinuteReading> minutes = resampler.resample(readings);

        assertThat(minutes).extracting(MinuteReading::temperatureC).containsExactly(5.0, null, null, 8.0);
        assertThat(minutes).extracting(MinuteReading::readingCount).containsExactly(1, 0, 0, 1);
    }

    @Test
    void writesAndReadsMinuteFiles(@TempDir Path dir) throws IOException {
        List<MinuteReading> minutes = List.of(
                new MinuteReading("A", LocalDateTime.of(2024, 1, 1, 0, 1), 5.25, 70.0, 12),
                new MinuteReading("A", LocalDateTime.of(2024, 1, 1, 0, 2), null, null, 0));
        Path file = dir.resolve("minute.csv");

        MinuteCsv.write(file, minutes);

        assertThat(Files.readAllLines(file)).containsExactly(MinuteCsv.HEADER,
                "2024-01-01T00:01:00,A,5.250000,70.000000,12", "2024-01-01T00:02:00,A,,,0");
        assertThat(MinuteCsv.read(file)).isEqualTo(minutes);
    }

    private static void assertSame(Double actual, Double expected, String what) {
        if (expected == null) {
            assertThat(actual).as(what).isNull();
        } else {
            assertThat(actual).as(what).isNotNull();
            assertThat(actual).as(what).isCloseTo(expected, within(1e-6));
        }
    }
}

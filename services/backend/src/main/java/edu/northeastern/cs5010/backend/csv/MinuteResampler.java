package edu.northeastern.cs5010.backend.csv;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Averages readings into one-minute bins, matching {@code resample_sensor} in the ML preprocessing.
 *
 * <p>Bins are right-closed and right-labeled: {@code 09:36} covers {@code (09:35, 09:36]}. A bin keeps its
 * mean only when it holds at least 75% of the readings expected at the sensor's sampling interval;
 * otherwise it is missing. Missing bins are kept as {@code null} and never filled in, so no value
 * depends on later readings.
 *
 * <p>The ML code assumes the 5-second interval of the cold-room loggers. Here the interval is the median
 * gap between a sensor's consecutive readings, which gives the same result for those files and also
 * works for uploads logged once a minute.
 */
public class MinuteResampler {
    public static final double MIN_COVERAGE = 0.75;

    public List<MinuteReading> resample(List<SensorReading> readings) {
        Map<String, List<SensorReading>> bySensor = new LinkedHashMap<>();
        for (SensorReading reading : readings) {
            bySensor.computeIfAbsent(reading.sensorId(), k -> new ArrayList<>()).add(reading);
        }
        List<MinuteReading> result = new ArrayList<>();
        bySensor.forEach((sensorId, list) -> result.addAll(resampleSensor(sensorId, list)));
        return result;
    }

    /** Readings needed for a minute to count, given the sampling interval in seconds. */
    public static int requiredReadings(long intervalSeconds) {
        long interval = Math.max(1, Math.min(60, intervalSeconds));
        return (int) Math.ceil(60.0 / interval * MIN_COVERAGE);
    }

    static LocalDateTime binLabel(LocalDateTime timestamp) {
        LocalDateTime floor = timestamp.truncatedTo(ChronoUnit.MINUTES);
        return floor.equals(timestamp) ? floor : floor.plusMinutes(1);
    }

    private static List<MinuteReading> resampleSensor(String sensorId, List<SensorReading> readings) {
        List<SensorReading> sorted = new ArrayList<>(readings);
        sorted.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
        int required = requiredReadings(medianIntervalSeconds(sorted));

        Map<LocalDateTime, Bin> bins = new LinkedHashMap<>();
        for (SensorReading reading : sorted) {
            Bin bin = bins.computeIfAbsent(binLabel(reading.timestamp()), k -> new Bin());
            if (reading.temperatureC() != null) {
                bin.temperatureSum += reading.temperatureC();
                bin.temperatureCount++;
            }
            if (reading.humidity() != null) {
                bin.humiditySum += reading.humidity();
                bin.humidityCount++;
            }
        }
        LocalDateTime first = binLabel(sorted.get(0).timestamp());
        LocalDateTime last = binLabel(sorted.get(sorted.size() - 1).timestamp());
        List<MinuteReading> result = new ArrayList<>();
        for (LocalDateTime t = first; !t.isAfter(last); t = t.plusMinutes(1)) {
            Bin bin = bins.get(t);
            if (bin == null) {
                result.add(new MinuteReading(sensorId, t, null, null, 0));
                continue;
            }
            Double temperature = bin.temperatureCount >= required ? bin.temperatureSum / bin.temperatureCount : null;
            Double humidity = bin.humidityCount >= required ? bin.humiditySum / bin.humidityCount : null;
            result.add(new MinuteReading(sensorId, t, temperature, humidity, bin.temperatureCount));
        }
        return result;
    }

    private static long medianIntervalSeconds(List<SensorReading> sorted) {
        if (sorted.size() < 2) {
            return 60;
        }
        List<Long> gaps = new ArrayList<>(sorted.size() - 1);
        for (int i = 1; i < sorted.size(); i++) {
            long seconds = Duration.between(sorted.get(i - 1).timestamp(), sorted.get(i).timestamp()).toSeconds();
            if (seconds > 0) {
                gaps.add(seconds);
            }
        }
        if (gaps.isEmpty()) {
            return 60;
        }
        gaps.sort(Long::compare);
        return gaps.get(gaps.size() / 2);
    }

    private static final class Bin {
        double temperatureSum;
        int temperatureCount;
        double humiditySum;
        int humidityCount;
    }
}

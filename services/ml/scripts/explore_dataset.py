"""Read-only exploratory analysis of one cold-storage sensor CSV."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd


def pick(columns, names):
    return next((c for c in columns if c.lower() in names), None)


def describe_numeric(series):
    valid = series.dropna()
    if valid.empty:
        return {"count": 0}
    return {
        "count": int(valid.size),
        "min": float(valid.min()),
        "max": float(valid.max()),
        "mean": float(valid.mean()),
        "median": float(valid.median()),
        "std": float(valid.std()) if valid.size > 1 else None,
        "p05": float(valid.quantile(0.05)),
        "p95": float(valid.quantile(0.95)),
    }


def analyze(csv_path, output_dir):
    # sep=None detects semicolon- and comma-separated files.
    raw = pd.read_csv(csv_path, sep=None, engine="python", dtype=str,
                      encoding="utf-8-sig", on_bad_lines="warn")
    raw.columns = raw.columns.str.strip().str.lower()
    raw = raw.apply(lambda col: col.str.strip())
    original_rows = len(raw)

    date_col = pick(raw.columns, {"date"})
    time_col = pick(raw.columns, {"time"})
    timestamp_col = pick(raw.columns, {"timestamp", "datetime", "date_time"})
    temp_col = pick(raw.columns, {"temperature", "temp", "temperature_c"})
    humidity_col = pick(raw.columns, {"humidity", "relative_humidity", "rh"})

    if temp_col is None or (timestamp_col is None and
                             (date_col is None or time_col is None)):
        raise ValueError(f"Unsupported columns: {list(raw.columns)}. "
                         "Need temperature plus timestamp OR date and time.")

    if timestamp_col:
        timestamps = pd.to_datetime(raw[timestamp_col], errors="coerce", dayfirst=True)
    else:
        timestamps = pd.to_datetime(
            raw[date_col] + " " + raw[time_col],
            format="%d.%m.%Y %H:%M:%S", errors="coerce")

    def parse_numeric(col):
        if col is None:
            return pd.Series(float("nan"), index=raw.index)
        return pd.to_numeric(raw[col].str.replace(",", ".", regex=False),
                             errors="coerce")

    temperature = parse_numeric(temp_col)
    humidity = parse_numeric(humidity_col)
    parsed = pd.DataFrame({"timestamp": timestamps, "temperature": temperature,
                           "humidity": humidity})
    valid_times = parsed.dropna(subset=["timestamp"])
    sorted_data = valid_times.sort_values("timestamp")
    duplicates = int(sorted_data["timestamp"].duplicated().sum())

    # Time gaps are measured on unique timestamps, without filling observations.
    unique_times = sorted_data["timestamp"].drop_duplicates()
    seconds = unique_times.diff().dt.total_seconds().dropna()
    positive_seconds = seconds[seconds > 0]
    median_interval = (float(positive_seconds.median())
                       if not positive_seconds.empty else None)
    most_common_interval = (float(positive_seconds.mode().iloc[0])
                            if not positive_seconds.empty else None)
    gap_threshold = 1.5 * median_interval if median_interval else None
    long_gaps = (positive_seconds[positive_seconds > gap_threshold]
                 if gap_threshold else positive_seconds.iloc[0:0])

    # Consecutive missing-temperature runs on the original time grid.
    # A run is interrupted by a valid measurement or a discontinuity in timestamps.
    missing_runs = []
    current_start = None
    current_end = None
    current_count = 0
    previous_timestamp = None
    expected_interval = median_interval
    for timestamp, value in zip(sorted_data["timestamp"],
                                sorted_data["temperature"]):
        is_missing = pd.isna(value)
        is_contiguous = (previous_timestamp is not None
                         and expected_interval is not None
                         and (timestamp - previous_timestamp).total_seconds()
                         == expected_interval)
        if current_count and (not is_missing or not is_contiguous):
            missing_runs.append({
                "start": str(current_start),
                "end": str(current_end),
                "missing_records": current_count,
                "duration_seconds": (current_count * expected_interval
                                     if expected_interval is not None else None),
            })
            current_count = 0
        if is_missing:
            if current_count == 0:
                current_start = timestamp
            current_end = timestamp
            current_count += 1
        previous_timestamp = timestamp
    if current_count:
        missing_runs.append({
            "start": str(current_start),
            "end": str(current_end),
            "missing_records": current_count,
            "duration_seconds": (current_count * expected_interval
                                 if expected_interval is not None else None),
        })
    top_missing_runs = sorted(missing_runs,
                              key=lambda run: run["missing_records"],
                              reverse=True)[:10]

    start = unique_times.min() if not unique_times.empty else None
    end = unique_times.max() if not unique_times.empty else None
    report = {
        "input_file": str(csv_path),
        "raw_rows": original_rows,
        "columns": list(raw.columns),
        "detected_columns": {"date": date_col, "time": time_col,
                             "timestamp": timestamp_col, "temperature": temp_col,
                             "humidity": humidity_col},
        "missing_raw_cells": {col: int(raw[col].isna().sum()) for col in raw.columns},
        "invalid_timestamps": int(parsed["timestamp"].isna().sum()),
        "invalid_or_missing_temperatures": int(temperature.isna().sum()),
        "invalid_or_missing_humidity": (int(humidity.isna().sum())
                                        if humidity_col else None),
        "missing_temperature_runs": {
            "run_count": len(missing_runs),
            "longest_run_records": max(
                (run["missing_records"] for run in missing_runs), default=0),
            "longest_run_seconds": max(
                (run["duration_seconds"] for run in missing_runs
                 if run["duration_seconds"] is not None), default=0),
            "top_10_runs": top_missing_runs,
            "note": "Durations use the median sample interval; missing rows "
                    "not present in the CSV are assessed via timestamp gaps.",
        },
        "duplicate_timestamps": duplicates,
        "out_of_order_timestamps": int((timestamps.diff().dt.total_seconds() < 0).sum()),
        "first_timestamp": str(start) if start is not None else None,
        "last_timestamp": str(end) if end is not None else None,
        "duration_hours": (float((end - start).total_seconds() / 3600)
                           if start is not None and end is not None else None),
        "unique_timestamps": int(len(unique_times)),
        "median_interval_seconds": median_interval,
        "mode_interval_seconds": most_common_interval,
        "largest_interval_seconds": (float(positive_seconds.max())
                                     if not positive_seconds.empty else None),
        "gaps_over_1_5x_median": int(len(long_gaps)),
        "largest_gaps_seconds": [float(v) for v in long_gaps.nlargest(10)],
        "temperature": describe_numeric(temperature),
        "humidity": describe_numeric(humidity),
        "temperatures_below_minus_40_c": int((temperature < -40).sum()),
        "temperatures_above_80_c": int((temperature > 80).sum()),
        "humidity_outside_0_to_100": (int(((humidity < 0) | (humidity > 100)).sum())
                                     if humidity_col else None),
        "timestamp_timezone": "not specified in source CSV; assumed naive",
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    report_path = output_dir / "quality_report.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")

    plot_data = sorted_data.dropna(subset=["temperature"])
    if not plot_data.empty:
        # Limit plotted points for speed; never modify the underlying data.
        step = max(1, (len(plot_data) + 4999) // 5000)
        sample = plot_data.iloc[::step]
        fig, ax = plt.subplots(figsize=(12, 4))
        ax.plot(sample["timestamp"], sample["temperature"], linewidth=0.8)
        ax.set(title="Cold-storage temperature (sampled for display)",
               xlabel="Timestamp", ylabel="Temperature (reported units)")
        ax.grid(alpha=0.3)
        fig.autofmt_xdate()
        fig.tight_layout()
        fig.savefig(output_dir / "temperature_trend.png", dpi=150)
        plt.close(fig)

    print(json.dumps(report, indent=2))
    print(f"\nSaved report to: {report_path}")
    if not plot_data.empty:
        print(f"Saved plot to: {output_dir / 'temperature_trend.png'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="Path to one sensor CSV")
    parser.add_argument("--output", type=Path, default=Path("data/processed/eda"))
    args = parser.parse_args()
    if not args.csv.is_file():
        parser.error(f"CSV file not found: {args.csv}")
    analyze(args.csv, args.output)


if __name__ == "__main__":
    main()

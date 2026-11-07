"""Create a real-data HTTP request from Checkpoint 2 processed CSV (demo utility).

PowerShell example (from repository root):
  .\.venv\Scripts\python.exe services\ml\scripts\make_forecast_request.py --input data\processed\forecasting\all_sensors.csv --sensor SENSOR02 --threshold 10
"""
import argparse
import json
from pathlib import Path

import pandas as pd


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--sensor", required=True)
    parser.add_argument("--threshold", type=float, required=True)
    parser.add_argument("--output", type=Path, default=Path("data/processed/forecasting/sample_forecast_request.json"))
    args = parser.parse_args()
    frame = pd.read_csv(args.input)
    required = {"sensor_id", "timestamp", "temperature", "humidity"}
    if not required.issubset(frame.columns):
        parser.error(f"Missing required columns: {sorted(required - set(frame.columns))}")
    sensor = frame[frame.sensor_id == args.sensor].copy()
    sensor["timestamp"] = pd.to_datetime(sensor["timestamp"], errors="raise")
    sensor = sensor.sort_values("timestamp").set_index("timestamp").asfreq("1min")
    sensor["sensor_id"] = args.sensor
    valid = sensor.temperature.notna() & sensor.humidity.notna()
    complete = valid.rolling(61, min_periods=61).sum().eq(61)
    if not complete.any():
        parser.error("No complete 61-minute history for this sensor")
    # Choose the FIRST complete block for a reproducible smoke test.
    end = complete[complete].index[0]
    window = sensor.loc[end - pd.Timedelta(minutes=60):end]
    history = [{"timestamp": t.isoformat(), "temperature": float(row.temperature),
                "humidity": float(row.humidity)} for t, row in window.iterrows()]
    payload = {"sensor_id": args.sensor, "threshold": args.threshold,
               "horizons_minutes": [15, 30, 60], "history": history}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"Saved {len(history)} observed readings to {args.output}")


if __name__ == "__main__":
    main()

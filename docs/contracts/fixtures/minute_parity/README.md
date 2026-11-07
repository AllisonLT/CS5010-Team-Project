# One-minute resampling parity fixture

Synthetic data, not taken from any dataset. It checks that the Java backend (`MinuteResampler`) and the ML
preprocessing (`resample_sensor` in `cold_storage_ml.preprocessing`) turn the same CSV into the same
one-minute values.

- `SENSOR99_parity.csv`: 41 minutes of 5-second readings in the Zenodo layout. It includes a three-minute
  outage, one minute thinned below 75% coverage, `nan` temperatures, one 95 °C reading (outside -40..80),
  and one 120% humidity.
- `SENSOR99_parity.expected_minute.csv`: the output of the ML preprocessing on that file
  (`load_sensor_csv` then `resample_sensor`), written in the backend's `minute.csv` format. Values have
  6 decimals; missing values are empty.

Checked by `MinuteResamplerTest` (JUnit). A matching pytest test should be added once
`preprocessing.py` is merged into `main`.

To regenerate, run the ML preprocessing on `SENSOR99_parity.csv` and write the result with the columns
`timestamp,sensor_id,temperature_c,humidity,reading_count`, where `reading_count` is `temperature_count`.

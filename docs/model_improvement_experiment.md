# Checkpoint 4 follow-up: residual and short-term trend forecasting

This experimental extension **adds** files to Checkpoint 4. It does not alter existing preprocessing, baseline, absolute-target models, FastAPI, or saved model artifacts.

## Motivation

Persistence beat all initial ML models on pooled validation MAE. We test two adjustments:

1. **Residual regression**: fit three model families to `temperature(t+h) - temperature(t)` using the existing lag/rolling pipeline plus trailing short-term trend/volatility features. On validation, a correction weight in `{0, .1, .25, .5, .75, 1}` is selected. Weight 0 falls back to persistence.
2. **Damped-trend candidate**: `temperature(t+h) = temperature(t) + weight × (temperature(t) - temperature(t-lag))`, with validation-selected lag and weight. This candidate needs no fitted scikit-learn model, and provides a useful comparator for whether past direction helps.

All candidate inputs are available at/before prediction origin. Each sensor has its own minute grid. Targets are exact timestamps within the *same split*, and missing histories are excluded. Neither experiment reads `test.csv`.

**Model-selection warning:** Both shrinkage and damped-trend hyperparameters are selected on the validation partition. Their reported validation MAE is therefore **selection-tuned**, not an unbiased generalization estimate. Retain the held-out test partition and evaluate it only after choosing the approach and freezing all settings. Do not claim improvement on unseen future data yet. Avoid repeatedly tuning on one nine-day time series.

## Installation and commands (PowerShell from project root)

Place the five new Python files in the directory paths provided in the ZIP. Keep the existing Checkpoint 4 files. No new dependencies.

```powershell
.\.venv\Scripts\python.exe -m pytest services\ml\tests -v

# Fast trend experiment (does not train scikit-learn models)
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_trend.py --train data\processed\forecasting\train.csv --validation data\processed\forecasting\validation.csv

# Optional, slower: residual Ridge / Random Forest / HistGradientBoosting (9 models)
.\.venv\Scripts\python.exe services\ml\scripts\train_residual_models.py --train data\processed\forecasting\train.csv --validation data\processed\forecasting\validation.csv
```

## Output paths

Trend:
- `data/processed/forecasting/trend/validation_trend_metrics.json`
- `data/processed/forecasting/trend/validation_trend_predictions.csv`
- `models/artifacts/forecasting/trend/damped_trend_{15,30,60}min.json`

Residual:
- `data/processed/forecasting/residual/validation_residual_metrics.json`
- `data/processed/forecasting/residual/validation_residual_predictions.csv`
- `models/artifacts/forecasting/residual/residual_{model}_{15,30,60}min.joblib`

Do not use these new artifacts for production FastAPI inference until Checkpoint 5 creates tested load/validation behavior.

## Observed local smoke test (uploaded processed data; not held-out test)

For the three-sensor, one-minute dataset, using complete 60-minute histories:

| Horizon | Matched persistence MAE | Validation-selected damped trend MAE | Selected lag | Weight |
| --- | ---: | ---: | ---: | ---: |
| 15 min | 0.33865 | 0.29691 | 5 min | 1.0 |
| 30 min | 0.57635 | 0.53597 | 5 min | 1.5 |
| 60 min | 0.99480 | 0.93048 | 10 min | 1.0 |

The trend comparison is matched row-for-row. Units remain those of the raw CSV, not yet independently verified. Results are exploratory because the lag/weight search used validation itself.

Initial residual experiment: validation selected correction weight 0.0 for every *completed* fit, providing no MAE gain over persistence. That smoke test was interrupted before finishing all nine models, so run the full residual training locally if you want complete results. A result of zero simply means the candidate was not useful under the tested selection rule, not that the code failed.

## Next diagnostic ideas

- Compare the improvement separately for each sensor and for rapid warming/cooling events.
- Investigate why training and validation have different temperature dynamics before tuning model complexity.
- Consider adding 5-minute predictions as an **additional** horizon if product requirements allow; do not replace the already required 15/30/60-minute outputs.
- Freeze candidate selection before one-time held-out evaluation in the final QA milestone.

"""Fit no model weights: select simple trend parameters using validation only.

This is an exploratory validation-tuned baseline, not an unbiased validation
estimate after selection. test.csv must be kept untouched until final evaluation.
"""
import argparse
import json
from pathlib import Path

import pandas as pd

from cold_storage_ml.baseline import load_partition
from cold_storage_ml.forecast_models import SUPPORTED_HORIZONS_MINUTES, validate_splits
from cold_storage_ml.residual_forecasting import residual_examples
from cold_storage_ml.trend_forecasting import select_trend, evaluate_trend


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--train', type=Path, default=Path('data/processed/forecasting/train.csv'))
    parser.add_argument('--validation', type=Path, default=Path('data/processed/forecasting/validation.csv'))
    parser.add_argument('--results-dir', type=Path, default=Path('data/processed/forecasting/trend'))
    parser.add_argument('--output-dir', type=Path, default=Path('models/artifacts/forecasting/trend'))
    args=parser.parse_args()
    if args.train.name.lower()!='train.csv' or args.validation.name.lower()!='validation.csv':
        parser.error('Requires train.csv and validation.csv; test.csv excluded')
    train=load_partition(str(args.train))
    validation=load_partition(str(args.validation))
    validate_splits(train, validation)
    args.results_dir.mkdir(parents=True,exist_ok=True)
    args.output_dir.mkdir(parents=True,exist_ok=True)
    report={'evaluation_partition':'validation',
            'selection_note':'Lag and correction weight SELECTED ON validation. Metrics are exploratory/in-sample to model-selection; leave test untouched.',
            'horizons':{}}
    results=[]
    for horizon in SUPPORTED_HORIZONS_MINUTES:
        examples=residual_examples(validation,horizon)
        selected,candidates=select_trend(examples)
        predictions, scores=evaluate_trend(examples, selected)
        results.append(predictions)
        report['horizons'][str(horizon)]={'selected':selected,'scores':scores,'candidate_count':len(candidates)}
        (args.output_dir/f'damped_trend_{horizon}min.json').write_text(json.dumps({
            'method':'damped_trend', 'horizon_minutes':horizon,
            'lag_minutes':selected['lag_minutes'],'weight':selected['weight'],
            'prediction_formula':'temperature_now + weight * (temperature_now - temperature_lag)',
            'sensor_ids':sorted(train.sensor_id.unique().tolist()),
            'minimum_history_minutes':60,
        },indent=2),encoding='utf-8')
        print(f'{horizon} min: lag={selected["lag_minutes"]}, weight={selected["weight"]}; '
              f'MAE={scores["model"]["mae"]:.5f}, persistence={scores["matched_persistence"]["mae"]:.5f}',flush=True)
    pd.concat(results,ignore_index=True).to_csv(args.results_dir/'validation_trend_predictions.csv',index=False,float_format='%.9f')
    (args.results_dir/'validation_trend_metrics.json').write_text(json.dumps(report,indent=2),encoding='utf-8')


if __name__=='__main__':
    main()

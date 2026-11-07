import pandas as pd
import numpy as np
from cold_storage_ml.residual_forecasting import residual_examples
from cold_storage_ml.trend_forecasting import select_trend,evaluate_trend


def make_data():
    t=pd.date_range('2024-01-01',periods=150,freq='min')
    return pd.DataFrame({'timestamp':t,'sensor_id':'SENSOR02','temperature':np.arange(150)*0.1+6,'humidity':75.})


def test_trend_improves_linear_series():
    d=residual_examples(make_data(),15)
    selected,candidates=select_trend(d)
    _,scores=evaluate_trend(d,selected)
    assert scores['model']['mae']<scores['matched_persistence']['mae']
    assert len(candidates)>10


def test_matched_examples_and_time_horizon():
    d=residual_examples(make_data(),30)
    selected,_=select_trend(d)
    results,scores=evaluate_trend(d,selected)
    assert len(results)==scores['model']['count']==scores['matched_persistence']['count']
    assert ((results.target_timestamp-results.origin_timestamp)==pd.Timedelta(minutes=30)).all()

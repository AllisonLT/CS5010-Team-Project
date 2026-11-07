import numpy as np
import pandas as pd
import pytest
import joblib

from cold_storage_ml.residual_forecasting import (
    EXTRA_FEATURES, RESIDUAL_FEATURES, create_residual_model,
    evaluate_residual, residual_examples, select_shrinkage,
)


def fixture_partition(start='2024-01-01', minutes=170, sensor='SENSOR01'):
    ts = pd.date_range(start, periods=minutes, freq='min')
    temp = 8 + 0.02 * np.arange(minutes)
    return pd.DataFrame({'timestamp':ts, 'sensor_id':sensor,
                         'temperature':temp, 'humidity':70 + 0.01*np.arange(minutes)})


def test_target_is_future_delta():
    x=residual_examples(fixture_partition(), 15)
    assert len(x)>0
    assert np.allclose(x.target_delta, 0.3)
    assert (x.target_timestamp-x.origin_timestamp).eq(pd.Timedelta(minutes=15)).all()


def test_extra_features_are_past_only():
    data=fixture_partition()
    x=residual_examples(data,15)
    origin=x.iloc[3].origin_timestamp
    data.loc[data.timestamp>origin,'temperature']=999
    y=residual_examples(data,15)
    before=x[x.origin_timestamp==origin].iloc[0]
    after=y[y.origin_timestamp==origin].iloc[0]
    assert np.allclose(before[EXTRA_FEATURES].astype(float),after[EXTRA_FEATURES].astype(float))


def test_missing_history_drops_examples():
    data=fixture_partition()
    data.loc[data.timestamp==pd.Timestamp('2024-01-01 01:05:00'),'humidity']=np.nan
    x=residual_examples(data,15)
    assert not x.origin_timestamp.between(pd.Timestamp('2024-01-01 01:05'),pd.Timestamp('2024-01-01 02:05')).any()


def test_sensor_isolation():
    a=fixture_partition(sensor='SENSOR01')
    b=fixture_partition(sensor='SENSOR02')
    b['temperature']+=100
    x=residual_examples(pd.concat([a,b]),15)
    assert np.allclose(x[x.sensor_id=='SENSOR01'].target_delta,0.3)
    assert np.allclose(x[x.sensor_id=='SENSOR02'].target_delta,0.3)


def test_shrinkage_zero_for_bad_correction():
    weight, scores=select_shrinkage(np.array([7.,7.]),np.array([7.,7.]),np.array([5.,5.]))
    assert weight==0
    assert scores['0.0']['mae']==0


def test_shrinkage_one_for_perfect_correction():
    weight,_=select_shrinkage(np.array([8.,10.]),np.array([7.,9.]),np.array([1.,1.]))
    assert weight==1


def test_shrinkage_rejects_bad_arrays():
    with pytest.raises(ValueError):
        select_shrinkage(np.array([1]),np.array([1,2]),np.array([1]))


@pytest.mark.parametrize('name',['ridge','random_forest','hist_gradient_boosting'])
def test_models_fit_save_load_and_evaluate(tmp_path,name):
    x=residual_examples(fixture_partition(),15)
    model=create_residual_model(name)
    model.fit(x[['sensor_id',*RESIDUAL_FEATURES]],x.target_delta)
    results,report=evaluate_residual(model,x,name)
    assert len(results)==len(x)
    assert report['model']['count']==len(x)
    assert np.isfinite(results.predicted_temperature).all()
    fn=tmp_path/'model.joblib'
    joblib.dump(model,fn)
    loaded=joblib.load(fn)
    assert np.allclose(model.predict(x[['sensor_id',*RESIDUAL_FEATURES]]),loaded.predict(x[['sensor_id',*RESIDUAL_FEATURES]]))


def test_no_targets_cross_partition():
    data=fixture_partition(minutes=160)
    left=data.iloc[:100]
    right=data.iloc[100:]
    assert residual_examples(left,60).empty
    assert residual_examples(right,15).empty

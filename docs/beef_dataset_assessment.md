# Beef dataset: preliminary assessment

This is separate from the implemented poultry model. No beef model has been
trained or integrated, and no beef data is used to validate poultry predictions.

## Verified metadata

- Source: https://data.mendeley.com/datasets/mwmhh766fc/3
- Version DOI: https://doi.org/10.17632/mwmhh766fc.3
- Title: Electronic nose dataset for beef quality monitoring under an uncontrolled environment.
- Creators credited in the record: Dedy Rahman Wijaya, Riyanarto Sarno, Enny Zulaika.
- License: Creative Commons Attribution 4.0 International.
- Evidence: https://api.datacite.org/dois/10.17632/mwmhh766fc.3
  (retrieved November 21, 2026).

The record describes five series, `TS1.csv` through `TS5.csv`, each from a
different beef cut with 2,160 one-minute measurements. It lists:

- `Minute`: measurement time in minutes.
- `Class`: excellent, good, acceptable, or spoiled.
- `TVC`: microbial population in log10 CFU/g.
- `MQ_` columns: gas-sensor resistance in ohms.
- `Humidity`: sample-chamber relative humidity.
- `Temperature`: sample-chamber Celsius temperature.

These are source descriptions, not locally verified row counts, distributions,
or independent label measurements. The dataset files have not yet been downloaded
and inspected; the attempted Mendeley metadata endpoint returned 404, while
DataCite metadata was accessible.

## Suitability and next gate

Unlike the previously reviewed fruit dataset, this record describes actual beef
quality classes and a time dimension, so it warrants inspection for learned
spoilage classification. It does not establish a model suitable for arbitrary
cold-room histories, poultry, or remaining shelf life.

Before any beef ML implementation:

1. Retrieve the exact version's files, record checksums and attribution, inspect
   missing values, duplicates, sampling, temperatures, class transitions, and how
   TVC/class labels were measured or interpolated.
2. Decide which predictors are available at inference. The current application
   supplies air temperature, not electronic-nose gas measurements. A model using
   MQ sensors cannot silently accept temperature-only requests.
3. Keep entire beef-cut series separate across train, validation, and test.
   Never randomly split adjacent rows; fit preprocessing on training series only.
   Five independent cuts are a small sample regardless of the number of rows.
4. Do not feed `Class`, `TVC`, future readings, or time-to-rejection into a
   classifier intended to predict spoilage from exposure. If using TVC as a target,
   keep it out of predictors. Deriving time-to-spoilage targets requires documented
   event/censoring rules and cannot establish general cold-storage shelf life.
5. Compare against simple baselines on held-out cuts; report per-class metrics,
   supported temperature range, dependence on elapsed time/gas sensors, and limitations.
6. Agree on a separate beef contract or an explicit reviewed change to the product
   and inference APIs before integration. The current poultry equations and
   parameters must not be relabeled as beef ML.

# API B contract examples

Example request and response bodies for the backend → ML service API (`docs/api_contracts.md`, section 3).

| File | What it is |
|---|---|
| `ml_forecast_request.json` | Shape of `POST /api/v1/predictions/temperature` as the backend sends it (real requests have 61 readings) |
| `ml_forecast_response.json` | Response from the current FastAPI service on the `Leyang` branch, including fields the backend ignores |
| `ml_shelf_life_request.json` | `POST /api/v1/predictions/shelf-life` as the backend will send it, with a missing minute as `null` |
| `ml_shelf_life_response.json` | Response that the shelf-life endpoint (not yet implemented) must return |
| `ml_error_422_text.json` | FastAPI error with a text `detail` |
| `ml_error_422_validation.json` | FastAPI validation error with a list `detail` |

`MlClientContractTest` (JUnit) checks that the backend sends these request shapes and maps these responses.
When the ML code is merged into `main`, a pytest test should check that FastAPI accepts the request files
and returns responses with the same fields and types.

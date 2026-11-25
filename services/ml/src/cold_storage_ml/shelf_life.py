"""Published poultry spoilage model, not a trained ML or food-safety model."""
from __future__ import annotations

import math
from datetime import datetime, timedelta
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

B = 0.022
TMIN_C = -6.18
SPOILAGE_LOG_COUNT = 7.0
MODEL_VERSION = "tsaloumi2022-v1"
CITATION_DOI = "10.1016/j.psj.2022.101985"


class ShelfLifeObservation(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    timestamp: str
    temperature_c: float | None


class ShelfLifeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    category: Literal["FRESH_POULTRY_AEROBIC"]
    initial_log_count: float = Field(ge=1.0, le=4.0)
    physiological_state_alpha0: float = Field(ge=0.1, le=1.0)
    future_temperature_c: float | None = Field(default=None, ge=0.0, le=30.0)
    interval_minutes: int = Field(strict=True, ge=1, le=1)
    history: list[ShelfLifeObservation] = Field(min_length=60, max_length=20160)

    @model_validator(mode="after")
    def validate_history(self) -> "ShelfLifeRequest":
        previous = None
        gap_start = None
        gap_length = 0
        for index, reading in enumerate(self.history):
            try:
                timestamp = datetime.fromisoformat(reading.timestamp)
            except ValueError as exc:
                raise ValueError(f"Invalid history timestamp: {reading.timestamp}") from exc
            if ("T" not in reading.timestamp or timestamp.tzinfo is not None
                    or timestamp.second or timestamp.microsecond):
                raise ValueError("History requires timezone-naive ISO timestamps aligned to whole minutes")
            if previous is not None and timestamp - previous != timedelta(minutes=1):
                raise ValueError("History must be strictly increasing consecutive one-minute readings")
            previous = timestamp
            if reading.temperature_c is None:
                if gap_start is None:
                    gap_start = reading.timestamp
                gap_length += 1
            else:
                if gap_length > 10:
                    raise ValueError(
                        f"Missing-temperature gap exceeds 10 minutes: {gap_start} to "
                        f"{self.history[index - 1].timestamp}"
                    )
                gap_start = None
                gap_length = 0
        if self.history[0].temperature_c is None or self.history[-1].temperature_c is None:
            raise ValueError("First and last history temperatures must not be null")
        if self.future_temperature_c is None:
            last = self.history[-1].temperature_c
            if last is None or not 0 <= last <= 30:
                raise ValueError("Default future temperature (last reading) must be between 0 and 30 C")
        return self


def _temperatures(history: list[ShelfLifeObservation]) -> list[float]:
    result: list[float] = []
    for index, reading in enumerate(history):
        if reading.temperature_c is not None:
            result.append(reading.temperature_c)
        else:
            if index > 0 and history[index - 1].temperature_c is None:
                continue
            end = index
            while history[end].temperature_c is None:
                end += 1
            left = result[-1]
            right = history[end].temperature_c
            assert right is not None
            count = end - index
            result.extend(left * (1 - offset / (count + 1)) + right * offset / (count + 1)
                          for offset in range(1, count + 1))
    return result


def _growth_rate(temperature_c: float) -> float:
    value = B * max(0.0, temperature_c - TMIN_C)
    rate = value * value
    if not math.isfinite(rate):
        raise ValueError("Temperature is too extreme for a finite modeled growth rate")
    return rate


def estimate_shelf_life(payload: ShelfLifeRequest) -> dict:
    temperatures = _temperatures(payload.history)
    # Each reading represents one minute of exposure, including the final bin.
    growth = [_growth_rate(t) / 60 for t in temperatures]
    cumulative = sum(growth)
    if not math.isfinite(cumulative):
        raise ValueError("Temperature history produces nonfinite cumulative growth")
    alpha = payload.physiological_state_alpha0
    # Stable Baranyi adjustment: log(1 + alpha * (exp(cumulative) - 1)).
    adjusted = cumulative + math.log(alpha + (1 - alpha) * math.exp(-cumulative))
    log_count = payload.initial_log_count + adjusted / math.log(10)
    target_growth = (SPOILAGE_LOG_COUNT - payload.initial_log_count) * math.log(10)
    total_growth_to_spoil = (
        target_growth + math.log1p(-(1 - alpha) * math.exp(-target_growth)) - math.log(alpha)
    )
    future = payload.future_temperature_c
    if future is None:
        future = temperatures[-1]
    remaining = max(0.0, total_growth_to_spoil - cumulative) / _growth_rate(future)
    used = min(1.0, max(0.0, adjusted / target_growth))
    past = cumulative >= total_growth_to_spoil
    above_growth = sum(g for t, g in zip(temperatures, growth) if t > 4)
    flags = []
    if any(t < 0 or t > 30 for t in temperatures):
        flags.append("HISTORY_TEMPERATURE_OUTSIDE_VALIDATED_RANGE")
    if payload.initial_log_count < 2:
        flags.append("INITIAL_LOG_COUNT_OUTSIDE_TESTED_RANGE")
    return {
        "category": payload.category,
        "history_start": payload.history[0].timestamp,
        "history_end": payload.history[-1].timestamp,
        "future_temperature_c": future,
        "remaining_shelf_life_hours": remaining,
        "shelf_life_used_fraction": used,
        "spoilage_risk": "high" if used >= 0.8 else "medium" if used >= 0.5 else "low",
        "estimated_log_count": log_count,
        "past_spoilage_level": past,
        "factors": {
            "minutes_above_4c": sum(t > 4 for t in temperatures),
            "peak_temperature_c": max(temperatures),
            "mean_temperature_c": math.fsum(t / len(temperatures) for t in temperatures),
            "growth_share_above_4c": above_growth / cumulative if cumulative > 0 else 0.0,
        },
        "interpolated_minutes": sum(r.temperature_c is None for r in payload.history),
        "flags": flags,
        "limitations": [
            "Published growth-model estimate for fresh poultry in aerobic storage, not trained ML "
            "and not a food safety determination.",
            "Based on storage-room air temperature, not product temperature.",
            "Assumes the supplied history begins at the stated initial contamination level; "
            "prior exposure is not inferred.",
            "Future storage temperature is assumed constant. Growth parameters are validated at 0-30 C.",
            "Risk cutoffs of 0.5 and 0.8 and the 4 C exposure reference are project conventions, "
            "not calibrated probabilities.",
            "Initial contamination and physiological state are assumed inputs; no confidence interval is estimated.",
        ],
        "model": {
            "name": "pseudomonads_square_root",
            "version": MODEL_VERSION,
            "citation_doi": CITATION_DOI,
            "parameters": {"b": B, "tmin_c": TMIN_C, "spoilage_log_count": SPOILAGE_LOG_COUNT},
        },
        "unit": "celsius",
    }

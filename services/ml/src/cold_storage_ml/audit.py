"""Persist operational events from the independently running ML service."""
from __future__ import annotations

import logging
import os
import socket
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from uuid import uuid4

import pymongo
from fastapi import HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from pymongo.errors import PyMongoError

logger = logging.getLogger(__name__)
COLLECTION = "audit_ml"
DEFAULT_URI = "mongodb://127.0.0.1:27017/cold_storage"


class AuditWriter:
    def __init__(self) -> None:
        uri = os.getenv("ML_AUDIT_MONGODB_URI") or os.getenv("MONGODB_URI") or DEFAULT_URI
        self.client = pymongo.MongoClient(uri, connect=False)
        self.collection = self.client.get_default_database(default="cold_storage")[COLLECTION]
        self.host = socket.gethostname()

    def record(
        self,
        event_type: str,
        source: str,
        details: dict | None = None,
        *,
        error: dict | None = None,
        warning: dict | None = None,
    ) -> None:
        record = {
            "_id": str(uuid4()),
            "schemaVersion": 1,
            "timestamp": datetime.now(timezone.utc),
            "eventType": event_type,
            "source": f"ml.{source}",
            "host": self.host,
            "actor": "service:ml",
            "outcome": "FAILURE" if error else "SUCCESS",
            "refs": {"datasetId": None, "productId": None, "predictionId": None},
            "details": details or {},
            "error": error,
        }
        records = [record]
        if warning:
            records.append({
                **record,
                "_id": str(uuid4()),
                "eventType": "ML_THRESHOLD_WARNING",
                "details": {**record["details"], **warning},
            })
        try:
            with pymongo.timeout(1):
                self.collection.insert_many(records)
        except PyMongoError as exc:
            for missed in records:
                logger.error("MongoDB did not confirm ML audit event (%s): %s", type(exc).__name__, missed)


@asynccontextmanager
async def audit_lifespan(app):
    writer = AuditWriter()
    app.state.audit = writer
    try:
        await run_in_threadpool(writer.record, "ML_STARTED", "lifecycle")
        yield
    finally:
        try:
            await run_in_threadpool(writer.record, "ML_STOPPING", "lifecycle")
        finally:
            writer.client.close()


class AuditedRoute(APIRoute):
    """Record validated request metadata and outcomes, including rejected bodies."""

    def get_route_handler(self):
        handler = super().get_route_handler()
        group = ("ML_INFERENCE" if self.path.endswith(("/temperature", "/shelf-life"))
                 else "ML_ALERT_CHECK")

        async def audited(request: Request):
            writer = getattr(request.app.state, "audit", None)
            try:
                response = await handler(request)
            except Exception as exc:
                if writer is not None:
                    if isinstance(exc, (HTTPException, RequestValidationError)):
                        status = exc.status_code if isinstance(exc, HTTPException) else 422
                        event_type = f"{group}_FAILED"
                        error = {"code": f"HTTP_{status}", "message": "ML request could not be completed"}
                    else:
                        event_type = "ML_SYSTEM_ERROR"
                        error = {"code": "INTERNAL_ERROR", "message": "Unexpected ML request error"}
                    await run_in_threadpool(writer.record, event_type, self.name,
                                            getattr(request.state, "audit_details", {}), error=error)
                raise
            if writer is not None:
                await run_in_threadpool(writer.record, f"{group}_SUCCEEDED", self.name,
                                        getattr(request.state, "audit_details", {}),
                                        warning=getattr(request.state, "audit_warning", None))
            return response

        return audited

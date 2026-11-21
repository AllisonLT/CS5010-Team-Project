"""HTTP tests can inspect audit writes without a running database."""
from unittest.mock import MagicMock

import pytest

from cold_storage_ml import audit


@pytest.fixture(autouse=True)
def audit_collection(monkeypatch):
    client = MagicMock()
    monkeypatch.setattr(audit.pymongo, "MongoClient", lambda *args, **kwargs: client)
    return client.get_default_database.return_value.__getitem__.return_value

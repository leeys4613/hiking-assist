import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import config
from app.main import create_app

TOKEN = "test-token-123"


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setattr(config, "TOKEN", TOKEN)
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    with TestClient(create_app(factory)) as c:   # with → startup(seed) 실행
        c.factory = factory
        yield c


@pytest.fixture()
def auth():
    return {"Authorization": f"Bearer {TOKEN}"}

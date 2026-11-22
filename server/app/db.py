from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from . import config


class Base(DeclarativeBase):
    pass


def make_engine(path=None):
    p = path or config.DB_PATH
    if str(p) != ":memory:":
        p.parent.mkdir(parents=True, exist_ok=True)
        url = f"sqlite:///{p}"
    else:
        url = "sqlite://"
    return create_engine(url, connect_args={"check_same_thread": False})


engine = make_engine()
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

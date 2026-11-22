from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base


class Mountain(Base):
    __tablename__ = "mountain"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    name: Mapped[str] = mapped_column(String)
    lat: Mapped[float] = mapped_column(Float)
    lon: Mapped[float] = mapped_column(Float)
    courses: Mapped[list["Course"]] = relationship(back_populates="mountain")


class Course(Base):
    __tablename__ = "course"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    mountain_id: Mapped[str] = mapped_column(ForeignKey("mountain.id"))
    name: Mapped[str] = mapped_column(String)
    length_m: Mapped[float] = mapped_column(Float)
    ascent_m: Mapped[float] = mapped_column(Float)
    descent_m: Mapped[float] = mapped_column(Float)
    difficulty: Mapped[str] = mapped_column(String, default="")
    std_minutes: Mapped[int] = mapped_column(Integer)          # Tobler 기반 예상(왕복 전체)
    package_json: Mapped[str] = mapped_column(Text)            # 앱에 내려줄 코스 패키지 원본
    mountain: Mapped[Mountain] = relationship(back_populates="courses")
    hikes: Mapped[list["Hike"]] = relationship(back_populates="course")


class Hike(Base):
    __tablename__ = "hike"
    __table_args__ = (UniqueConstraint("course_id", "started_at", name="uq_hike_course_start"),)
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    course_id: Mapped[str] = mapped_column(ForeignKey("course.id"))
    started_at: Mapped[datetime] = mapped_column(DateTime)
    ended_at: Mapped[datetime] = mapped_column(DateTime)
    total_s: Mapped[int] = mapped_column(Integer)
    summit_s: Mapped[int | None] = mapped_column(Integer, nullable=True)
    rest_count: Mapped[int] = mapped_column(Integer, default=0)
    rest_s: Mapped[int] = mapped_column(Integer, default=0)
    distance_m: Mapped[float] = mapped_column(Float, default=0)
    avg_pace_s_per_km: Mapped[float | None] = mapped_column(Float, nullable=True)
    steps: Mapped[int | None] = mapped_column(Integer, nullable=True)
    track_json: Mapped[str] = mapped_column(Text)              # [[t_ms, lat, lon, acc, progress], ...]
    events_json: Mapped[str] = mapped_column(Text)             # [{"t":..,"key":..,"text":..}, ...]
    uploaded_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    course: Mapped[Course] = relationship(back_populates="hikes")


class PaceState(Base):
    """개인 페이스 모델(전체 기록에서 학습). 업로드 때마다 다시 학습해 한 행(id=1)을 갱신한다."""
    __tablename__ = "pace_state"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    model_json: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)


class GraphState(Base):
    """산별 등산로 그래프와 검증 리포트(명세 §7). enabled 는 검증 통과 여부이며,
    통과한 산에서만 패키지에 graph 를 실어 앱의 재경로를 켠다(AC8)."""
    __tablename__ = "graph_state"
    mountain_id: Mapped[str] = mapped_column(ForeignKey("mountain.id"), primary_key=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    report_json: Mapped[str] = mapped_column(Text)
    graph_json: Mapped[str] = mapped_column(Text)

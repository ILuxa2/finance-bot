"""PNG chart generation for the Telegram reports."""

from __future__ import annotations

import logging
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from uuid import uuid4

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

from app.reports import MONTH_NAMES_RU, calculate_budget

logger = logging.getLogger(__name__)
CHARTS_DIR = Path("tmp/charts")


def build_expenses_chart(rows: list[dict[str, str]], *, now: datetime | None = None) -> Path | None:
    current = _now(now)
    totals: defaultdict[str, Decimal] = defaultdict(Decimal)
    for row in _month_rows(rows, current):
        if row.get("type") == "expense":
            totals[str(row.get("category") or "Без категории")] += _amount(row, "amount")
    if not totals:
        return None
    labels, values = _sorted_series(totals)
    title = f"Расходы по категориям  {_month_title(current)}"
    fig, ax = plt.subplots(figsize=(10, max(4.5, len(labels) * .55)))
    if len(labels) > 5:
        ax.barh(labels[::-1], [float(value) for value in values[::-1]], color="#e76f51")
        ax.set_xlabel("₽")
    else:
        ax.pie([float(value) for value in values], labels=labels, autopct="%1.1f%%", startangle=90)
        ax.axis("equal")
    ax.set_title(title)
    return _save(fig, "expenses")


def build_cashflow_chart(rows: list[dict[str, str]], *, now: datetime | None = None) -> Path | None:
    current = _now(now)
    income: defaultdict[int, Decimal] = defaultdict(Decimal)
    expense: defaultdict[int, Decimal] = defaultdict(Decimal)
    for row in _month_rows(rows, current):
        timestamp = _timestamp(row.get("created_at", ""))
        if timestamp is None:
            continue
        if row.get("type") == "income": income[timestamp.day] += _amount(row, "amount")
        elif row.get("type") == "expense": expense[timestamp.day] += _amount(row, "amount")
    if not income and not expense:
        return None
    days = sorted(set(income) | set(expense))
    fig, ax = plt.subplots(figsize=(10, 5))
    ax.plot(days, [float(income[day]) for day in days], marker="o", label="Доходы", color="#2a9d8f")
    ax.plot(days, [float(expense[day]) for day in days], marker="o", label="Расходы", color="#e76f51")
    ax.set(title=f"Доходы и расходы по дням  {_month_title(current)}", xlabel="День месяца", ylabel="₽")
    ax.set_xticks(days)
    ax.legend()
    ax.grid(alpha=.25)
    return _save(fig, "cashflow")


def build_plan_chart(rows: list[dict[str, str]], plans: list[dict[str, str]], startup: dict[str, str] | None = None, *, now: datetime | None = None) -> Path | None:
    budget = calculate_budget(rows, plans, startup, now=now)
    categories: list[dict[str, object]] = budget["categories"]  # type: ignore[assignment]
    if not categories:
        return None
    labels = [str(item["category"]) for item in categories]
    planned = [float(item["planned"]) for item in categories]
    actual = [float(item["actual"]) for item in categories]
    positions = list(range(len(labels)))
    fig, ax = plt.subplots(figsize=(max(8, len(labels) * 1.25), 5))
    ax.bar([position - .2 for position in positions], planned, .4, label="План", color="#457b9d")
    ax.bar([position + .2 for position in positions], actual, .4, label="Факт", color="#e76f51")
    ax.set(title=f"План / факт  {_month_title(budget['now'])}", ylabel="₽")
    ax.set_xticks(positions, labels, rotation=25, ha="right")
    ax.legend()
    fig.tight_layout()
    return _save(fig, "plan")


def build_debts_chart(debts: list[dict[str, str]]) -> Path | None:
    active = [debt for debt in debts if debt.get("status") == "active"]
    if not active:
        return None
    labels = [str(debt.get("debt_name") or "Без названия") for debt in active]
    values = [float(_amount(debt, "current_amount")) for debt in active]
    fig, ax = plt.subplots(figsize=(10, max(4, len(labels) * .6)))
    ax.barh(labels[::-1], values[::-1], color="#e76f51")
    ax.set(title="Остатки долгов", xlabel="₽")
    return _save(fig, "debts")


def build_goals_chart(goals: list[dict[str, str]]) -> Path | None:
    active = [goal for goal in goals if goal.get("status") == "active"]
    if not active:
        return None
    labels = [str(goal.get("goal_name") or "Без названия") for goal in active]
    current = [float(_amount(goal, "current_amount")) for goal in active]
    target = [float(_amount(goal, "target_amount")) for goal in active]
    positions = list(range(len(labels)))
    fig, ax = plt.subplots(figsize=(max(8, len(labels) * 1.25), 5))
    ax.bar([position - .2 for position in positions], current, .4, label="Накоплено", color="#2a9d8f")
    ax.bar([position + .2 for position in positions], target, .4, label="Цель", color="#457b9d")
    ax.set(title="Прогресс целей", ylabel="₽")
    ax.set_xticks(positions, labels, rotation=25, ha="right")
    ax.legend()
    fig.tight_layout()
    return _save(fig, "goals")


def _save(fig, prefix: str) -> Path:
    CHARTS_DIR.mkdir(parents=True, exist_ok=True)
    path = CHARTS_DIR / f"{prefix}-{uuid4().hex}.png"
    try:
        fig.tight_layout()
        fig.savefig(path, format="png", dpi=160, bbox_inches="tight")
        return path
    finally:
        plt.close(fig)


def _month_rows(rows: list[dict[str, str]], current: datetime):
    for row in rows:
        timestamp = _timestamp(row.get("created_at", ""))
        if timestamp and (timestamp.year, timestamp.month) == (current.year, current.month):
            yield row


def _sorted_series(values: defaultdict[str, Decimal]) -> tuple[list[str], list[Decimal]]:
    pairs = sorted(values.items(), key=lambda item: (-item[1], item[0].casefold()))
    return [pair[0] for pair in pairs], [pair[1] for pair in pairs]


def _now(now: datetime | None) -> datetime:
    return (now or datetime.now(timezone.utc)).astimezone(timezone.utc)


def _month_title(current: datetime) -> str:
    return f"{MONTH_NAMES_RU[current.month - 1]} {current.year}"


def _timestamp(value: str) -> datetime | None:
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None
    return result.replace(tzinfo=timezone.utc) if result.tzinfo is None else result.astimezone(timezone.utc)


def _amount(row: dict[str, str], field: str) -> Decimal:
    try:
        return Decimal(str(row.get(field, "0")))
    except (InvalidOperation, ValueError):
        return Decimal("0")

"""Pure FinanceOS business calculations.

The functions in this module do not import Telegram or Google Sheets and are
safe to use from reports, tests and future presentation layers.
"""

from __future__ import annotations

from calendar import monthrange
from decimal import Decimal, InvalidOperation
from typing import Iterable, Mapping
from datetime import datetime, timezone
import re


Money = Decimal | int | float | str
Operation = Mapping[str, object]


def as_decimal(value: Money | None) -> Decimal:
    """Convert supported numeric input to Decimal, treating invalid input as zero."""
    try:
        return Decimal(str(value if value is not None else 0))
    except (InvalidOperation, ValueError):
        return Decimal("0")


def calculate_income(operations: Iterable[Operation], starting_income: Money = 0) -> Decimal:
    return as_decimal(starting_income) + sum(
        (as_decimal(row.get("amount")) for row in operations if row.get("type") == "income"),
        Decimal("0"),
    )


def calculate_expenses(operations: Iterable[Operation], starting_expense: Money = 0) -> Decimal:
    return as_decimal(starting_expense) + sum(
        (as_decimal(row.get("amount")) for row in operations if row.get("type") == "expense"),
        Decimal("0"),
    )


def calculate_balance(
    operations: Iterable[Operation], startup_balance: Money | None = None
) -> Decimal:
    """Return startup balance plus cash flow, or income minus expense without it."""
    rows = list(operations)
    cash_flow = calculate_income(rows) - calculate_expenses(rows)
    return cash_flow if startup_balance is None else as_decimal(startup_balance) + cash_flow


def percentage(actual: Money, planned: Money) -> Decimal:
    denominator = as_decimal(planned)
    if denominator == 0:
        return Decimal("0")
    return (as_decimal(actual) / denominator * 100).quantize(Decimal("0.1"))


def calculate_plan_fact(planned: Money, actual: Money) -> dict[str, Decimal | bool]:
    plan = as_decimal(planned)
    fact = as_decimal(actual)
    remaining = plan - fact
    return {
        "planned": plan,
        "actual": fact,
        "remaining": remaining,
        "percent": percentage(fact, plan),
        "is_overrun": remaining < 0,
    }


def calculate_debt_repayment(initial_amount: Money, current_amount: Money) -> dict[str, Decimal | bool]:
    initial = max(Decimal("0"), as_decimal(initial_amount))
    current = max(Decimal("0"), as_decimal(current_amount))
    paid = max(Decimal("0"), initial - current)
    return {
        "initial": initial,
        "current": current,
        "paid": paid,
        "percent": percentage(paid, initial),
        "is_closed": current == 0,
    }


def calculate_debt_burden(monthly_payments: Money, income: Money) -> Decimal:
    income_value = as_decimal(income)
    if income_value == 0:
        return Decimal("0")
    return as_decimal(monthly_payments) / income_value * 100


def apply_debt_payment(current_amount: Money, payment: Money) -> dict[str, Decimal | bool]:
    """Cap a payment at the outstanding amount and never produce negative debt."""
    current = max(Decimal("0"), as_decimal(current_amount))
    requested = max(Decimal("0"), as_decimal(payment))
    applied = min(current, requested)
    remaining = current - applied
    return {
        "requested": requested,
        "applied": applied,
        "remaining": remaining,
        "is_closed": remaining == 0,
    }


def calculate_goal_progress(target_amount: Money, current_amount: Money) -> dict[str, Decimal | bool]:
    target = max(Decimal("0"), as_decimal(target_amount))
    current = max(Decimal("0"), as_decimal(current_amount))
    remaining = max(Decimal("0"), target - current)
    return {
        "target": target,
        "current": current,
        "remaining": remaining,
        "percent": percentage(current, target),
        "is_reached": target > 0 and current >= target,
    }


def calculate_financial_health(
    *,
    income: Money,
    expense: Money,
    debt_payments: Money = 0,
    goal_targets: Money = 0,
    has_active_goals: bool = False,
    has_budget: bool = False,
    has_budget_overrun: bool = False,
    has_startup_state: bool = False,
    has_debt_progress: bool = False,
) -> dict[str, object]:
    """Calculate the existing FinanceOS health score and its reasons."""
    income_value = as_decimal(income)
    expense_value = as_decimal(expense)
    debt_value = as_decimal(debt_payments)
    free_cashflow = income_value - expense_value - debt_value - as_decimal(goal_targets)
    score = 100
    pluses: list[str] = []
    minuses: list[str] = []

    if expense_value > income_value:
        score -= 30
        minuses.append("расходы выше доходов")
    burden = calculate_debt_burden(debt_value, income_value)
    if burden > 50:
        score -= 30
        minuses.append(f"долговая нагрузка {burden:.0f}%")
    elif burden >= 30:
        score -= 15
        minuses.append(f"долговая нагрузка {burden:.0f}%")
    if not has_active_goals:
        score -= 10
        minuses.append("нет активных целей")
    if not has_budget:
        score -= 10
        minuses.append("нет бюджетного плана")
    if has_budget_overrun:
        score -= 10
        minuses.append("есть перерасход по категориям")
    if free_cashflow < 0:
        score -= 20
        minuses.append("отрицательный свободный денежный поток")
    if has_startup_state:
        score += 5
        pluses.append("есть стартовое состояние")
    if has_active_goals:
        score += 5
        pluses.append("есть цели")
    if has_budget:
        score += 5
        pluses.append("есть бюджет")
    if has_debt_progress:
        score += 5
        pluses.append("долги уменьшаются")

    return {
        "score": max(0, min(100, score)),
        "pluses": pluses,
        "minuses": minuses,
        "burden": burden,
        "free_cashflow": free_cashflow,
    }


def normalize_category_name(name: object) -> str:
    return " ".join(str(name).strip().split())


def category_exists(categories: Iterable[object], candidate: object) -> bool:
    normalized = normalize_category_name(candidate).casefold()
    return bool(normalized) and any(
        normalize_category_name(category).casefold() == normalized for category in categories
    )


def calculate_operation_totals(operations: Iterable[Operation]) -> dict[str, Decimal]:
    rows = list(operations)
    income = calculate_income(rows)
    expense = calculate_expenses(rows)
    return {"income": income, "expense": expense, "balance": income - expense}


def calculate_daily_limit(
    operations: Iterable[Operation], daily_limit: Money | None, *, now: datetime | None = None
) -> dict[str, object]:
    """Calculate today's expense usage for an optional active daily limit."""
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    comparison_timezone = current.tzinfo
    limit = as_decimal(daily_limit) if daily_limit is not None else None
    spent = Decimal("0")
    for row in operations:
        if row.get("type") != "expense":
            continue
        try:
            created = datetime.fromisoformat(str(row.get("created_at", "")).replace("Z", "+00:00"))
        except (TypeError, ValueError):
            continue
        if created.tzinfo is None:
            created = created.replace(tzinfo=timezone.utc)
        if created.astimezone(comparison_timezone).date() == current.date():
            spent += as_decimal(row.get("amount"))
    if limit is None or limit <= 0:
        return {"is_set": False, "limit": Decimal("0"), "spent": spent,
                "remaining": Decimal("0"), "percent": Decimal("0"), "status": "unset"}
    percent = percentage(spent, limit)
    status = "ok" if percent < 80 else "warning" if percent <= 100 else "exceeded"
    return {"is_set": True, "limit": limit, "spent": spent,
            "remaining": limit - spent, "percent": percent, "status": status}


def calculate_daily_rollover(
    daily_limit: Money | None, spent_today: Money, *, now: datetime | None = None
) -> dict[str, object]:
    """Recommend a daily limit with today's unused amount spread over month remainder."""
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    limit = as_decimal(daily_limit) if daily_limit is not None else None
    spent = as_decimal(spent_today)
    if limit is None or limit <= 0:
        return {
            "is_set": False,
            "unused_today": Decimal("0"),
            "remaining_days": 0,
            "rollover_per_day": Decimal("0"),
            "recommended_daily_limit": Decimal("0"),
        }

    unused_today = limit - spent
    remaining_days = monthrange(current.year, current.month)[1] - current.day
    if unused_today <= 0 or remaining_days <= 0:
        return {
            "is_set": True,
            "unused_today": max(Decimal("0"), unused_today),
            "remaining_days": max(0, remaining_days),
            "rollover_per_day": Decimal("0"),
            "recommended_daily_limit": limit,
        }

    rollover_per_day = unused_today / Decimal(remaining_days)
    return {
        "is_set": True,
        "unused_today": unused_today,
        "remaining_days": remaining_days,
        "rollover_per_day": rollover_per_day,
        "recommended_daily_limit": limit + rollover_per_day,
    }


def parse_reminder_time(value: str) -> str | None:
    """Validate a Moscow reminder time and normalize it to HH:MM."""
    match = re.fullmatch(r"(\d{1,2}):(\d{2})", value.strip())
    if not match:
        return None
    hour, minute = map(int, match.groups())
    if hour > 23 or minute > 59:
        return None
    return f"{hour:02d}:{minute:02d}"


def resolve_daily_limit(monthly_limit: Money | None, override: Mapping[str, object] | None) -> dict[str, object]:
    """Resolve a day override before falling back to the monthly limit."""
    if override and override.get("status") == "disabled":
        return {"enabled": False, "limit": None, "source": "disabled"}
    if override and override.get("status") == "active":
        value = as_decimal(override.get("daily_limit"))
        if value > 0:
            return {"enabled": True, "limit": value, "source": "override"}
    if monthly_limit is not None and as_decimal(monthly_limit) > 0:
        return {"enabled": True, "limit": as_decimal(monthly_limit), "source": "monthly"}
    return {"enabled": False, "limit": None, "source": "unset"}

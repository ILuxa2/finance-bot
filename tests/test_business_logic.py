from decimal import Decimal

import pytest

from app.calculations import (
    apply_debt_payment,
    calculate_balance,
    calculate_debt_burden,
    calculate_debt_repayment,
    calculate_expenses,
    calculate_financial_health,
    calculate_goal_progress,
    calculate_income,
    calculate_plan_fact,
)


@pytest.mark.parametrize(
    ("planned", "actual", "remaining", "percent", "overrun"),
    [
        ("1000", "700", "300", "70.0", False),
        ("1000", "1000", "0", "100.0", False),
        ("1000", "1250", "-250", "125.0", True),
    ],
)
def test_plan_fact(planned, actual, remaining, percent, overrun):
    result = calculate_plan_fact(planned, actual)
    assert result["remaining"] == Decimal(remaining)
    assert result["percent"] == Decimal(percent)
    assert result["is_overrun"] is overrun


def test_debt_repayment_percent_and_closed_status():
    open_debt = calculate_debt_repayment("10000", "2500")
    closed_debt = calculate_debt_repayment("10000", "0")
    assert open_debt["percent"] == Decimal("75.0")
    assert open_debt["is_closed"] is False
    assert closed_debt["percent"] == Decimal("100.0")
    assert closed_debt["is_closed"] is True


def test_debt_burden():
    assert calculate_debt_burden("30000", "100000") == Decimal("30.0")


def test_payment_larger_than_debt_is_capped():
    result = apply_debt_payment("5000", "7000")
    assert result["applied"] == Decimal("5000")
    assert result["remaining"] == 0
    assert result["is_closed"] is True


def test_goal_progress_reached_and_remaining():
    active = calculate_goal_progress("100000", "40000")
    reached = calculate_goal_progress("100000", "100000")
    assert active["percent"] == Decimal("40.0")
    assert active["remaining"] == Decimal("60000")
    assert active["is_reached"] is False
    assert reached["remaining"] == 0
    assert reached["is_reached"] is True


def test_financial_health_good_state():
    health = calculate_financial_health(
        income=100000, expense=50000, debt_payments=10000, goal_targets=10000,
        has_active_goals=True, has_budget=True, has_startup_state=True,
        has_debt_progress=True,
    )
    assert health["score"] == 100
    assert health["minuses"] == []


def test_financial_health_expenses_above_income():
    health = calculate_financial_health(
        income=50000, expense=60000, has_active_goals=True, has_budget=True,
    )
    assert "расходы выше доходов" in health["minuses"]


def test_financial_health_high_debt_burden():
    health = calculate_financial_health(
        income=100000, expense=10000, debt_payments=60000,
        has_active_goals=True, has_budget=True,
    )
    assert health["burden"] == Decimal("60.0")
    assert any("долговая нагрузка" in reason for reason in health["minuses"])


def test_financial_health_negative_free_cashflow():
    health = calculate_financial_health(
        income=100000, expense=70000, debt_payments=20000, goal_targets=20000,
        has_active_goals=True, has_budget=True,
    )
    assert health["free_cashflow"] == Decimal("-10000")
    assert "отрицательный свободный денежный поток" in health["minuses"]


def balance_operations():
    return [
        {"type": "income", "amount": "5000"},
        {"type": "expense", "amount": "1200"},
    ]


def test_balance_is_income_minus_expenses():
    rows = balance_operations()
    assert calculate_income(rows) - calculate_expenses(rows) == Decimal("3800")


def test_balance_without_startup_state():
    assert calculate_balance(balance_operations()) == Decimal("3800")


def test_balance_with_startup_state():
    assert calculate_balance(balance_operations(), startup_balance="10000") == Decimal("13800")

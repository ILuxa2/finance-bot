from datetime import datetime, timezone

from app.reports import build_monthly_summary


NOW = datetime(2026, 6, 20, tzinfo=timezone.utc)


def operation(kind: str, amount: str, day: int, category: str = "") -> dict[str, str]:
    return {
        "created_at": f"2026-06-{day:02d}T10:00:00+00:00",
        "type": kind,
        "amount": amount,
        "category": category,
        "description": "test",
    }


def test_report_balance_is_income_minus_expense_without_startup_state():
    report = build_monthly_summary(
        [operation("income", "10000", 2), operation("expense", "2500", 3, "Еда")],
        now=NOW,
    )
    assert "Доходы: 10 000 ₽" in report
    assert "Расходы: 2 500 ₽" in report
    assert "Баланс: 7 500 ₽" in report


def test_report_balance_uses_startup_state_and_only_later_operations():
    startup = {
        "created_at": "2026-06-10T12:00:00+00:00",
        "total_balance": "50000",
        "month_income_so_far": "20000",
        "month_expense_so_far": "5000",
    }
    rows = [
        operation("income", "9000", 5),
        operation("income", "3000", 11),
        operation("expense", "1000", 12, "Еда"),
    ]
    report = build_monthly_summary(rows, startup, now=NOW)
    assert "Доходы: 23 000 ₽" in report
    assert "Расходы: 6 000 ₽" in report
    assert "Баланс: 52 000 ₽" in report


from datetime import datetime, timezone
from decimal import Decimal

from app.calculations import (
    calculate_daily_limit, calculate_daily_rollover, parse_reminder_time, resolve_daily_limit,
)
from app.sheets import SheetsRepository


NOW = datetime(2026, 7, 4, 12, tzinfo=timezone.utc)


def expense(amount: str, *, kind: str = "expense", day: int = 4):
    return {"created_at": f"2026-07-{day:02d}T10:00:00+00:00", "type": kind, "amount": amount}


def test_daily_limit_is_not_set():
    result = calculate_daily_limit([expense("100")], None, now=NOW)
    assert result["is_set"] is False
    assert result["status"] == "unset"


def test_daily_expenses_below_limit_ignore_income_and_transfer():
    result = calculate_daily_limit([
        expense("900"), expense("5000", kind="income"), expense("300", kind="transfer")
    ], "1500", now=NOW)
    assert result["spent"] == Decimal("900")
    assert result["remaining"] == Decimal("600")
    assert result["percent"] == Decimal("60.0")
    assert result["status"] == "ok"


def test_daily_expenses_between_80_and_100_percent():
    result = calculate_daily_limit([expense("1200")], "1500", now=NOW)
    assert result["percent"] == Decimal("80.0")
    assert result["status"] == "warning"


def test_daily_limit_exceeded():
    result = calculate_daily_limit([expense("1600")], "1500", now=NOW)
    assert result["remaining"] == Decimal("-100")
    assert result["status"] == "exceeded"


def test_deleted_daily_limit_is_not_considered():
    class Worksheet:
        @staticmethod
        def get_all_records():
            return [{"created_at": "2026-07-01T00:00:00+00:00",
                     "telegram_user_id": "42", "month": "2026-07",
                     "daily_limit": "1500", "status": "deleted"}]

    repository = object.__new__(SheetsRepository)
    repository._daily_limits_worksheet = Worksheet()
    assert repository.get_daily_limit(42, "2026-07") is None


def test_reminder_time_is_parsed_and_normalized():
    assert parse_reminder_time("09:00") == "09:00"
    assert parse_reminder_time("8:30") == "08:30"
    assert parse_reminder_time("23:15") == "23:15"


def test_invalid_reminder_time_is_rejected():
    for value in ("25:00", "12:99", "утром", "девять", "9:0"):
        assert parse_reminder_time(value) is None


def test_monthly_limit_without_override():
    assert resolve_daily_limit("1500", None) == {
        "enabled": True, "limit": Decimal("1500"), "source": "monthly"
    }


def test_active_daily_override_wins_over_monthly_limit():
    assert resolve_daily_limit("1500", {"daily_limit": "2000", "status": "active"}) == {
        "enabled": True, "limit": Decimal("2000"), "source": "override"
    }


def test_disabled_daily_override_disables_limit():
    assert resolve_daily_limit("1500", {"daily_limit": "", "status": "disabled"}) == {
        "enabled": False, "limit": None, "source": "disabled"
    }


def test_daily_rollover_spreads_unused_limit_over_remaining_month_days():
    result = calculate_daily_rollover(
        "1500", "1000", now=datetime(2026, 7, 21, 12, tzinfo=timezone.utc)
    )
    assert result["unused_today"] == Decimal("500")
    assert result["remaining_days"] == 10
    assert result["rollover_per_day"] == Decimal("50")
    assert result["recommended_daily_limit"] == Decimal("1550")


def test_daily_rollover_is_zero_when_limit_is_fully_used():
    result = calculate_daily_rollover("1500", "1500", now=NOW)
    assert result["unused_today"] == Decimal("0")
    assert result["rollover_per_day"] == Decimal("0")
    assert result["recommended_daily_limit"] == Decimal("1500")


def test_daily_rollover_is_zero_when_limit_is_exceeded():
    result = calculate_daily_rollover("1500", "2000", now=NOW)
    assert result["unused_today"] == Decimal("0")
    assert result["rollover_per_day"] == Decimal("0")
    assert result["recommended_daily_limit"] == Decimal("1500")


def test_daily_rollover_is_zero_on_last_month_day():
    result = calculate_daily_rollover(
        "1500", "1000", now=datetime(2026, 7, 31, 12, tzinfo=timezone.utc)
    )
    assert result["unused_today"] == Decimal("500")
    assert result["remaining_days"] == 0
    assert result["rollover_per_day"] == Decimal("0")
    assert result["recommended_daily_limit"] == Decimal("1500")


def test_daily_rollover_is_not_calculated_when_limit_is_disabled_today():
    effective = resolve_daily_limit("1500", {"daily_limit": "", "status": "disabled"})
    result = calculate_daily_rollover(effective["limit"], "1000", now=NOW)
    assert result["is_set"] is False
    assert result["unused_today"] == Decimal("0")
    assert result["rollover_per_day"] == Decimal("0")

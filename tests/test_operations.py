from decimal import Decimal

from app.calculations import calculate_expenses, calculate_income, calculate_operation_totals


OPERATIONS = [
    {"type": "income", "amount": "10000"},
    {"type": "income", "amount": "2500.50"},
    {"type": "expense", "amount": "3000"},
    {"type": "expense", "amount": "500.25"},
]


def test_income_total():
    assert calculate_income(OPERATIONS) == Decimal("12500.50")


def test_expense_total():
    assert calculate_expenses(OPERATIONS) == Decimal("3500.25")


def test_operation_totals_and_balance():
    assert calculate_operation_totals(OPERATIONS) == {
        "income": Decimal("12500.50"),
        "expense": Decimal("3500.25"),
        "balance": Decimal("9000.25"),
    }


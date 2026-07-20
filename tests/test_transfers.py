from decimal import Decimal

import pytest

from app.sheets import calculate_transfer_balances


STATE = {
    "cash_balance": "14000",
    "card_balance": "0",
    "savings_balance": "0",
    "total_balance": "14000",
}


def test_cash_to_card():
    result = calculate_transfer_balances(STATE, "cash", "card", Decimal("10000"))
    assert result["cash_balance"] == Decimal("4000")
    assert result["card_balance"] == Decimal("10000")


def test_card_to_savings():
    state = {**STATE, "cash_balance": "0", "card_balance": "12000"}
    result = calculate_transfer_balances(state, "card", "savings", Decimal("5000"))
    assert result["card_balance"] == Decimal("7000")
    assert result["savings_balance"] == Decimal("5000")


def test_insufficient_funds():
    with pytest.raises(ValueError, match="Insufficient funds"):
        calculate_transfer_balances(STATE, "card", "savings", Decimal("5000"))


def test_total_balance_does_not_change():
    result = calculate_transfer_balances(STATE, "cash", "card", Decimal("10000"))
    assert result["total_balance"] == Decimal("14000")

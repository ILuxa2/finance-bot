"""Google Sheets gateway for financial transactions and startup states."""

from datetime import datetime, timezone
from decimal import Decimal

import gspread

from app.config import Settings

ACCOUNT_FIELDS = {
    "cash": "cash_balance",
    "card": "card_balance",
    "savings": "savings_balance",
}
ACCOUNT_LABELS = {
    "cash": "Наличные",
    "card": "Карта",
    "savings": "Накопительный счёт",
}


def calculate_transfer_balances(
    state: dict[str, str | Decimal], from_account: str, to_account: str,
    amount: Decimal, *, reverse: bool = False,
) -> dict[str, Decimal]:
    """Return account balances after applying (or reversing) a transfer."""
    if from_account == to_account or from_account not in ACCOUNT_FIELDS or to_account not in ACCOUNT_FIELDS:
        raise ValueError("Invalid transfer accounts.")
    if amount <= 0:
        raise ValueError("Transfer amount must be positive.")
    result = {
        field: Decimal(str(state.get(field, "0")))
        for field in (*ACCOUNT_FIELDS.values(), "total_balance")
    }
    source, destination = ACCOUNT_FIELDS[from_account], ACCOUNT_FIELDS[to_account]
    if not reverse and result[source] < amount:
        raise ValueError("Insufficient funds.")
    result[source] += amount if reverse else -amount
    result[destination] += -amount if reverse else amount
    return result

HEADERS = [
    "created_at",
    "telegram_user_id",
    "type",
    "amount",
    "category",
    "description",
]

STARTUP_STATE_WORKSHEET = "StartupState"
STARTUP_STATE_HEADERS = [
    "created_at", "telegram_user_id", "cash_balance", "card_balance",
    "savings_balance", "total_balance", "month_income_so_far",
    "month_expense_so_far", "month_savings_so_far",
    "month_debt_paid_so_far", "financial_mode",
]
BUDGET_PLAN_WORKSHEET = "BudgetPlan"
BUDGET_PLAN_HEADERS = [
    "created_at", "telegram_user_id", "month", "category", "planned_amount",
]
DEBTS_WORKSHEET = "Debts"
DEBTS_HEADERS = [
    "created_at", "telegram_user_id", "debt_name", "initial_amount",
    "current_amount", "monthly_payment", "payment_day", "status",
]
DEBT_PAYMENTS_WORKSHEET = "DebtPayments"
DEBT_PAYMENTS_HEADERS = [
    "created_at", "telegram_user_id", "debt_name", "amount", "comment",
]
GOALS_WORKSHEET = "Goals"
GOALS_HEADERS = [
    "created_at", "telegram_user_id", "goal_name", "target_amount",
    "current_amount", "monthly_target", "status",
]
CATEGORIES_WORKSHEET = "Categories"
CATEGORIES_HEADERS = [
    "created_at", "telegram_user_id", "type", "category_name", "status",
    "is_favorite", "last_used_at",
]
DAILY_LIMITS_WORKSHEET = "DailyLimits"
DAILY_LIMITS_HEADERS = [
    "created_at", "telegram_user_id", "month", "daily_limit", "status",
]
USER_SETTINGS_WORKSHEET = "UserSettings"
USER_SETTINGS_HEADERS = [
    "created_at", "updated_at", "telegram_user_id",
    "daily_limit_reminder_enabled", "daily_limit_reminder_time", "timezone",
]
DAILY_LIMIT_OVERRIDES_WORKSHEET = "DailyLimitOverrides"
DAILY_LIMIT_OVERRIDES_HEADERS = [
    "created_at", "telegram_user_id", "date", "daily_limit", "status",
]
DEFAULT_CATEGORIES = {
    "expense": [
        "Еда", "Транспорт", "Дом", "Коммунальные", "Связь", "Здоровье",
        "Одежда", "Развлечения", "Подарки", "Подписки", "Образование",
        "Долги", "Накопления", "Прочее",
    ],
    "income": [
        "Зарплата", "Премия", "Подработка", "Проценты", "Возврат",
        "Подарок", "Прочее",
    ],
}


class SheetsRepository:
    """Persist user transactions in one Google Sheets worksheet."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._worksheet: gspread.Worksheet | None = None
        self._startup_state_worksheet: gspread.Worksheet | None = None
        self._budget_plan_worksheet: gspread.Worksheet | None = None
        self._debts_worksheet: gspread.Worksheet | None = None
        self._debt_payments_worksheet: gspread.Worksheet | None = None
        self._goals_worksheet: gspread.Worksheet | None = None
        self._categories_worksheet: gspread.Worksheet | None = None
        self._daily_limits_worksheet: gspread.Worksheet | None = None
        self._user_settings_worksheet: gspread.Worksheet | None = None
        self._daily_limit_overrides_worksheet: gspread.Worksheet | None = None

    def _get_client(self) -> gspread.Client:
        if self._settings.service_account_json is not None:
            return gspread.service_account_from_dict(self._settings.service_account_json)
        if self._settings.service_account_file is None:
            raise ValueError("Google service-account credentials are not configured.")
        return gspread.service_account(filename=str(self._settings.service_account_file))

    def _get_worksheet(self) -> gspread.Worksheet:
        if self._worksheet is not None:
            return self._worksheet

        client = self._get_client()
        spreadsheet = client.open_by_key(self._settings.spreadsheet_id)
        try:
            worksheet = spreadsheet.worksheet(self._settings.worksheet_name)
        except gspread.WorksheetNotFound:
            worksheet = spreadsheet.add_worksheet(
                title=self._settings.worksheet_name,
                rows=1000,
                cols=len(HEADERS),
            )

        self._ensure_headers(worksheet)
        self._worksheet = worksheet
        return worksheet

    def _get_startup_state_worksheet(self) -> gspread.Worksheet:
        if self._startup_state_worksheet is not None:
            return self._startup_state_worksheet
        client = self._get_client()
        spreadsheet = client.open_by_key(self._settings.spreadsheet_id)
        try:
            worksheet = spreadsheet.worksheet(STARTUP_STATE_WORKSHEET)
        except gspread.WorksheetNotFound:
            worksheet = spreadsheet.add_worksheet(
                title=STARTUP_STATE_WORKSHEET, rows=1000, cols=len(STARTUP_STATE_HEADERS)
            )
        self._ensure_exact_headers(worksheet, STARTUP_STATE_HEADERS)
        self._startup_state_worksheet = worksheet
        return worksheet

    def _get_budget_plan_worksheet(self) -> gspread.Worksheet:
        if self._budget_plan_worksheet is not None:
            return self._budget_plan_worksheet
        client = self._get_client()
        spreadsheet = client.open_by_key(self._settings.spreadsheet_id)
        try:
            worksheet = spreadsheet.worksheet(BUDGET_PLAN_WORKSHEET)
        except gspread.WorksheetNotFound:
            worksheet = spreadsheet.add_worksheet(
                title=BUDGET_PLAN_WORKSHEET, rows=1000, cols=len(BUDGET_PLAN_HEADERS)
            )
        self._ensure_exact_headers(worksheet, BUDGET_PLAN_HEADERS)
        self._budget_plan_worksheet = worksheet
        return worksheet

    def _get_debts_worksheet(self) -> gspread.Worksheet:
        if self._debts_worksheet is not None:
            return self._debts_worksheet
        worksheet = self._get_or_create_worksheet(DEBTS_WORKSHEET, DEBTS_HEADERS)
        self._debts_worksheet = worksheet
        return worksheet

    def _get_debt_payments_worksheet(self) -> gspread.Worksheet:
        if self._debt_payments_worksheet is not None:
            return self._debt_payments_worksheet
        worksheet = self._get_or_create_worksheet(DEBT_PAYMENTS_WORKSHEET, DEBT_PAYMENTS_HEADERS)
        self._debt_payments_worksheet = worksheet
        return worksheet

    def _get_goals_worksheet(self) -> gspread.Worksheet:
        if self._goals_worksheet is None:
            self._goals_worksheet = self._get_or_create_worksheet(GOALS_WORKSHEET, GOALS_HEADERS)
        return self._goals_worksheet

    def _get_categories_worksheet(self) -> gspread.Worksheet:
        if self._categories_worksheet is None:
            self._categories_worksheet = self._get_or_create_worksheet(
                CATEGORIES_WORKSHEET, CATEGORIES_HEADERS
            )
        return self._categories_worksheet

    def _get_daily_limits_worksheet(self) -> gspread.Worksheet:
        if self._daily_limits_worksheet is None:
            self._daily_limits_worksheet = self._get_or_create_worksheet(
                DAILY_LIMITS_WORKSHEET, DAILY_LIMITS_HEADERS
            )
        return self._daily_limits_worksheet

    def _get_user_settings_worksheet(self) -> gspread.Worksheet:
        if self._user_settings_worksheet is None:
            self._user_settings_worksheet = self._get_or_create_worksheet(
                USER_SETTINGS_WORKSHEET, USER_SETTINGS_HEADERS
            )
        return self._user_settings_worksheet

    def _get_daily_limit_overrides_worksheet(self) -> gspread.Worksheet:
        if self._daily_limit_overrides_worksheet is None:
            self._daily_limit_overrides_worksheet = self._get_or_create_worksheet(
                DAILY_LIMIT_OVERRIDES_WORKSHEET, DAILY_LIMIT_OVERRIDES_HEADERS
            )
        return self._daily_limit_overrides_worksheet

    def _get_or_create_worksheet(self, title: str, headers: list[str]) -> gspread.Worksheet:
        client = self._get_client()
        spreadsheet = client.open_by_key(self._settings.spreadsheet_id)
        try:
            worksheet = spreadsheet.worksheet(title)
        except gspread.WorksheetNotFound:
            worksheet = spreadsheet.add_worksheet(title=title, rows=1000, cols=len(headers))
        self._ensure_exact_headers(worksheet, headers)
        return worksheet

    @staticmethod
    def _ensure_headers(worksheet: gspread.Worksheet) -> None:
        existing_headers = worksheet.row_values(1)
        if not existing_headers:
            worksheet.append_row(HEADERS)
            return

        # Upgrade the initial V1 schema, which did not have a category column.
        if existing_headers == [
            "created_at",
            "telegram_user_id",
            "type",
            "amount",
            "description",
        ]:
            worksheet.insert_cols([["category"]], col=5)

    @staticmethod
    def _ensure_exact_headers(worksheet: gspread.Worksheet, headers: list[str]) -> None:
        existing_headers = worksheet.row_values(1)
        if not existing_headers:
            worksheet.append_row(headers)
        elif existing_headers != headers:
            raise ValueError(f"Worksheet {worksheet.title!r} has an unexpected header schema.")

    def add_transaction(
        self,
        user_id: int,
        transaction_type: str,
        amount: Decimal,
        category: str,
        description: str,
    ) -> None:
        self._get_worksheet().append_row(
            [
                datetime.now(timezone.utc).isoformat(),
                str(user_id),
                transaction_type,
                str(amount),
                category,
                description,
            ],
            value_input_option="USER_ENTERED",
        )

    def _update_account_balances(self, user_id: int, balances: dict[str, Decimal]) -> bool:
        worksheet = self._get_startup_state_worksheet()
        matching = [
            (number, row) for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) > 1 and str(row[1]) == str(user_id)
        ]
        if not matching:
            return False
        row_number, _ = max(matching, key=lambda item: item[1][0] if item[1] else "")
        for field in (*ACCOUNT_FIELDS.values(), "total_balance"):
            worksheet.update_cell(row_number, STARTUP_STATE_HEADERS.index(field) + 1, str(balances[field]))
        return True

    def add_transfer(
        self, user_id: int, from_account: str, to_account: str,
        amount: Decimal, comment: str,
    ) -> str:
        """Store a transfer and update account balances when startup state exists."""
        state = self.get_startup_state(user_id)
        balances = None
        if state is not None:
            balances = calculate_transfer_balances(state, from_account, to_account, amount)
            self._update_account_balances(user_id, balances)
        description = (
            f"Перевод: {ACCOUNT_LABELS[from_account]}  {ACCOUNT_LABELS[to_account]}. {comment}"
        )
        try:
            self.add_transaction(user_id, "transfer", amount, "transfer", description)
        except Exception:
            if state is not None:
                self._update_account_balances(user_id, {
                    field: Decimal(str(state.get(field, "0")))
                    for field in (*ACCOUNT_FIELDS.values(), "total_balance")
                })
            raise
        return "updated" if balances is not None else "recorded"

    @staticmethod
    def _transfer_accounts(description: str) -> tuple[str, str] | None:
        prefix = "Перевод: "
        if not description.startswith(prefix):
            return None
        route = description[len(prefix):].split(".", 1)[0]
        for source, source_label in ACCOUNT_LABELS.items():
            for destination, destination_label in ACCOUNT_LABELS.items():
                if route == f"{source_label}  {destination_label}":
                    return source, destination
        return None

    def ensure_default_categories(self, user_id: int) -> None:
        """Create defaults once, only when the user has no category rows at all."""
        worksheet = self._get_categories_worksheet()
        rows = worksheet.get_all_values()[1:]
        if any(len(row) > 1 and row[1] == str(user_id) for row in rows):
            return
        now = datetime.now(timezone.utc).isoformat()
        values = [
            [now, str(user_id), category_type, name, "active", "false", ""]
            for category_type, names in DEFAULT_CATEGORIES.items()
            for name in names
        ]
        if values:
            worksheet.append_rows(values, value_input_option="USER_ENTERED")

    def get_categories(
        self, user_id: int, category_type: str, *, active_only: bool = True
    ) -> list[dict[str, str]]:
        self.ensure_default_categories(user_id)
        result = []
        for row_number, row in enumerate(
            self._get_categories_worksheet().get_all_values()[1:], start=2
        ):
            if len(row) < len(CATEGORIES_HEADERS):
                row += [""] * (len(CATEGORIES_HEADERS) - len(row))
            item = dict(zip(CATEGORIES_HEADERS, row[:len(CATEGORIES_HEADERS)]))
            if item["telegram_user_id"] != str(user_id) or item["type"] != category_type:
                continue
            if active_only and item["status"] != "active":
                continue
            item["_row_number"] = str(row_number)
            result.append(item)
        return result

    def get_category_by_row(
        self, user_id: int, category_type: str, row_number: int,
        *, active_only: bool = True,
    ) -> dict[str, str] | None:
        return next((
            row for row in self.get_categories(user_id, category_type, active_only=active_only)
            if int(row["_row_number"]) == row_number
        ), None)

    def create_category(self, user_id: int, category_type: str, name: str) -> dict[str, str] | None:
        """Create an active category; return None for a case-insensitive duplicate."""
        normalized = name.strip()
        if not normalized:
            raise ValueError("Category name cannot be empty.")
        if any(row["category_name"].casefold() == normalized.casefold()
               for row in self.get_categories(user_id, category_type)):
            return None
        now = datetime.now(timezone.utc).isoformat()
        worksheet = self._get_categories_worksheet()
        worksheet.append_row(
            [now, str(user_id), category_type, normalized, "active", "false", ""],
            value_input_option="USER_ENTERED",
        )
        return self.get_categories(user_id, category_type)[-1]

    def set_category_favorite(
        self, user_id: int, category_type: str, row_number: int, favorite: bool
    ) -> bool:
        category = self.get_category_by_row(user_id, category_type, row_number)
        if category is None:
            return False
        column = CATEGORIES_HEADERS.index("is_favorite") + 1
        self._get_categories_worksheet().update_cell(row_number, column, str(favorite).lower())
        return True

    def touch_category(self, user_id: int, category_type: str, category_name: str) -> bool:
        matches = [row for row in self.get_categories(user_id, category_type)
                   if row["category_name"].casefold() == category_name.casefold()]
        if not matches:
            return False
        row = max(matches, key=lambda item: item["created_at"])
        column = CATEGORIES_HEADERS.index("last_used_at") + 1
        self._get_categories_worksheet().update_cell(
            int(row["_row_number"]), column, datetime.now(timezone.utc).isoformat()
        )
        return True

    def archive_category(self, user_id: int, category_type: str, category_name: str) -> bool:
        matches = [row for row in self.get_categories(user_id, category_type)
                   if row["category_name"].casefold() == category_name.casefold()]
        if not matches:
            return False
        row = max(matches, key=lambda item: item["created_at"])
        self._get_categories_worksheet().update_cell(
            int(row["_row_number"]), CATEGORIES_HEADERS.index("status") + 1, "archived"
        )
        return True

    def rename_category(
        self, user_id: int, category_type: str, old_name: str, new_name: str,
        *, rename_operations: bool = False,
    ) -> int | None:
        normalized = new_name.strip()
        if not normalized:
            raise ValueError("Category name cannot be empty.")
        active = self.get_categories(user_id, category_type)
        if any(row["category_name"].casefold() == normalized.casefold()
               and row["category_name"].casefold() != old_name.casefold() for row in active):
            return None
        matches = [row for row in active if row["category_name"].casefold() == old_name.casefold()]
        if not matches:
            return None
        row = max(matches, key=lambda item: item["created_at"])
        self._get_categories_worksheet().update_cell(
            int(row["_row_number"]), CATEGORIES_HEADERS.index("category_name") + 1, normalized
        )
        return self.move_operations_category(
            user_id, category_type, old_name, normalized
        ) if rename_operations else 0

    def move_operations_category(
        self, user_id: int, operation_type: str, old_category: str, new_category: str
    ) -> int:
        worksheet = self._get_worksheet()
        category_column = HEADERS.index("category") + 1
        matching = [
            number for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) >= len(HEADERS) and row[1] == str(user_id)
            and row[2] == operation_type and row[4].casefold() == old_category.casefold()
        ]
        for number in matching:
            worksheet.update_cell(number, category_column, new_category)
        self.touch_category(user_id, operation_type, new_category)
        return len(matching)

    def delete_operations_by_category(
        self, user_id: int, operation_type: str, category: str
    ) -> int:
        worksheet = self._get_worksheet()
        matching = [
            number for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) >= len(HEADERS) and row[1] == str(user_id)
            and row[2] == operation_type and row[4].casefold() == category.casefold()
        ]
        for number in reversed(matching):
            worksheet.delete_rows(number)
        return len(matching)

    def get_transactions(self, user_id: int) -> list[dict[str, str]]:
        return [
            row
            for row in self._get_worksheet().get_all_records()
            if str(row.get("telegram_user_id")) == str(user_id)
        ]

    def get_transactions_for_month(self, user_id: int, month: str) -> list[dict[str, str]]:
        """Return a user's operations created in ``YYYY-MM`` without altering history."""
        return [
            row for row in self.get_transactions(user_id)
            if str(row.get("created_at", "")).startswith(month)
        ]

    def get_recent_operations_with_row_numbers(
        self, user_id: int, month: str, limit: int = 10,
    ) -> list[dict[str, str]]:
        """Return a user's newest operations for a month with sheet row numbers."""
        rows = []
        for row_number, values in enumerate(self._get_worksheet().get_all_values()[1:], start=2):
            if (
                len(values) >= len(HEADERS)
                and str(values[1]) == str(user_id)
                and str(values[0]).startswith(month)
            ):
                row = dict(zip(HEADERS, values[:len(HEADERS)]))
                row["_row_number"] = str(row_number)
                rows.append(row)
        rows.sort(key=lambda row: str(row["created_at"]), reverse=True)
        return rows[:limit]

    def delete_operation(
        self, user_id: int, row_number: int, expected_created_at: str,
    ) -> bool:
        """Physically delete an operation after verifying its owner and identity."""
        worksheet = self._get_worksheet()
        values = worksheet.get_all_values()
        if row_number < 2 or row_number > len(values):
            return False
        row = values[row_number - 1]
        if (
            len(row) < len(HEADERS)
            or str(row[1]) != str(user_id)
            or str(row[0]) != expected_created_at
        ):
            return False
        original_state = None
        if row[2] == "transfer":
            accounts = self._transfer_accounts(row[5])
            state = self.get_startup_state(user_id)
            if accounts is not None and state is not None:
                original_state = {
                    field: Decimal(str(state.get(field, "0")))
                    for field in (*ACCOUNT_FIELDS.values(), "total_balance")
                }
                reversed_balances = calculate_transfer_balances(
                    state, accounts[0], accounts[1], Decimal(str(row[3])), reverse=True
                )
                self._update_account_balances(user_id, reversed_balances)
        try:
            worksheet.delete_rows(row_number)
        except Exception:
            if original_state is not None:
                self._update_account_balances(user_id, original_state)
            raise
        return True

    def get_startup_state(self, user_id: int) -> dict[str, str] | None:
        states = [
            row for row in self._get_startup_state_worksheet().get_all_records()
            if str(row.get("telegram_user_id")) == str(user_id)
        ]
        return max(states, key=lambda row: str(row.get("created_at", ""))) if states else None

    def replace_startup_state(self, user_id: int, state: dict[str, Decimal | str]) -> None:
        worksheet = self._get_startup_state_worksheet()
        rows_to_delete = [
            number for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) > 1 and str(row[1]) == str(user_id)
        ]
        for number in reversed(rows_to_delete):
            worksheet.delete_rows(number)
        worksheet.append_row([
            datetime.now(timezone.utc).isoformat(), str(user_id),
            str(state["cash_balance"]), str(state["card_balance"]),
            str(state["savings_balance"]), str(state["total_balance"]),
            str(state["month_income_so_far"]), str(state["month_expense_so_far"]),
            str(state["month_savings_so_far"]), str(state["month_debt_paid_so_far"]),
            str(state["financial_mode"]),
        ], value_input_option="USER_ENTERED")

    def update_financial_mode(self, user_id: int, financial_mode: str) -> bool:
        """Update the latest startup state for a user without replacing its balances."""
        worksheet = self._get_startup_state_worksheet()
        matching_rows = [
            (row_number, row)
            for row_number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) > 1 and str(row[1]) == str(user_id)
        ]
        if not matching_rows:
            return False

        row_number, _ = max(
            matching_rows,
            key=lambda item: item[1][0] if item[1] else "",
        )
        financial_mode_column = STARTUP_STATE_HEADERS.index("financial_mode") + 1
        worksheet.update_cell(row_number, financial_mode_column, financial_mode)
        return True

    def upsert_budget_plan(
        self, user_id: int, month: str, category: str, planned_amount: Decimal
    ) -> None:
        """Create or replace a user's category plan for a month."""
        worksheet = self._get_budget_plan_worksheet()
        rows_to_delete = [
            number for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) >= 4
            and str(row[1]) == str(user_id)
            and row[2] == month
            and row[3] == category
        ]
        for number in reversed(rows_to_delete):
            worksheet.delete_rows(number)
        worksheet.append_row([
            datetime.now(timezone.utc).isoformat(), str(user_id), month, category,
            str(planned_amount),
        ], value_input_option="USER_ENTERED")

    def get_budget_plans(self, user_id: int, month: str) -> list[dict[str, str]]:
        return [
            row for row in self._get_budget_plan_worksheet().get_all_records()
            if str(row.get("telegram_user_id")) == str(user_id)
            and str(row.get("month")) == month
        ]

    def delete_budget_plan(self, user_id: int, month: str, category: str) -> bool:
        worksheet = self._get_budget_plan_worksheet()
        rows_to_delete = [
            number for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) >= 4
            and str(row[1]) == str(user_id)
            and row[2] == month
            and row[3] == category
        ]
        for number in reversed(rows_to_delete):
            worksheet.delete_rows(number)
        return bool(rows_to_delete)

    def get_daily_limit(self, user_id: int, month: str) -> dict[str, str] | None:
        """Return the newest active daily limit for a user and month."""
        matches = [
            row for row in self._get_daily_limits_worksheet().get_all_records()
            if str(row.get("telegram_user_id")) == str(user_id)
            and str(row.get("month")) == month and str(row.get("status")) == "active"
        ]
        return max(matches, key=lambda row: str(row.get("created_at", ""))) if matches else None

    def upsert_daily_limit(self, user_id: int, month: str, daily_limit: Decimal) -> None:
        """Deactivate prior values and append the active value, preserving history."""
        worksheet = self._get_daily_limits_worksheet()
        status_column = DAILY_LIMITS_HEADERS.index("status") + 1
        for number, row in enumerate(worksheet.get_all_values()[1:], start=2):
            if len(row) >= len(DAILY_LIMITS_HEADERS) and row[1] == str(user_id) \
                    and row[2] == month and row[4] == "active":
                worksheet.update_cell(number, status_column, "deleted")
        worksheet.append_row([
            datetime.now(timezone.utc).isoformat(), str(user_id), month,
            str(daily_limit), "active",
        ], value_input_option="USER_ENTERED")

    def delete_daily_limit(self, user_id: int, month: str) -> bool:
        """Soft-delete every active value for the selected month."""
        worksheet = self._get_daily_limits_worksheet()
        status_column = DAILY_LIMITS_HEADERS.index("status") + 1
        matching = [
            number for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) >= len(DAILY_LIMITS_HEADERS) and row[1] == str(user_id)
            and row[2] == month and row[4] == "active"
        ]
        for number in matching:
            worksheet.update_cell(number, status_column, "deleted")
        return bool(matching)

    def get_user_settings(self, user_id: int) -> dict[str, str] | None:
        rows = [
            row for row in self._get_user_settings_worksheet().get_all_records()
            if str(row.get("telegram_user_id")) == str(user_id)
        ]
        return max(rows, key=lambda row: str(row.get("updated_at", ""))) if rows else None

    def get_reminder_settings(self) -> list[dict[str, str]]:
        """Return the latest enabled reminder setting for every user."""
        latest: dict[str, dict[str, str]] = {}
        for row in self._get_user_settings_worksheet().get_all_records():
            user_id = str(row.get("telegram_user_id", ""))
            if user_id and (user_id not in latest or str(row.get("updated_at", "")) >= str(latest[user_id].get("updated_at", ""))):
                latest[user_id] = row
        return [row for row in latest.values()
                if str(row.get("daily_limit_reminder_enabled", "")).lower() == "true"]

    def upsert_user_settings(
        self, user_id: int, *, reminder_enabled: bool, reminder_time: str = ""
    ) -> dict[str, str]:
        worksheet = self._get_user_settings_worksheet()
        existing = self.get_user_settings(user_id)
        now = datetime.now(timezone.utc).isoformat()
        created_at = str(existing.get("created_at", now)) if existing else now
        effective_time = reminder_time or (str(existing.get("daily_limit_reminder_time", "")) if existing else "")
        rows = [
            number for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
            if len(row) > 2 and row[2] == str(user_id)
        ]
        for number in reversed(rows):
            worksheet.delete_rows(number)
        worksheet.append_row([
            created_at, now, str(user_id), str(reminder_enabled).lower(),
            effective_time, "Europe/Moscow",
        ], value_input_option="USER_ENTERED")
        return {"created_at": created_at, "updated_at": now, "telegram_user_id": str(user_id),
                "daily_limit_reminder_enabled": str(reminder_enabled).lower(),
                "daily_limit_reminder_time": effective_time, "timezone": "Europe/Moscow"}

    def get_daily_limit_override(self, user_id: int, date: str) -> dict[str, str] | None:
        rows = [
            row for row in self._get_daily_limit_overrides_worksheet().get_all_records()
            if str(row.get("telegram_user_id")) == str(user_id) and str(row.get("date")) == date
        ]
        return max(rows, key=lambda row: str(row.get("created_at", ""))) if rows else None

    def set_daily_limit_override(
        self, user_id: int, date: str, daily_limit: Decimal | None, status: str
    ) -> None:
        if status not in {"active", "disabled"}:
            raise ValueError("Invalid daily limit override status.")
        worksheet = self._get_daily_limit_overrides_worksheet()
        worksheet.append_row([
            datetime.now(timezone.utc).isoformat(), str(user_id), date,
            str(daily_limit) if daily_limit is not None else "", status,
        ], value_input_option="USER_ENTERED")

    def add_debt(
        self, user_id: int, debt_name: str, initial_amount: Decimal,
        monthly_payment: Decimal, payment_day: int,
    ) -> None:
        self._get_debts_worksheet().append_row([
            datetime.now(timezone.utc).isoformat(), str(user_id), debt_name,
            str(initial_amount), str(initial_amount), str(monthly_payment),
            str(payment_day), "active",
        ], value_input_option="USER_ENTERED")

    def get_debts(self, user_id: int, *, active_only: bool = False) -> list[dict[str, str]]:
        debts = [
            row for row in self._get_debts_worksheet().get_all_records()
            if str(row.get("telegram_user_id")) == str(user_id)
        ]
        if active_only:
            debts = [row for row in debts if row.get("status") == "active"]
        return debts

    def get_debt(self, user_id: int, debt_name: str) -> dict[str, str] | None:
        matching = [
            row for row in self.get_debts(user_id)
            if str(row.get("debt_name", "")).casefold() == debt_name.casefold()
        ]
        return max(matching, key=lambda row: str(row.get("created_at", ""))) if matching else None

    def add_debt_payment(
        self, user_id: int, debt_name: str, amount: Decimal, comment: str,
    ) -> dict[str, str] | None:
        """Save a payment and update the matching debt balance.

        The caller validates that the debt is active before invoking this method.
        """
        worksheet = self._get_debts_worksheet()
        values = worksheet.get_all_values()
        matching = [
            (row_number, row) for row_number, row in enumerate(values[1:], start=2)
            if len(row) >= len(DEBTS_HEADERS)
            and str(row[1]) == str(user_id)
            and row[2].casefold() == debt_name.casefold()
        ]
        if not matching:
            return None
        row_number, row = max(matching, key=lambda item: item[1][0])
        if row[7] != "active":
            return None
        current_amount = Decimal(row[4])
        # A debt cannot be paid down below zero.  Persist the amount actually
        # applied so DebtPayments and Operations can always be reconciled.
        applied_amount = min(amount, current_amount)
        remaining = current_amount - applied_amount
        status = "closed" if remaining == 0 else "active"
        self._get_debt_payments_worksheet().append_row([
            datetime.now(timezone.utc).isoformat(), str(user_id), row[2], str(applied_amount), comment,
        ], value_input_option="USER_ENTERED")
        worksheet.update(f"E{row_number}:H{row_number}", [[
            str(remaining), row[5], row[6], status,
        ]], value_input_option="USER_ENTERED")
        updated = dict(zip(DEBTS_HEADERS, [*row[:4], str(remaining), row[5], row[6], status]))
        updated["applied_amount"] = str(applied_amount)
        updated["overpayment"] = str(amount - applied_amount)
        return updated

    def get_debt_payments(self, user_id: int, debt_name: str | None = None) -> list[dict[str, str]]:
        payments = [
            row for row in self._get_debt_payments_worksheet().get_all_records()
            if str(row.get("telegram_user_id")) == str(user_id)
        ]
        if debt_name is not None:
            payments = [
                row for row in payments
                if str(row.get("debt_name", "")).casefold() == debt_name.casefold()
            ]
        return payments

    def close_debt(self, user_id: int, debt_name: str) -> str:
        """Close a debt by name, returning ``missing``, ``closed`` or ``updated``."""
        worksheet = self._get_debts_worksheet()
        values = worksheet.get_all_values()
        matching = [
            (row_number, row) for row_number, row in enumerate(values[1:], start=2)
            if len(row) >= len(DEBTS_HEADERS)
            and str(row[1]) == str(user_id)
            and row[2].casefold() == debt_name.casefold()
        ]
        if not matching:
            return "missing"
        row_number, row = max(matching, key=lambda item: item[1][0])
        if row[7] == "closed":
            return "closed"
        worksheet.update_acell(f"H{row_number}", "closed")
        return "updated"

    def add_goal(self, user_id: int, name: str, target: Decimal, current: Decimal, monthly: Decimal) -> None:
        status = "completed" if current >= target else "active"
        self._get_goals_worksheet().append_row([
            datetime.now(timezone.utc).isoformat(), str(user_id), name, str(target),
            str(current), str(monthly), status,
        ], value_input_option="USER_ENTERED")

    def get_goals(self, user_id: int, *, active_only: bool = False) -> list[dict[str, str]]:
        goals = [row for row in self._get_goals_worksheet().get_all_records()
                 if str(row.get("telegram_user_id")) == str(user_id)]
        return [row for row in goals if row.get("status") == "active"] if active_only else goals

    def get_goal(self, user_id: int, name: str) -> dict[str, str] | None:
        matches = [row for row in self.get_goals(user_id)
                   if str(row.get("goal_name", "")).casefold() == name.casefold()]
        return max(matches, key=lambda row: str(row.get("created_at", ""))) if matches else None

    def fund_goal(self, user_id: int, name: str, amount: Decimal) -> dict[str, str] | None:
        worksheet = self._get_goals_worksheet()
        matches = [(number, row) for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
                   if len(row) >= len(GOALS_HEADERS) and row[1] == str(user_id) and row[2].casefold() == name.casefold()]
        if not matches:
            return None
        number, row = max(matches, key=lambda item: item[1][0])
        if row[6] != "active":
            return None
        current = Decimal(row[4]) + amount
        status = "completed" if current >= Decimal(row[3]) else "active"
        worksheet.update(f"E{number}:G{number}", [[str(current), row[5], status]], value_input_option="USER_ENTERED")
        return dict(zip(GOALS_HEADERS, [*row[:4], str(current), row[5], status]))

    def close_goal(self, user_id: int, name: str) -> str:
        worksheet = self._get_goals_worksheet()
        matches = [(number, row) for number, row in enumerate(worksheet.get_all_values()[1:], start=2)
                   if len(row) >= len(GOALS_HEADERS) and row[1] == str(user_id) and row[2].casefold() == name.casefold()]
        if not matches:
            return "missing"
        number, row = max(matches, key=lambda item: item[1][0])
        if row[6] == "completed":
            return "completed"
        worksheet.update_acell(f"G{number}", "completed")
        return "updated"

# Схема существующей таблицы FinanceOS

Считаны только названия вкладок и колонок из экспортированной копии. Исходная таблица не менялась. Это контракт совместимости новой Java-версии с нынешними данными.

| Лист | Колонки |
| --- | --- |
| Transactions | created_at, telegram_user_id, type, amount, category, description |
| StartupState | created_at, telegram_user_id, cash_balance, card_balance, savings_balance, total_balance, month_income_so_far, month_expense_so_far, month_savings_so_far, month_debt_paid_so_far, financial_mode |
| BudgetPlan | created_at, telegram_user_id, month, category, planned_amount |
| Debts | created_at, telegram_user_id, debt_name, initial_amount, current_amount, monthly_payment, payment_day, status |
| DebtPayments | created_at, telegram_user_id, debt_name, amount, comment |
| Goals | created_at, telegram_user_id, goal_name, target_amount, current_amount, monthly_target, status |
| Categories | created_at, telegram_user_id, type, category_name, status, is_favorite, last_used_at |
| DailyLimits | created_at, telegram_user_id, month, daily_limit, status |
| UserSettings | created_at, updated_at, telegram_user_id, daily_limit_reminder_enabled, daily_limit_reminder_time, timezone |
| DailyLimitOverrides | created_at, telegram_user_id, date, daily_limit, status |

`Sheet1` пуст. Название `telegram_user_id` — часть формата данных, а не требование использовать Telegram. Новый VK-бот должен явно связывать VK-пользователя с прежним финансовым идентификатором.

Пока не подтверждены значения полей `type`, `status`, форматы дат и правила обновления записей. До этой проверки адаптер остаётся только для чтения.

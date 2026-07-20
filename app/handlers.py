"""Telegram handlers for the financial bot."""

import asyncio
import logging
from pathlib import Path
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from decimal import Decimal, InvalidOperation

from types import SimpleNamespace

from telegram import InlineKeyboardButton, InlineKeyboardMarkup, ReplyKeyboardMarkup, ReplyKeyboardRemove, Update
from telegram.ext import ContextTypes

from app.reports import (
    build_budget_plan_report, build_budget_report_block, build_debt_report_block,
    build_monthly_summary, build_forecast_report, build_health_report, calculate_health,
    financial_metrics, goal_forecast_date, build_operations_history,
    build_business_insights, build_accounts_report_block, build_daily_limit_message,
    build_daily_limit_report_block,
)
from app.charts import (
    build_cashflow_chart, build_debts_chart, build_expenses_chart, build_goals_chart,
    build_plan_chart,
)
from app.sheets import ACCOUNT_LABELS, SheetsRepository
from app.calculations import calculate_daily_limit, parse_reminder_time, resolve_daily_limit

logger = logging.getLogger(__name__)

# Inline-разделы заменяют старую постоянную клавиатуру, но текстовые команды
# и обработчики её прежних кнопок остаются совместимыми.
MENU = ReplyKeyboardRemove()
CONFIRMATION_KEYBOARD = ReplyKeyboardMarkup(
    [["Подтвердить", "Изменить", "Отменить"]], resize_keyboard=True, one_time_keyboard=True
)
PENDING_TYPE = "pending_transaction_type"
PENDING_CATEGORY = "pending_transaction_category"
PENDING_TRANSACTION_STEP = "pending_transaction_step"
PENDING_CATEGORY_ACTION = "pending_category_action"
PENDING_BUDGET_ACTION = "pending_budget_action"
PENDING_DEBT_ACTION = "pending_debt_action"
PENDING_GOAL_ACTION = "pending_goal_action"
PENDING_DELETE_OPERATION = "pending_delete_operation"
PENDING_TRANSFER = "pending_transfer"
PENDING_DAILY_LIMIT = "pending_daily_limit"
PENDING_DAILY_OVERRIDE = "pending_daily_override"
PENDING_REMINDER_TIME = "pending_reminder_time"
MOSCOW_TZ = ZoneInfo("Europe/Moscow")
SETUP_STATE = "startup_state_draft"
SETUP_FIELDS = (
    ("cash_balance", "Введите баланс наличных:"),
    ("card_balance", "Введите баланс на банковских картах:"),
    ("savings_balance", "Введите баланс на накопительных счетах:"),
    ("month_income_so_far", "Введите уже полученные доходы в текущем месяце:"),
    ("month_expense_so_far", "Введите уже потраченные расходы в текущем месяце:"),
    ("month_savings_so_far", "Введите уже внесённое в накопления в текущем месяце:"),
    ("month_debt_paid_so_far", "Введите уже погашенные долги в текущем месяце:"),
)
FINANCIAL_MODES = {"обычный", "накопление", "долги"}
FINANCIAL_MODE_LABELS = {
    "обычный": "Обычный",
    "накопление": "Накопление",
    "долги": "Закрытие долгов",
}


def _inline_menu(rows: list[list[tuple[str, str]]]) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup([
        [InlineKeyboardButton(text, callback_data=callback) for text, callback in row]
        for row in rows
    ])


MAIN_MENU = _inline_menu([
    [("➕ Добавить", "menu:add"), ("📊 Отчёт", "action:report")],
    [("📌 Бюджет", "menu:budget"), ("💳 Долги", "menu:debts")],
    [("🎯 Цели", "menu:goals"), ("📈 Аналитика", "menu:analytics")],
    [("⚙️ Настройки", "menu:settings")],
])

REPORT_MENU = _inline_menu([
    [("📜 Все операции", "action:operations")],
    [("💸 Расходы", "action:expenses"), ("💰 Доходы", "action:incomes")],
    [("📊 План / факт", "action:plan"), ("📈 Прогноз", "action:forecast")],
    [("💚 Здоровье", "action:health"), ("💳 Долги", "menu:debts")],
    [("🎯 Цели", "menu:goals")],
    [("📊 Диаграммы", "action:charts")],
])


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text(
        "🏠 Главное меню\n\nВыберите раздел:", reply_markup=MAIN_MENU,
    )


async def add_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("➕ Добавить операцию", reply_markup=_inline_menu([
        [("💰 Доход", "action:income"), ("💸 Расход", "action:expense")],
        [("🔁 Перевод между счетами", "action:transfer")],
        [("💳 Платёж по долгу", "action:pay_debt")],
        [("🎯 Пополнение цели", "action:fund_goal")],
        [("🗑 Удалить операцию", "action:delete_operation")],
    ]))


async def budget_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("📌 Бюджет", reply_markup=_inline_menu([
        [("📊 План / факт", "action:plan")],
        [("➕ Установить план", "action:set_plan")],
        [("🗑 Удалить план", "action:delete_plan")],
        [("📆 Дневной лимит", "action:daily_limit")],
        [("🌅 Утренний лимит", "action:daily_limit_reminder")],
        [("➕ Установить дневной лимит", "action:set_daily_limit")],
        [("🗑 Удалить дневной лимит", "action:delete_daily_limit")],
    ]))


async def debt_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("💳 Долги", reply_markup=_inline_menu([
        [("💳 Список долгов", "action:debts")],
        [("➕ Добавить долг", "action:add_debt"), ("💸 Платёж по долгу", "action:pay_debt")],
        [("✅ Закрыть с оплатой", "action:close_debt"), ("🔎 Карточка долга", "action:debt")],
        [("✏️ Закрыть вручную", "action:delete_debt")],
    ]))


async def goal_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("🎯 Цели", reply_markup=_inline_menu([
        [("🎯 Список целей", "action:goals")],
        [("➕ Добавить цель", "action:add_goal"), ("💰 Пополнить цель", "action:fund_goal")],
        [("🔎 Карточка цели", "action:goal"), ("✅ Закрыть цель", "action:delete_goal")],
    ]))


async def analytics_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("📈 Аналитика", reply_markup=_inline_menu([
        [("📈 Прогноз", "action:forecast")],
        [("🕊 Долговая свобода", "action:freedom")],
        [("💚 Финансовое здоровье", "action:health")],
        [("📊 Диаграммы", "action:charts")],
        [("🧠 Бизнес-выводы", "action:insights")],
        [("📜 История операций", "action:operations")],
    ]))


async def charts(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("📊 Диаграммы", reply_markup=_inline_menu([
        [("📊 Расходы по категориям", "action:chart_expenses")],
        [("📈 Доходы / расходы по дням", "action:chart_cashflow")],
        [("📌 План / факт", "action:chart_plan")],
        [("💳 Долги", "action:chart_debts")],
        [("🎯 Цели", "action:chart_goals")],
    ]))


async def settings_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("⚙️ Настройки", reply_markup=_inline_menu([
        [("🧾 Стартовое состояние", "action:setup_current_state")],
        [("Финансовый режим", "action:financial_mode")],
        [("📂 Категории", "category:manage")],
        [("📆 Дневной лимит", "action:daily_limit")],
        [("🌅 Утренний лимит", "action:daily_limit_reminder")],
        [("🔄 Новый месяц", "action:new_month")],
        [("🧹 Очистить чат", "action:clear")],
        [("Отменить ввод", "action:cancel")],
    ]))


async def financial_mode_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """Show the active financial mode and allow changing it."""
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        state = await asyncio.to_thread(repository.get_startup_state, update.effective_user.id)
    except Exception:
        logger.exception("Failed to load financial mode for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось получить финансовый режим. Попробуйте ещё раз.")
        return

    if state is None:
        await update.message.reply_text(
            "Стартовое состояние ещё не настроено. Сначала выполните /setup_current_state"
        )
        return

    current_mode = str(state.get("financial_mode", ""))
    current_label = current_mode if current_mode in FINANCIAL_MODES else current_mode or "не задан"
    await update.message.reply_text(
        f"Финансовый режим: {current_label}",
        reply_markup=_inline_menu([
            [("Обычный", "action:financial_mode:обычный")],
            [("Накопление", "action:financial_mode:накопление")],
            [("Закрытие долгов", "action:financial_mode:долги")],
        ]),
    )


async def set_financial_mode(
    update: Update, context: ContextTypes.DEFAULT_TYPE, mode: str
) -> None:
    """Update the configured user's financial mode."""
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        updated = await asyncio.to_thread(
            repository.update_financial_mode, update.effective_user.id, mode
        )
    except Exception:
        logger.exception("Failed to update financial mode for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось обновить финансовый режим. Попробуйте ещё раз.")
        return

    if not updated:
        await update.message.reply_text(
            "Стартовое состояние ещё не настроено. Сначала выполните /setup_current_state"
        )
        return

    await update.message.reply_text(
        f"Финансовый режим обновлён: {FINANCIAL_MODE_LABELS[mode]}."
    )


async def begin_income(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await _request_transaction(update, context, "income")


async def begin_expense(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await _request_transaction(update, context, "expense")


def _account_keyboard(prefix: str, *, exclude: str | None = None) -> InlineKeyboardMarkup:
    accounts = (("Наличные", "cash"), ("Карта", "card"), ("Накопительный счёт", "savings"))
    return _inline_menu([[(label, f"transfer:{prefix}:{key}")] for label, key in accounts if key != exclude])


async def begin_transfer(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_TRANSFER] = {"step": "from"}
    await update.message.reply_text("Откуда перевод?", reply_markup=_account_keyboard("from"))


async def transfer_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    if query is None or query.message is None:
        return
    parts = (query.data or "").split(":")
    draft = context.user_data.get(PENDING_TRANSFER)
    if len(parts) != 3 or draft is None or parts[2] not in {"cash", "card", "savings"}:
        await query.message.reply_text("Сценарий перевода истёк. Запустите /transfer ещё раз.")
        return
    if parts[1] == "from" and draft.get("step") == "from":
        draft.update({"from_account": parts[2], "step": "to"})
        await query.message.reply_text("Куда перевод?", reply_markup=_account_keyboard("to", exclude=parts[2]))
        return
    if parts[1] == "to" and draft.get("step") == "to":
        if parts[2] == draft.get("from_account"):
            await query.message.reply_text("Счета отправления и получения не должны совпадать.")
            return
        draft.update({"to_account": parts[2], "step": "amount"})
        await query.message.reply_text("Введите сумму перевода.")


async def begin_set_plan(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_BUDGET_ACTION] = "set"
    await update.message.reply_text("Введите план в формате: категория сумма\nПример: продукты 15000")


async def begin_delete_plan(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_BUDGET_ACTION] = "delete"
    await update.message.reply_text("Введите категорию плана.\nПример: продукты")


async def begin_add_debt(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_debt_action(context, "add")
    await update.message.reply_text(
        "Введите долг в формате: название остаток_долга ежемесячный_платёж день_платежа\n"
        "Пример: кредит 180000 25000 10"
    )


async def debts(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        user_debts = await asyncio.to_thread(repository.get_debts, update.effective_user.id)
    except Exception:
        logger.exception("Failed to load debts for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось получить список долгов. Попробуйте ещё раз.")
        return
    if not user_debts:
        await update.message.reply_text("Долгов нет.", reply_markup=MENU)
        return
    total_remaining = Decimal("0")
    total_monthly = Decimal("0")
    lines = ["💳 Долги", "──────────"]
    for debt in user_debts:
        initial = _debt_decimal(debt, "initial_amount")
        current = _debt_decimal(debt, "current_amount")
        monthly = _debt_decimal(debt, "monthly_payment")
        paid = max(Decimal("0"), initial - current)
        progress = (paid / initial * 100).quantize(Decimal("0.1")) if initial else Decimal("0")
        if debt.get("status") == "active":
            total_remaining += current
            total_monthly += monthly
        lines.extend([
            "", f"💳 {debt.get('debt_name', '')}",
            f"Сумма: {_format_amount(initial)}",
            f"Остаток: {_format_amount(current)}",
            f"Ежемесячный платёж: {_format_amount(monthly)}",
            f"День платежа: {debt.get('payment_day', '')}",
            f"Погашено: {_format_amount(paid)}",
            f"Статус: {debt.get('status', '')}",
            f"Прогресс: {progress:.1f}%",
        ])
    lines.extend(["", "──────────", f"Остаток долгов: {_format_amount(total_remaining)}", f"Платежи в месяц: {_format_amount(total_monthly)}"])
    await update.message.reply_text("\n".join(lines), reply_markup=MENU)


async def begin_pay_debt(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_debt_action(context, "pay")
    await update.message.reply_text(
        "💸 Платёж по долгу\n\nВведите: название сумма комментарий\nПример: кредит 25000 июньский платёж"
    )


async def begin_close_debt(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_debt_action(context, "close_pay")
    await update.message.reply_text(
        "✅ Закрытие долга с оплатой\n\nВведите: название комментарий\nПример: кредит досрочное погашение"
    )


async def begin_debt(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_debt_action(context, "detail")
    await update.message.reply_text("Введите название долга.\nПример: кредит")


async def begin_delete_debt(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_debt_action(context, "delete")
    await update.message.reply_text("Введите название долга.\nПример: кредит")


def _begin_debt_action(context: ContextTypes.DEFAULT_TYPE, action: str) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_DEBT_ACTION] = action


async def _request_transaction(update: Update, context: ContextTypes.DEFAULT_TYPE, transaction_type: str) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_TYPE] = transaction_type
    await _show_category_picker(update, context, transaction_type)


async def _show_category_picker(
    update: Update, context: ContextTypes.DEFAULT_TYPE, transaction_type: str
) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        categories = await asyncio.to_thread(
            repository.get_categories, update.effective_user.id, transaction_type
        )
    except Exception:
        logger.exception("Failed to load categories for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось загрузить категории.")
        return
    favorites = [c for c in categories if str(c["is_favorite"]).lower() == "true"]
    recent = sorted(
        [c for c in categories if c["last_used_at"] and c not in favorites],
        key=lambda c: c["last_used_at"], reverse=True,
    )[:5]
    remaining = [c for c in categories if c not in favorites and c not in recent]
    rows: list[list[tuple[str, str]]] = []
    for heading, items in (("⭐ Избранные", favorites), ("🕒 Недавно использованные", recent),
                           ("📂 Остальные", remaining)):
        if items:
            rows.append([(heading, "category:noop")])
            rows.extend([[(c["category_name"], f"category:select:{transaction_type}:{c['_row_number']}")]
                         for c in items])
    rows.append([("➕ Создать новую категорию", f"category:create:{transaction_type}")])
    title = "дохода" if transaction_type == "income" else "расхода"
    await update.message.reply_text(
        f"{'💰' if transaction_type == 'income' else '💸'} Выберите категорию {title}",
        reply_markup=_inline_menu(rows),
    )


async def categories_menu(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("📂 Категории", reply_markup=_inline_menu([
        [("💰 Категории доходов", "category:list:income")],
        [("💸 Категории расходов", "category:list:expense")],
    ]))


async def _show_managed_categories(update: Update, context: ContextTypes.DEFAULT_TYPE,
                                   category_type: str) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    categories = await asyncio.to_thread(
        repository.get_categories, update.effective_user.id, category_type
    )
    rows = [[(
        ("⭐ " if str(c["is_favorite"]).lower() == "true" else "") + c["category_name"],
        f"category:item:{category_type}:{c['_row_number']}",
    )] for c in categories]
    rows.append([("➕ Создать категорию", f"category:managecreate:{category_type}")])
    await update.message.reply_text(
        "💰 Категории доходов" if category_type == "income" else "💸 Категории расходов",
        reply_markup=_inline_menu(rows),
    )


async def process_category_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    action = context.user_data.get(PENDING_CATEGORY_ACTION)
    if not action:
        return False
    repository: SheetsRepository = context.bot_data["repository"]
    user_id = update.effective_user.id
    text = (update.message.text or "").strip()
    kind = action["type"]
    if action["action"] in {"create", "managecreate"}:
        try:
            category = await asyncio.to_thread(repository.create_category, user_id, kind, text)
        except ValueError:
            await update.message.reply_text("Название категории не может быть пустым.")
            return True
        if category is None:
            await update.message.reply_text("⚠️ Такая категория уже существует.")
            return True
        context.user_data.pop(PENDING_CATEGORY_ACTION, None)
        if action["action"] == "create":
            context.user_data[PENDING_TYPE] = kind
            context.user_data[PENDING_CATEGORY] = category["category_name"]
            context.user_data[PENDING_TRANSACTION_STEP] = "amount"
            await update.message.reply_text("Категория создана. Введите сумму.")
        else:
            await update.message.reply_text("Категория создана.")
            await _show_managed_categories(update, context, kind)
        return True
    if action["action"] == "rename":
        if not text:
            await update.message.reply_text("Название категории не может быть пустым.")
            return True
        active = await asyncio.to_thread(repository.get_categories, user_id, kind)
        if any(c["category_name"].casefold() == text.casefold()
               and c["category_name"].casefold() != action["old_name"].casefold() for c in active):
            await update.message.reply_text("⚠️ Такая категория уже существует.")
            return True
        action["new_name"] = text
        action["action"] = "rename_confirm"
        await update.message.reply_text("Что сделать со старыми операциями?", reply_markup=_inline_menu([
            [("🔁 Переименовать категорию и операции", "category:renameops")],
            [("🏷 Переименовать только категорию", "category:renameonly")],
            [("Отмена", "category:cancel")],
        ]))
        return True
    return False


async def begin_setup_current_state(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        existing = await asyncio.to_thread(repository.get_startup_state, update.effective_user.id)
    except Exception:
        logger.exception("Failed to check startup state for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось проверить стартовое состояние. Попробуйте позже.")
        return
    _clear_pending_actions(context)
    context.user_data[SETUP_STATE] = {"step": 0}
    if existing:
        await update.message.reply_text(
            "Стартовое состояние уже сохранено. После подтверждения оно будет заменено новым."
        )
    await update.message.reply_text(SETUP_FIELDS[0][1])


async def cancel(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    cancelled = context.user_data.pop(SETUP_STATE, None) is not None
    cancelled = context.user_data.pop(PENDING_TYPE, None) is not None or cancelled
    cancelled = context.user_data.pop(PENDING_BUDGET_ACTION, None) is not None or cancelled
    cancelled = context.user_data.pop(PENDING_DEBT_ACTION, None) is not None or cancelled
    cancelled = context.user_data.pop(PENDING_GOAL_ACTION, None) is not None or cancelled
    cancelled = context.user_data.pop(PENDING_CATEGORY_ACTION, None) is not None or cancelled
    cancelled = context.user_data.pop(PENDING_TRANSFER, None) is not None or cancelled
    cancelled = context.user_data.pop(PENDING_DAILY_LIMIT, None) is not None or cancelled
    cancelled = context.user_data.pop(PENDING_DAILY_OVERRIDE, None) is not None or cancelled
    cancelled = context.user_data.pop(PENDING_REMINDER_TIME, None) is not None or cancelled
    context.user_data.pop(PENDING_CATEGORY, None)
    context.user_data.pop(PENDING_TRANSACTION_STEP, None)
    await update.message.reply_text(
        "Настройка отменена." if cancelled else "Нет активной настройки для отмены.", reply_markup=MENU
    )


async def process_setup_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    draft = context.user_data.get(SETUP_STATE)
    if draft is None:
        return False
    text = (update.message.text or "").strip()
    step = draft["step"]
    if step == "reminder":
        normalized = None if text.casefold() == "нет" else parse_reminder_time(text)
        if text.casefold() != "нет" and normalized is None:
            await update.message.reply_text("❗ Введите время в формате HH:MM, например 09:00, или «нет»")
            return True
        repository: SheetsRepository = context.bot_data["repository"]
        try:
            settings = await asyncio.to_thread(
                repository.upsert_user_settings, update.effective_user.id,
                reminder_enabled=normalized is not None, reminder_time=normalized or "",
            )
            await _apply_reminder_schedule(context, update.effective_user.id, settings)
        except Exception:
            logger.exception("Failed to save initial reminder settings")
            await update.message.reply_text("Не удалось сохранить настройку напоминания.")
            return True
        context.user_data.pop(SETUP_STATE, None)
        message = (f"Стартовое состояние сохранено. Напоминание включено на {normalized} МСК."
                   if normalized else "Стартовое состояние сохранено. Напоминание выключено.")
        await update.message.reply_text(message, reply_markup=MENU)
        return True
    if step < len(SETUP_FIELDS):
        value = _parse_number(text)
        if value is None:
            await update.message.reply_text("❗ Не понял сумму.\nВведите число, например: 1500")
            return True
        draft[SETUP_FIELDS[step][0]] = value
        draft["step"] = step + 1
        if draft["step"] < len(SETUP_FIELDS):
            await update.message.reply_text(SETUP_FIELDS[draft["step"]][1])
        else:
            await update.message.reply_text(
                "Выберите финансовый режим: обычный, накопление или долги."
            )
        return True
    if step == len(SETUP_FIELDS):
        mode = text.lower()
        if mode not in FINANCIAL_MODES:
            await update.message.reply_text("Допустимые режимы: обычный, накопление, долги.")
            return True
        draft["financial_mode"] = mode
        draft["total_balance"] = (
            draft["cash_balance"] + draft["card_balance"] + draft["savings_balance"]
        )
        draft["step"] += 1
        await update.message.reply_text(_startup_summary(draft), reply_markup=CONFIRMATION_KEYBOARD)
        return True
    if text == "Изменить":
        context.user_data[SETUP_STATE] = {"step": 0}
        await update.message.reply_text(SETUP_FIELDS[0][1], reply_markup=MENU)
        return True
    if text == "Отменить":
        context.user_data.pop(SETUP_STATE, None)
        await update.message.reply_text("Настройка отменена.", reply_markup=MENU)
        return True
    if text != "Подтвердить":
        await update.message.reply_text("Выберите: Подтвердить, Изменить или Отменить.")
        return True
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        await asyncio.to_thread(repository.replace_startup_state, update.effective_user.id, draft)
    except Exception:
        logger.exception("Failed to save startup state for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось сохранить стартовое состояние. Попробуйте ещё раз.")
        return True
    context.user_data[SETUP_STATE] = {"step": "reminder"}
    await update.message.reply_text(
        "Во сколько каждое утро напоминать о дневном лимите?\n"
        "Пример: 09:00\nЕсли напоминание не нужно, напишите: нет",
        reply_markup=MENU,
    )
    return True


def _parse_number(value: str) -> Decimal | None:
    try:
        parsed = Decimal(value.replace(",", "."))
    except InvalidOperation:
        return None
    return parsed if parsed.is_finite() else None


def _clear_pending_actions(context: ContextTypes.DEFAULT_TYPE) -> None:
    """Leave one unambiguous interactive action active for a user."""
    for key in (PENDING_TYPE, PENDING_CATEGORY, PENDING_TRANSACTION_STEP,
                PENDING_CATEGORY_ACTION, PENDING_BUDGET_ACTION, PENDING_DEBT_ACTION,
                PENDING_GOAL_ACTION, PENDING_DELETE_OPERATION, PENDING_TRANSFER,
                PENDING_DAILY_LIMIT, PENDING_DAILY_OVERRIDE, PENDING_REMINDER_TIME,
                SETUP_STATE):
        context.user_data.pop(key, None)
    context.user_data.pop("pending_transaction_amount", None)


def _startup_summary(draft: dict[str, Decimal | str | int]) -> str:
    return "\n".join([
        "🧾 Стартовое финансовое состояние", "──────────",
        f"💵 Наличные: {_format_amount(draft['cash_balance'])}",
        f"💳 Карты: {_format_amount(draft['card_balance'])}",
        f"🏦 Накопительные счета: {_format_amount(draft['savings_balance'])}",
        f"Баланс: {_format_amount(draft['total_balance'])}",
        f"Доходы месяца: {_format_amount(draft['month_income_so_far'])}",
        f"Расходы месяца: {_format_amount(draft['month_expense_so_far'])}",
        f"Накоплено: {_format_amount(draft['month_savings_so_far'])}",
        f"Погашено долгов: {_format_amount(draft['month_debt_paid_so_far'])}",
        f"Финансовый режим: {draft['financial_mode']}",
        "\nПодтвердить стартовое состояние?",
    ])


async def save_transaction(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    if await process_reminder_time_message(update, context):
        return
    if await process_daily_override_message(update, context):
        return
    if await process_daily_limit_message(update, context):
        return
    if await process_setup_message(update, context):
        return
    if await process_budget_message(update, context):
        return
    if await process_debt_message(update, context):
        return
    if await process_goal_message(update, context):
        return
    if await process_category_message(update, context):
        return
    if await process_transfer_message(update, context):
        return
    transaction_type = context.user_data.get(PENDING_TYPE)
    if not transaction_type:
        await update.message.reply_text("Выберите действие в /add или откройте главное меню: /start", reply_markup=MENU)
        return
    category = context.user_data.get(PENDING_CATEGORY)
    step = context.user_data.get(PENDING_TRANSACTION_STEP)
    if not category or step not in {"amount", "comment"}:
        await update.message.reply_text("Сначала выберите категорию кнопкой.")
        return
    if step == "amount":
        amount = _parse_number((update.message.text or "").strip())
        if amount is None or amount <= 0:
            await update.message.reply_text("❗ Введите положительную сумму, например: 1500")
            return
        context.user_data["pending_transaction_amount"] = amount
        context.user_data[PENDING_TRANSACTION_STEP] = "comment"
        await update.message.reply_text("Введите комментарий.")
        return
    amount = context.user_data.get("pending_transaction_amount")
    description = (update.message.text or "").strip()
    if not description:
        await update.message.reply_text("Комментарий не может быть пустым.")
        return
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        await asyncio.to_thread(repository.add_transaction, update.effective_user.id, transaction_type, amount, category, description)
        await asyncio.to_thread(repository.touch_category, update.effective_user.id, transaction_type, category)
    except Exception:
        logger.exception("Failed to save transaction for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось сохранить операцию. Проверьте настройки Google Sheets.")
        return
    _clear_pending_actions(context)
    context.user_data.pop("pending_transaction_amount", None)
    icon, kind = ("💰", "Доход") if transaction_type == "income" else ("💸", "Расход")
    message = (f"✅ {kind} сохранён\n\n{icon} {_format_amount(amount)}\n"
               f"📂 Категория: {category}\n📝 Комментарий: {description}")
    await update.message.reply_text(message, reply_markup=MENU)


async def begin_set_daily_limit(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_DAILY_LIMIT] = True
    await update.message.reply_text("Введите сумму дневного лимита.\nНапример: 1500")


async def process_daily_limit_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    if not context.user_data.get(PENDING_DAILY_LIMIT):
        return False
    amount = _parse_number((update.message.text or "").strip())
    if amount is None or amount <= 0:
        await update.message.reply_text("❗ Введите число, например: 1500")
        return True
    repository: SheetsRepository = context.bot_data["repository"]
    month = datetime.now(MOSCOW_TZ).strftime("%Y-%m")
    try:
        await asyncio.to_thread(
            repository.upsert_daily_limit, update.effective_user.id, month, amount
        )
    except Exception:
        logger.exception("Failed to save daily limit for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось сохранить дневной лимит. Попробуйте ещё раз.")
        return True
    context.user_data.pop(PENDING_DAILY_LIMIT, None)
    await update.message.reply_text(
        f"✅ Дневной лимит установлен\n💸 {_format_amount(amount)} в день", reply_markup=MENU
    )
    return True


async def daily_limit(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    month = datetime.now(MOSCOW_TZ).strftime("%Y-%m")
    try:
        today = datetime.now(MOSCOW_TZ).strftime("%Y-%m-%d")
        rows, limit, override = await asyncio.gather(
            asyncio.to_thread(repository.get_transactions, update.effective_user.id),
            asyncio.to_thread(repository.get_daily_limit, update.effective_user.id, month),
            asyncio.to_thread(repository.get_daily_limit_override, update.effective_user.id, today),
        )
    except Exception:
        logger.exception("Failed to load daily limit for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось получить дневной лимит. Попробуйте ещё раз.")
        return
    await update.message.reply_text(
        build_daily_limit_message(rows, limit, override=override, now=datetime.now(MOSCOW_TZ)),
        reply_markup=MENU,
    )


def _reminder_text(settings: dict[str, str] | None) -> str:
    enabled = bool(settings and str(settings.get("daily_limit_reminder_enabled", "")).lower() == "true")
    time_text = str(settings.get("daily_limit_reminder_time", "")) if settings else ""
    return "\n".join([
        "🌅 Утренний лимит", "", f"Статус: {'включён' if enabled else 'выключен'}",
        f"Время: {time_text + ' МСК' if time_text else 'не задано'}",
    ])


async def daily_limit_reminder(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        settings = await asyncio.to_thread(repository.get_user_settings, update.effective_user.id)
    except Exception:
        logger.exception("Failed to load reminder settings for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось получить настройки напоминания.")
        return
    await update.message.reply_text(_reminder_text(settings), reply_markup=_inline_menu([
        [("🕒 Изменить время", "action:set_daily_limit_reminder")],
        [("✅ Включить", "action:enable_daily_limit_reminder")],
        [("🔕 Выключить", "action:disable_daily_limit_reminder")],
        [("🔙 Назад", "menu:settings")],
    ]))


async def begin_set_daily_limit_reminder(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_REMINDER_TIME] = True
    await update.message.reply_text(
        "Введите время по Москве в формате HH:MM.\nНапример: 09:00"
    )


async def _apply_reminder_schedule(context, user_id: int, settings: dict[str, str]) -> None:
    from app.scheduler import schedule_user_reminder
    schedule_user_reminder(context.application, user_id, settings)


async def process_reminder_time_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    if not context.user_data.get(PENDING_REMINDER_TIME):
        return False
    normalized = parse_reminder_time(update.message.text or "")
    if normalized is None:
        await update.message.reply_text("❗ Введите время в формате HH:MM, например 09:00")
        return True
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        settings = await asyncio.to_thread(
            repository.upsert_user_settings, update.effective_user.id,
            reminder_enabled=True, reminder_time=normalized,
        )
        await _apply_reminder_schedule(context, update.effective_user.id, settings)
    except Exception:
        logger.exception("Failed to save reminder settings for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось сохранить время напоминания.")
        return True
    context.user_data.pop(PENDING_REMINDER_TIME, None)
    await update.message.reply_text(
        f"✅ Утреннее напоминание включено на {normalized} МСК", reply_markup=MENU
    )
    return True


async def set_reminder_enabled(update: Update, context: ContextTypes.DEFAULT_TYPE, enabled: bool) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    existing = await asyncio.to_thread(repository.get_user_settings, update.effective_user.id)
    if enabled and not (existing and existing.get("daily_limit_reminder_time")):
        await begin_set_daily_limit_reminder(update, context)
        return
    settings = await asyncio.to_thread(
        repository.upsert_user_settings, update.effective_user.id,
        reminder_enabled=enabled,
    )
    await _apply_reminder_schedule(context, update.effective_user.id, settings)
    await update.message.reply_text(
        "✅ Утренний лимит включён." if enabled else "Утренний лимит выключен."
    )


async def begin_daily_override(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_DAILY_OVERRIDE] = True
    await update.message.reply_text("Введите лимит на сегодня.")


async def process_daily_override_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    if not context.user_data.get(PENDING_DAILY_OVERRIDE):
        return False
    amount = _parse_number((update.message.text or "").strip())
    if amount is None or amount <= 0:
        await update.message.reply_text("❗ Введите число, например: 1500")
        return True
    repository: SheetsRepository = context.bot_data["repository"]
    today = datetime.now(MOSCOW_TZ).strftime("%Y-%m-%d")
    await asyncio.to_thread(
        repository.set_daily_limit_override, update.effective_user.id, today, amount, "active"
    )
    context.user_data.pop(PENDING_DAILY_OVERRIDE, None)
    await update.message.reply_text(
        f"✅ Лимит на сегодня установлен: {_format_amount(amount)}", reply_markup=MENU
    )
    return True


async def disable_limit_today(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    today = datetime.now(MOSCOW_TZ).strftime("%Y-%m-%d")
    await asyncio.to_thread(
        repository.set_daily_limit_override, update.effective_user.id, today, None, "disabled"
    )
    await update.message.reply_text("Сегодня лимит отключён.")


async def confirm_limit_today(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    month = datetime.now(MOSCOW_TZ).strftime("%Y-%m")
    limit = await asyncio.to_thread(repository.get_daily_limit, update.effective_user.id, month)
    if not limit:
        await update.message.reply_text("Дневной лимит не установлен.")
        return
    await update.message.reply_text(
        f"✅ Лимит на сегодня подтверждён: {_format_amount(Decimal(str(limit['daily_limit'])))}"
    )


async def delete_daily_limit(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    month = datetime.now(MOSCOW_TZ).strftime("%Y-%m")
    try:
        deleted = await asyncio.to_thread(
            repository.delete_daily_limit, update.effective_user.id, month
        )
    except Exception:
        logger.exception("Failed to delete daily limit for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось удалить дневной лимит. Попробуйте ещё раз.")
        return
    await update.message.reply_text(
        "✅ Дневной лимит удалён" if deleted else "Дневной лимит не установлен.",
        reply_markup=MENU,
    )


async def process_transfer_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    draft = context.user_data.get(PENDING_TRANSFER)
    if draft is None:
        return False
    if draft.get("step") == "amount":
        amount = _parse_number((update.message.text or "").strip())
        if amount is None or amount <= 0:
            await update.message.reply_text("❗ Введите положительную сумму, например: 1500")
            return True
        draft.update({"amount": amount, "step": "comment"})
        await update.message.reply_text("Введите комментарий.")
        return True
    if draft.get("step") != "comment":
        await update.message.reply_text("Сначала выберите счёт кнопкой.")
        return True
    comment = (update.message.text or "").strip()
    if not comment:
        await update.message.reply_text("Комментарий не может быть пустым.")
        return True
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        result = await asyncio.to_thread(
            repository.add_transfer, update.effective_user.id, draft["from_account"],
            draft["to_account"], draft["amount"], comment,
        )
    except ValueError as error:
        if str(error) == "Insufficient funds.":
            await update.message.reply_text("Недостаточно средств на счёте.")
            return True
        raise
    except Exception:
        logger.exception("Failed to save transfer for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось сохранить перевод. Попробуйте ещё раз.")
        return True
    message = (
        f"✅ Перевод сохранён\n\n🔁 {_format_amount(draft['amount'])}\n"
        f"{ACCOUNT_LABELS[draft['from_account']]} → {ACCOUNT_LABELS[draft['to_account']]}\n"
        f"Комментарий: {comment}"
    )
    if result == "recorded":
        message += ("\n\nСтартовое состояние не настроено. Перевод будет записан как операция, "
                    "но балансы счетов не обновятся.")
    context.user_data.pop(PENDING_TRANSFER, None)
    await update.message.reply_text(message, reply_markup=MENU)
    return True


async def process_debt_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    action = context.user_data.get(PENDING_DEBT_ACTION)
    if not action:
        return False
    text = (update.message.text or "").strip()
    repository: SheetsRepository = context.bot_data["repository"]
    user_id = update.effective_user.id
    if action == "add":
        try:
            debt_name, initial, monthly, payment_day = _parse_debt(text)
        except ValueError as error:
            await update.message.reply_text(str(error))
            return True
        try:
            await asyncio.to_thread(repository.add_debt, user_id, debt_name, initial, monthly, payment_day)
        except Exception:
            logger.exception("Failed to add debt for user %s", user_id)
            await update.message.reply_text("Не удалось сохранить долг. Попробуйте ещё раз.")
            return True
        context.user_data.pop(PENDING_DEBT_ACTION, None)
        await update.message.reply_text(
            f"✅ Долг добавлен\n\n💳 {debt_name}\nОстаток: {_format_amount(initial)}\n"
            f"Ежемесячный платёж: {_format_amount(monthly)}\nДень платежа: {payment_day}",
            reply_markup=MENU,
        )
        return True

    if action in {"pay", "close_pay"}:
        try:
            if action == "pay":
                debt_name, amount, comment = _parse_debt_payment(text)
            else:
                debt_name, comment = _parse_debt_closure(text)
                amount = Decimal("0")
        except ValueError as error:
            await update.message.reply_text(str(error))
            return True
        try:
            debt = await asyncio.to_thread(repository.get_debt, user_id, debt_name)
        except Exception:
            logger.exception("Failed to load debt for payment, user %s", user_id)
            await update.message.reply_text("Не удалось проверить долг. Попробуйте ещё раз.")
            return True
        if debt is None:
            await update.message.reply_text("❗ Не нашёл такой долг.\nПроверьте название или откройте /debts")
            return True
        if debt.get("status") != "active":
            await update.message.reply_text("Долг уже закрыт.")
            return True
        remaining = _debt_decimal(debt, "current_amount")
        if action == "close_pay":
            amount = remaining
        try:
            updated = await asyncio.to_thread(repository.add_debt_payment, user_id, debt_name, amount, comment)
            if updated is None:
                await update.message.reply_text("Не удалось сохранить платёж: долг не найден или закрыт.")
                return True
            await asyncio.to_thread(
                repository.add_transaction, user_id, "expense", _debt_decimal(updated, "applied_amount"), "долги",
                f"Платёж по долгу: {updated['debt_name']} {comment}".strip(),
            )
        except Exception:
            logger.exception("Failed to save debt payment for user %s", user_id)
            await update.message.reply_text("Не удалось сохранить платёж. Проверьте настройки Google Sheets и повторите попытку.")
            return True
        context.user_data.pop(PENDING_DEBT_ACTION, None)
        applied_amount = _debt_decimal(updated, "applied_amount")
        overpayment = _debt_decimal(updated, "overpayment")
        if action == "close_pay":
            response = f"Долг закрыт платежом:\n{updated['debt_name']}\nСписано: {_format_amount(applied_amount)}"
        elif updated.get("status") == "closed":
            response = f"Долг закрыт.\nПлатёж: {_format_amount(applied_amount)}"
            if overpayment:
                response += f"\nПереплата не учтена: {_format_amount(overpayment)}"
        else:
            response = (
                f"✅ Платёж по долгу сохранён\n\n💳 {updated['debt_name']}\n💸 {_format_amount(applied_amount)}\n"
                f"Остаток: {_format_amount(_debt_decimal(updated, 'current_amount'))}"
            )
        await update.message.reply_text(response, reply_markup=MENU)
        return True

    debt_name = text.strip()
    if not debt_name:
        await update.message.reply_text("Введите название долга.")
        return True
    if action == "detail":
        try:
            debt, payments = await asyncio.gather(
                asyncio.to_thread(repository.get_debt, user_id, debt_name),
                asyncio.to_thread(repository.get_debt_payments, user_id, debt_name),
            )
        except Exception:
            logger.exception("Failed to load debt details for user %s", user_id)
            await update.message.reply_text("Не удалось получить карточку долга. Попробуйте ещё раз.")
            return True
        if debt is None:
            await update.message.reply_text("Долг не найден.")
            return True
        context.user_data.pop(PENDING_DEBT_ACTION, None)
        initial = _debt_decimal(debt, "initial_amount")
        current = _debt_decimal(debt, "current_amount")
        paid = max(Decimal("0"), initial - current)
        progress = (paid / initial * 100).quantize(Decimal("0.1")) if initial else Decimal("0")
        lines = [
            f"💳 {debt['debt_name']}", "──────────",
            f"Остаток: {_format_amount(current)}",
            f"Статус: {debt.get('status', '')}",
            f"Платёж: {_format_amount(_debt_decimal(debt, 'monthly_payment'))}",
            f"День платежа: {debt.get('payment_day', '')}",
            f"Прогресс: {progress:.1f}%",
            "", f"Начальная сумма: {_format_amount(initial)}",
            f"Погашено: {_format_amount(paid)}", "", "Последние платежи:",
        ]
        latest = sorted(payments, key=lambda payment: str(payment.get("created_at", "")), reverse=True)[:5]
        if latest:
            lines.extend(
                f"• {_format_amount(_debt_decimal(payment, 'amount'))} — {payment.get('comment', '') or 'без комментария'}"
                for payment in latest
            )
        else:
            lines.append("Платежей пока нет.")
        await update.message.reply_text("\n".join(lines), reply_markup=MENU)
        return True

    try:
        result = await asyncio.to_thread(repository.close_debt, user_id, debt_name)
    except Exception:
        logger.exception("Failed to close debt for user %s", user_id)
        await update.message.reply_text("Не удалось закрыть долг. Попробуйте ещё раз.")
        return True
    if result == "missing":
        await update.message.reply_text("❗ Не нашёл такой долг.\nПроверьте название или откройте /debts")
        return True
    if result == "closed":
        await update.message.reply_text("Долг уже закрыт.")
        return True
    context.user_data.pop(PENDING_DEBT_ACTION, None)
    await update.message.reply_text(f'Долг "{debt_name}" закрыт вручную.', reply_markup=MENU)
    return True


async def process_budget_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    action = context.user_data.get(PENDING_BUDGET_ACTION)
    if not action:
        return False
    text = (update.message.text or "").strip()
    repository: SheetsRepository = context.bot_data["repository"]
    month = datetime.now(timezone.utc).strftime("%Y-%m")
    if action == "set":
        try:
            category, amount = _parse_plan(text)
        except ValueError:
            await update.message.reply_text("❗ Неверный формат.\nВведите: категория сумма\nПример: продукты 15000")
            return True
        try:
            await asyncio.to_thread(repository.upsert_budget_plan, update.effective_user.id, month, category, amount)
        except Exception:
            logger.exception("Failed to save budget plan for user %s", update.effective_user.id)
            await update.message.reply_text("Не удалось сохранить план. Попробуйте ещё раз.")
            return True
        context.user_data.pop(PENDING_BUDGET_ACTION, None)
        await update.message.reply_text(f"✅ План сохранён\n\n📌 {category}\n💰 {_format_amount(amount)}", reply_markup=MENU)
        return True
    category = text.strip()
    if not category:
        await update.message.reply_text("Введите категорию. Пример: продукты")
        return True
    try:
        deleted = await asyncio.to_thread(repository.delete_budget_plan, update.effective_user.id, month, category)
    except Exception:
        logger.exception("Failed to delete budget plan for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось удалить план. Попробуйте ещё раз.")
        return True
    if not deleted:
        await update.message.reply_text("План для этой категории не найден.")
        return True
    context.user_data.pop(PENDING_BUDGET_ACTION, None)
    await update.message.reply_text(f'План для категории "{category}" удалён.', reply_markup=MENU)
    return True


def _parse_transaction(text: str, transaction_type: str) -> tuple[Decimal, str, str]:
    parts = text.strip().replace(",", ".").split()
    expected_parts = 2 if transaction_type == "income" else 3
    if len(parts) < expected_parts:
        raise ValueError("❗ Неверный формат.\nПример:\n1500 продукты магнит")
    amount = _parse_number(parts[0])
    if amount is None or amount <= 0:
        raise ValueError("❗ Не понял сумму.\nВведите число, например: 1500")
    return (amount, "", " ".join(parts[1:])) if transaction_type == "income" else (amount, parts[1], " ".join(parts[2:]))


def _parse_plan(text: str) -> tuple[str, Decimal]:
    parts = text.strip().split()
    if len(parts) < 2:
        raise ValueError("Category and amount are required")
    amount = _parse_number(parts[-1])
    category = " ".join(parts[:-1]).strip()
    if not category or amount is None or amount <= 0:
        raise ValueError("Invalid plan")
    return category, amount


def _parse_debt(text: str) -> tuple[str, Decimal, Decimal, int]:
    parts = text.split()
    if len(parts) != 4 or not parts[0]:
        raise ValueError("Неверный формат. Введите: название остаток_долга ежемесячный_платёж день_платежа")
    initial = _parse_number(parts[1])
    monthly = _parse_number(parts[2])
    try:
        payment_day = int(parts[3])
    except ValueError:
        payment_day = 0
    if initial is None or initial <= 0:
        raise ValueError("Остаток долга должен быть числом больше 0.")
    if monthly is None or monthly <= 0:
        raise ValueError("Ежемесячный платёж должен быть числом больше 0.")
    if not 1 <= payment_day <= 31:
        raise ValueError("День платежа должен быть числом от 1 до 31.")
    return parts[0], initial, monthly, payment_day


def _parse_debt_payment(text: str) -> tuple[str, Decimal, str]:
    parts = text.split()
    if len(parts) < 2 or not parts[0]:
        raise ValueError("Неверный формат. Введите: название сумма комментарий")
    amount = _parse_number(parts[1])
    if amount is None or amount <= 0:
        raise ValueError("Сумма должна быть числом больше 0.")
    return parts[0], amount, " ".join(parts[2:])


def _parse_debt_closure(text: str) -> tuple[str, str]:
    parts = text.split()
    if not parts or not parts[0]:
        raise ValueError("Неверный формат. Введите: название комментарий")
    return parts[0], " ".join(parts[1:])


def _debt_decimal(row: dict[str, str], field: str) -> Decimal:
    value = _parse_number(str(row.get(field, "0")))
    return value if value is not None else Decimal("0")


def _format_amount(amount: Decimal) -> str:
    rendered = f"{amount:,.2f}" if amount % 1 else f"{amount:,.0f}"
    return f"{rendered.replace(',', ' ')} ₽"


async def operations(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await _show_operations(update, context)


async def expenses(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await _show_operations(update, context, operation_type="expense")


async def incomes(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await _show_operations(update, context, operation_type="income")


async def _show_operations(
    update: Update, context: ContextTypes.DEFAULT_TYPE, operation_type: str | None = None,
) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    user_id = update.effective_user.id
    month = datetime.now(timezone.utc).strftime("%Y-%m")
    raw_args = getattr(context, "args", None) or []
    query = " ".join(raw_args).strip()
    try:
        rows = await asyncio.to_thread(repository.get_transactions_for_month, user_id, month)
    except Exception:
        logger.exception("Failed to load operations for user %s", user_id)
        await update.message.reply_text("Не удалось получить историю операций. Проверьте настройки Google Sheets.")
        return
    reply_markup = (
        _inline_menu([[("🗑 Удалить операцию", "action:delete_operation")]])
        if operation_type is None else MENU
    )
    await update.message.reply_text(
        build_operations_history(rows, operation_type=operation_type, query=query), reply_markup=reply_markup
    )


def _operation_delete_text(row: dict[str, str], *, numbered: int | None = None) -> str:
    """Render one operation in the compact deletion UI format."""
    try:
        created_at = datetime.fromisoformat(str(row.get("created_at", "")).replace("Z", "+00:00"))
        date = created_at.astimezone(timezone.utc).strftime("%d.%m.%Y %H:%M")
    except ValueError:
        date = str(row.get("created_at", ""))
    amount = _parse_number(str(row.get("amount", "0"))) or Decimal("0")
    kind = {"income": "💰", "expense": "💸", "transfer": "🔁"}.get(row.get("type"), "•")
    category = str(row.get("category", "")).strip() or "без категории"
    description = str(row.get("description", "")).strip() or "без описания"
    prefix = f"{numbered}) " if numbered is not None else ""
    return f"{prefix}{date}\n{kind} {_format_amount(amount).removesuffix(' ₽')}   {category}  {description}"


def _linked_operation_warning(row: dict[str, str]) -> str:
    description = str(row.get("description", "")).casefold()
    if description.startswith(("платёж по долгу:", "пополнение цели:")):
        return (
            "\n\nЭта операция связана с долгом или целью.\n"
            "Удаление уберёт только запись из Operations.\n"
            "Долг или цель автоматически не изменятся."
        )
    return ""


async def delete_operation(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """Show the current user's ten newest Operations rows for deletion."""
    _clear_pending_actions(context)
    repository: SheetsRepository = context.bot_data["repository"]
    user_id = update.effective_user.id
    month = datetime.now(timezone.utc).strftime("%Y-%m")
    try:
        rows = await asyncio.to_thread(repository.get_recent_operations_with_row_numbers, user_id, month)
    except Exception:
        logger.exception("Failed to load operations to delete for user %s", user_id)
        await update.message.reply_text("Не удалось получить операции. Проверьте настройки Google Sheets.")
        return
    if not rows:
        await update.message.reply_text("Операций для удаления нет.", reply_markup=MENU)
        return
    context.user_data[PENDING_DELETE_OPERATION] = {
        str(row["_row_number"]): row for row in rows
    }
    lines = ["Выберите операцию для удаления:"]
    buttons = []
    for index, row in enumerate(rows, start=1):
        lines.extend(["", _operation_delete_text(row, numbered=index)])
        buttons.append([(f"Удалить #{index}", f"delete_operation:select:{row['_row_number']}")])
    await update.message.reply_text("\n".join(lines), reply_markup=_inline_menu(buttons))


async def delete_operation_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    if query is None or query.message is None:
        return
    data = query.data or ""
    parts = data.split(":")
    if len(parts) != 3 or parts[0] != "delete_operation":
        return
    selected = context.user_data.get(PENDING_DELETE_OPERATION, {})
    row = selected.get(parts[2])
    if parts[1] == "select":
        if row is None:
            await query.message.reply_text("Операция не найдена или уже удалена.")
            return
        await query.message.reply_text(
            "Удалить операцию?\n\n"
            f"{_operation_delete_text(row)}"
            f"{_linked_operation_warning(row)}",
            reply_markup=_inline_menu([
                [("Да, удалить", f"delete_operation:confirm:{parts[2]}")],
                [("Отмена", "delete_operation:cancel:0")],
            ]),
        )
        return
    if parts[1] == "cancel":
        context.user_data.pop(PENDING_DELETE_OPERATION, None)
        await query.message.reply_text("Удаление отменено.", reply_markup=MENU)
        return
    if parts[1] != "confirm" or row is None:
        await query.message.reply_text("Операция не найдена или уже удалена.")
        return
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        deleted = await asyncio.to_thread(
            repository.delete_operation, update.effective_user.id, int(parts[2]), row["created_at"],
        )
    except Exception:
        logger.exception("Failed to delete operation for user %s", update.effective_user.id)
        await query.message.reply_text("Не удалось удалить операцию. Попробуйте ещё раз.")
        return
    context.user_data.pop(PENDING_DELETE_OPERATION, None)
    if not deleted:
        await query.message.reply_text("Операция не найдена или уже удалена.")
        return
    await query.message.reply_text("Операция удалена.", reply_markup=MENU)


async def clear_chat(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text(
        "Очистить последние 100 сообщений чата?\n\nДанные в таблице не будут удалены.",
        reply_markup=_inline_menu([
            [("Да, очистить", "action:clear_confirm")],
            [("Отмена", "action:clear_cancel")],
        ]),
    )


async def confirm_clear_chat(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    history = context.bot_data.get("message_history")
    user_id = update.effective_user.id
    message_ids = history.get(user_id) if history is not None else []
    chat_id = update.message.chat_id
    for message_id in message_ids:
        try:
            await context.bot.delete_message(chat_id=chat_id, message_id=message_id)
        except Exception:
            # Telegram does not allow every message type to be deleted. Continue
            # so one old or inaccessible message cannot abort chat cleanup.
            logger.debug("Could not delete message %s in chat %s", message_id, chat_id, exc_info=True)
    if history is not None:
        history.clear(user_id)
    await update.message.reply_text("Чат очищен")


async def cancel_clear_chat(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text("Очистка чата отменена.")


async def new_month(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    del context
    await update.message.reply_text(
        "🔄 Новый месяц\n\n"
        "Старые операции останутся в таблице.\n"
        "Планы прошлого месяца останутся в истории.\n"
        "Для нового месяца можно создать новые лимиты и новое стартовое состояние.\n\n"
        "Что сделать?",
        reply_markup=_inline_menu([
            [("🧾 Создать стартовое состояние", "action:new_month_setup")],
            [("📌 Создать планы месяца", "action:new_month_plan")],
            [("Отмена", "action:new_month_cancel")],
        ]),
    )


async def new_month_plan(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    await begin_set_plan(update, context)



async def report(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        today_moscow = datetime.now(MOSCOW_TZ)
        rows, startup_state, plans, debt_rows, debt_payments, goal_rows, daily_limit_row, daily_override = await asyncio.gather(
            asyncio.to_thread(repository.get_transactions, update.effective_user.id),
            asyncio.to_thread(repository.get_startup_state, update.effective_user.id),
            asyncio.to_thread(repository.get_budget_plans, update.effective_user.id, datetime.now(timezone.utc).strftime("%Y-%m")),
            asyncio.to_thread(repository.get_debts, update.effective_user.id),
            asyncio.to_thread(repository.get_debt_payments, update.effective_user.id),
            asyncio.to_thread(repository.get_goals, update.effective_user.id),
            asyncio.to_thread(repository.get_daily_limit, update.effective_user.id, today_moscow.strftime("%Y-%m")),
            asyncio.to_thread(repository.get_daily_limit_override, update.effective_user.id, today_moscow.strftime("%Y-%m-%d")),
        )
    except Exception:
        logger.exception("Failed to load report for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось получить отчёт. Проверьте настройки Google Sheets.")
        return
    summary = build_monthly_summary(rows, startup_state)
    active_goals = [goal for goal in goal_rows if goal.get("status") == "active"]
    goal_current = sum((_debt_decimal(goal, "current_amount") for goal in active_goals), Decimal("0"))
    goal_remaining = sum((max(Decimal("0"), _debt_decimal(goal, "target_amount") - _debt_decimal(goal, "current_amount")) for goal in active_goals), Decimal("0"))
    metrics = financial_metrics(rows, startup_state, debt_rows, goal_rows)
    health_score = calculate_health(rows, startup_state, plans, debt_rows, goal_rows)["score"]
    insights = build_business_insights(
        rows, startup_state, plans, debt_rows, goal_rows, now=today_moscow,
        daily_limit=daily_limit_row, daily_limit_override=daily_override,
    )
    analytics_block = f"🎯 Цели:\nАктивных целей: {len(active_goals)}\nНакоплено всего: {_format_amount(goal_current)}\nОсталось до целей: {_format_amount(goal_remaining)}\n\n📈 Прогноз:\nСвободный денежный поток: {_format_amount(metrics['free_cashflow'])}\n\n💚 Финансовое здоровье: {health_score}/100"
    await update.message.reply_text(
        f"{summary}\n\n{build_accounts_report_block(startup_state)}\n\n{build_budget_report_block(rows, plans, startup_state)}\n\n"
        f"{build_daily_limit_report_block(rows, daily_limit_row, override=daily_override, now=today_moscow)}\n\n"
        f"{build_debt_report_block(rows, debt_rows, debt_payments, startup_state)}\n\n{analytics_block}\n\n"
        f"🧠 Бизнес-выводы\n\n" + "\n".join(f"- {insight}" for insight in insights),
        reply_markup=REPORT_MENU,
    )


async def insights(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    try:
        rows, startup, plans, debts_, goals_ = await _load_analytics(update, context)
        daily_limit_row = await asyncio.to_thread(
            context.bot_data["repository"].get_daily_limit, update.effective_user.id,
            datetime.now(timezone.utc).strftime("%Y-%m"),
        )
        today_moscow = datetime.now(MOSCOW_TZ)
        daily_override = await asyncio.to_thread(
            context.bot_data["repository"].get_daily_limit_override,
            update.effective_user.id, today_moscow.strftime("%Y-%m-%d"),
        )
    except Exception:
        logger.exception("Failed to build business insights")
        await update.message.reply_text("Не удалось сформировать бизнес-выводы. Попробуйте ещё раз.")
        return
    conclusions = build_business_insights(
        rows, startup, plans, debts_, goals_, now=today_moscow,
        daily_limit=daily_limit_row, daily_limit_override=daily_override,
    )
    await update.message.reply_text(
        "🧠 Бизнес-выводы\n\n" + "\n".join(f"- {item}" for item in conclusions),
        reply_markup=MENU,
    )


async def _send_chart(update: Update, build, *, empty_message: str, caption: str) -> None:
    """Build a PNG away from the event loop, send it, then remove the temporary file."""
    chart_path: Path | None = None
    try:
        chart_path = await asyncio.to_thread(build)
        if chart_path is None:
            await update.message.reply_text(empty_message, reply_markup=MENU)
            return
        with chart_path.open("rb") as photo:
            await update.message.reply_photo(photo=photo, caption=caption, reply_markup=MENU)
    except Exception:
        logger.exception("Failed to generate chart %s", caption)
        await update.message.reply_text("Не удалось построить диаграмму. Попробуйте ещё раз.", reply_markup=MENU)
    finally:
        if chart_path is not None:
            try:
                chart_path.unlink(missing_ok=True)
            except OSError:
                logger.warning("Could not remove temporary chart %s", chart_path, exc_info=True)


async def chart_expenses(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        rows = await asyncio.to_thread(repository.get_transactions, update.effective_user.id)
    except Exception:
        logger.exception("Failed to load expense chart data")
        await update.message.reply_text("Не удалось получить данные для диаграммы.")
        return
    await _send_chart(update, lambda: build_expenses_chart(rows), empty_message="Расходов за текущий месяц нет.", caption="Расходы по категориям")


async def chart_cashflow(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        rows = await asyncio.to_thread(repository.get_transactions, update.effective_user.id)
    except Exception:
        logger.exception("Failed to load cash-flow chart data")
        await update.message.reply_text("Не удалось получить данные для диаграммы.")
        return
    await _send_chart(update, lambda: build_cashflow_chart(rows), empty_message="Операций за текущий месяц нет.", caption="Доходы и расходы по дням")


async def chart_plan(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        rows, startup, plans = await asyncio.gather(
            asyncio.to_thread(repository.get_transactions, update.effective_user.id),
            asyncio.to_thread(repository.get_startup_state, update.effective_user.id),
            asyncio.to_thread(repository.get_budget_plans, update.effective_user.id, datetime.now(timezone.utc).strftime("%Y-%m")),
        )
    except Exception:
        logger.exception("Failed to load plan chart data")
        await update.message.reply_text("Не удалось получить данные для диаграммы.")
        return
    await _send_chart(update, lambda: build_plan_chart(rows, plans, startup), empty_message="Нет планов и расходов за текущий месяц.", caption="План / факт")


async def chart_debts(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        debt_rows = await asyncio.to_thread(repository.get_debts, update.effective_user.id)
    except Exception:
        logger.exception("Failed to load debt chart data")
        await update.message.reply_text("Не удалось получить данные для диаграммы.")
        return
    await _send_chart(update, lambda: build_debts_chart(debt_rows), empty_message="Активных долгов нет.", caption="Остатки долгов")


async def chart_goals(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        goal_rows = await asyncio.to_thread(repository.get_goals, update.effective_user.id)
    except Exception:
        logger.exception("Failed to load goal chart data")
        await update.message.reply_text("Не удалось получить данные для диаграммы.")
        return
    await _send_chart(update, lambda: build_goals_chart(goal_rows), empty_message="Активных целей нет.", caption="Прогресс целей")


async def plan(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository: SheetsRepository = context.bot_data["repository"]
    try:
        rows, startup_state, plans = await asyncio.gather(
            asyncio.to_thread(repository.get_transactions, update.effective_user.id),
            asyncio.to_thread(repository.get_startup_state, update.effective_user.id),
            asyncio.to_thread(repository.get_budget_plans, update.effective_user.id, datetime.now(timezone.utc).strftime("%Y-%m")),
        )
    except Exception:
        logger.exception("Failed to load budget plan for user %s", update.effective_user.id)
        await update.message.reply_text("Не удалось получить план. Попробуйте ещё раз.")
        return
    await update.message.reply_text(build_budget_plan_report(rows, plans, startup_state), reply_markup=MENU)


def _begin_goal_action(context: ContextTypes.DEFAULT_TYPE, action: str) -> None:
    _clear_pending_actions(context)
    context.user_data[PENDING_GOAL_ACTION] = action


async def begin_add_goal(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_goal_action(context, "add")
    await update.message.reply_text("🎯 Новая цель\n\nВведите: название цель накоплено ежемесячно\nПример: машина 600000 50000 20000")


async def begin_fund_goal(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_goal_action(context, "fund")
    await update.message.reply_text("💰 Пополнение цели\n\nВведите: название сумма комментарий\nПример: машина 15000 премия")


async def begin_goal(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_goal_action(context, "detail")
    await update.message.reply_text("Введите название цели.")


async def begin_delete_goal(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    _begin_goal_action(context, "delete")
    await update.message.reply_text("Введите название цели.")


def _goal_card(goal: dict[str, str]) -> str:
    target, current, monthly = (_debt_decimal(goal, key) for key in ("target_amount", "current_amount", "monthly_target"))
    remaining = max(Decimal("0"), target - current)
    progress = current / target * 100 if target else Decimal("0")
    status = "активна" if goal.get("status") == "active" else "завершена"
    return "\n".join([f"🎯 {goal.get('goal_name', '')}", "──────────", f"Статус: {status}", f"Цель: {_format_amount(target)}",
                      f"Накоплено: {_format_amount(current)}", f"Осталось: {_format_amount(remaining)}",
                      f"Прогресс: {progress:.1f}%", f"Ежемесячный план: {_format_amount(monthly)}",
                      f"Прогноз достижения: {goal_forecast_date(goal)}"])


async def process_goal_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> bool:
    action = context.user_data.get(PENDING_GOAL_ACTION)
    if not action: return False
    text, repository, user_id = (update.message.text or "").strip(), context.bot_data["repository"], update.effective_user.id
    if action == "add":
        parts = text.split()
        if len(parts) != 4:
            await update.message.reply_text("Введите: название цель накоплено ежемесячно"); return True
        target, current, monthly = (_parse_number(value) for value in parts[1:])
        if not parts[0] or target is None or target <= 0 or current is None or current < 0 or monthly is None or monthly < 0:
            await update.message.reply_text("Проверьте: цель > 0, накоплено и ежемесячный план ≥ 0."); return True
        try: await asyncio.to_thread(repository.add_goal, user_id, parts[0], target, current, monthly)
        except Exception:
            logger.exception("Failed to add goal for user %s", user_id); await update.message.reply_text("Не удалось сохранить цель."); return True
        context.user_data.pop(PENDING_GOAL_ACTION, None)
        await update.message.reply_text(f"✅ Цель добавлена\n\n🎯 {parts[0]}\nЦель: {_format_amount(target)}\nНакоплено: {_format_amount(current)}\nЕжемесячный план: {_format_amount(monthly)}", reply_markup=MENU); return True
    if action in {"detail", "delete"}:
        if not text: await update.message.reply_text("Название цели обязательно."); return True
        try:
            if action == "detail":
                goal = await asyncio.to_thread(repository.get_goal, user_id, text)
                if not goal: await update.message.reply_text("❗ Не нашёл такую цель.\nПроверьте название или откройте /goals"); return True
                context.user_data.pop(PENDING_GOAL_ACTION, None); await update.message.reply_text(_goal_card(goal), reply_markup=MENU); return True
            result = await asyncio.to_thread(repository.close_goal, user_id, text)
        except Exception:
            logger.exception("Failed goal action for user %s", user_id); await update.message.reply_text("Не удалось обработать цель."); return True
        if result == "missing": await update.message.reply_text("❗ Не нашёл такую цель.\nПроверьте название или откройте /goals")
        elif result == "completed": await update.message.reply_text("Цель уже завершена.")
        else: context.user_data.pop(PENDING_GOAL_ACTION, None); await update.message.reply_text(f'Цель "{text}" закрыта вручную.', reply_markup=MENU)
        return True
    parts = text.split(maxsplit=2)
    if len(parts) < 2 or not parts[0] or (amount := _parse_number(parts[1])) is None or amount <= 0:
        await update.message.reply_text("Введите: название сумма комментарий; сумма должна быть больше 0."); return True
    try:
        goal = await asyncio.to_thread(repository.get_goal, user_id, parts[0])
        if not goal: await update.message.reply_text("❗ Не нашёл такую цель.\nПроверьте название или откройте /goals"); return True
        if goal.get("status") != "active": await update.message.reply_text("Цель уже завершена."); return True
        remaining = _debt_decimal(goal, "target_amount") - _debt_decimal(goal, "current_amount")
        if amount > remaining:
            await update.message.reply_text(f"Сумма превышает остаток цели: {_format_amount(remaining)}."); return True
        updated = await asyncio.to_thread(repository.fund_goal, user_id, parts[0], amount)
        if updated is None:
            await update.message.reply_text("Цель не найдена или уже завершена."); return True
        await asyncio.to_thread(repository.add_transaction, user_id, "expense", amount, "накопления", f"Пополнение цели: {parts[0]} {' '.join(parts[2:])}".strip())
    except Exception:
        logger.exception("Failed to fund goal for user %s", user_id); await update.message.reply_text("Не удалось пополнить цель."); return True
    context.user_data.pop(PENDING_GOAL_ACTION, None)
    target, current = _debt_decimal(updated, "target_amount"), _debt_decimal(updated, "current_amount")
    reply = f"✅ Цель пополнена\n\n🎯 {updated['goal_name']}\n💰 +{_format_amount(amount)}\n📈 Накоплено: {_format_amount(current)}\nОсталось: {_format_amount(max(Decimal('0'), target-current))}"
    if updated.get("status") == "completed": reply += f'\nЦель "{updated["goal_name"]}" достигнута 🎉'
    await update.message.reply_text(reply, reply_markup=MENU); return True


async def goals(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository = context.bot_data["repository"]
    try: active = await asyncio.to_thread(repository.get_goals, update.effective_user.id, active_only=True)
    except Exception:
        logger.exception("Failed to load goals"); await update.message.reply_text("Не удалось получить цели."); return
    await update.message.reply_text("Активных целей нет." if not active else "🎯 Активные цели:\n\n" + "\n\n".join(_goal_card(goal) for goal in active), reply_markup=MENU)


async def _load_analytics(update, context):
    repository = context.bot_data["repository"]; uid = update.effective_user.id; month = datetime.now(timezone.utc).strftime("%Y-%m")
    return await asyncio.gather(*[asyncio.to_thread(call, uid, *args) for call, args in [(repository.get_transactions, ()), (repository.get_startup_state, ()), (repository.get_budget_plans, (month,)), (repository.get_debts, ()), (repository.get_goals, ())]])


async def forecast(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    try: rows, startup, plans, debts_, goals_ = await _load_analytics(update, context)
    except Exception: logger.exception("Failed forecast"); await update.message.reply_text("Не удалось построить прогноз."); return
    await update.message.reply_text(build_forecast_report(rows, startup, plans, debts_, goals_), reply_markup=MENU)


async def health(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    try: rows, startup, plans, debts_, goals_ = await _load_analytics(update, context)
    except Exception: logger.exception("Failed health"); await update.message.reply_text("Не удалось оценить здоровье."); return
    await update.message.reply_text(build_health_report(rows, startup, plans, debts_, goals_), reply_markup=MENU)


async def freedom(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    repository = context.bot_data["repository"]
    try: debts_ = await asyncio.to_thread(repository.get_debts, update.effective_user.id, active_only=True)
    except Exception: logger.exception("Failed freedom"); await update.message.reply_text("Не удалось рассчитать долговую свободу."); return
    if not debts_: await update.message.reply_text("Активных долгов нет. Долговая свобода уже достигнута 🎉", reply_markup=MENU); return
    total, payment = sum((_debt_decimal(d, "current_amount") for d in debts_), Decimal("0")), sum((_debt_decimal(d, "monthly_payment") for d in debts_), Decimal("0"))
    if payment <= 0: await update.message.reply_text("Невозможно рассчитать срок закрытия: нет ежемесячных платежей.", reply_markup=MENU); return
    months = max(
        int((_debt_decimal(debt, "current_amount") / _debt_decimal(debt, "monthly_payment")).to_integral_value(rounding="ROUND_CEILING"))
        for debt in debts_
    )
    now = datetime.now(timezone.utc); index = now.month - 1 + months
    await update.message.reply_text(f"🕊 Долговая свобода\n──────────\nОстаток долгов: {_format_amount(total)}\nПлатежи в месяц: {_format_amount(payment)}\nПримерная дата закрытия: {index % 12 + 1:02d}.{now.year + index // 12}\nОсталось месяцев: {months}\nСвободный поток после закрытия: +{_format_amount(payment)}", reply_markup=MENU)


async def category_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    if query is None or query.message is None:
        return
    data = query.data or ""
    if data == "category:noop":
        return
    callback_update = SimpleNamespace(message=query.message, effective_user=update.effective_user)
    repository: SheetsRepository = context.bot_data["repository"]
    user_id = update.effective_user.id
    parts = data.split(":")
    action = parts[1]
    if action == "manage":
        await categories_menu(callback_update, context)
        return
    if action == "list":
        await _show_managed_categories(callback_update, context, parts[2])
        return
    if action in {"create", "managecreate"}:
        kind = parts[2]
        if action == "managecreate":
            _clear_pending_actions(context)
        context.user_data[PENDING_CATEGORY_ACTION] = {"action": action, "type": kind}
        if action == "create":
            context.user_data[PENDING_TYPE] = kind
        await query.message.reply_text("Введите название новой категории.")
        return
    if action == "select":
        kind, row_number = parts[2], int(parts[3])
        category = await asyncio.to_thread(
            repository.get_category_by_row, user_id, kind, row_number
        )
        if category is None:
            await query.message.reply_text("Категория больше недоступна. Выберите другую.")
            return
        context.user_data[PENDING_TYPE] = kind
        context.user_data[PENDING_CATEGORY] = category["category_name"]
        context.user_data[PENDING_TRANSACTION_STEP] = "amount"
        await query.message.reply_text(f"Категория: {category['category_name']}\nВведите сумму.")
        return
    if action == "item":
        kind, row_number = parts[2], int(parts[3])
        category = await asyncio.to_thread(repository.get_category_by_row, user_id, kind, row_number)
        if category is None:
            await query.message.reply_text("Категория больше недоступна.")
            return
        context.user_data["managed_category"] = {
            "type": kind, "row": row_number, "name": category["category_name"]
        }
        favorite = str(category["is_favorite"]).lower() == "true"
        await query.message.reply_text(category["category_name"], reply_markup=_inline_menu([
            [(("Убрать из избранного" if favorite else "⭐ Добавить в избранное"),
              f"category:favorite:{'0' if favorite else '1'}")],
            [("✏️ Переименовать", "category:rename")],
            [("🗑 Удалить / архивировать", "category:remove")],
        ]))
        return
    managed = context.user_data.get("managed_category")
    if action in {"favorite", "rename", "remove"} and not managed:
        await query.message.reply_text("Выберите категорию заново.")
        return
    if action == "favorite":
        favorite = parts[2] == "1"
        await asyncio.to_thread(repository.set_category_favorite, user_id, managed["type"], managed["row"], favorite)
        await query.message.reply_text("Избранное обновлено.")
        await _show_managed_categories(callback_update, context, managed["type"])
        return
    if action == "rename":
        context.user_data[PENDING_CATEGORY_ACTION] = {
            "action": "rename", "type": managed["type"], "old_name": managed["name"]
        }
        await query.message.reply_text("Введите новое название категории.")
        return
    if action == "remove":
        context.user_data[PENDING_CATEGORY_ACTION] = {
            "action": "remove", "type": managed["type"], "old_name": managed["name"]
        }
        await query.message.reply_text(
            f'Что сделать с операциями в категории "{managed["name"]}"?',
            reply_markup=_inline_menu([
                [("📦 Архивировать категорию", "category:archive")],
                [("🔁 Перенести операции", "category:move")],
                [("🗑 Удалить операции этой категории", "category:deleteops")],
                [("Отмена", "category:cancel")],
            ]),
        )
        return
    pending = context.user_data.get(PENDING_CATEGORY_ACTION)
    if action == "cancel":
        context.user_data.pop(PENDING_CATEGORY_ACTION, None)
        await query.message.reply_text("Действие отменено.")
        return
    if not pending:
        await query.message.reply_text("Действие устарело. Выберите категорию заново.")
        return
    kind, old_name = pending["type"], pending["old_name"]
    if action in {"renameops", "renameonly"}:
        count = await asyncio.to_thread(
            repository.rename_category, user_id, kind, old_name, pending["new_name"],
            rename_operations=action == "renameops",
        )
        if count is None:
            await query.message.reply_text("⚠️ Такая категория уже существует.")
            return
        context.user_data.pop(PENDING_CATEGORY_ACTION, None)
        await query.message.reply_text(
            f"Категория переименована.{f' Обновлено операций: {count}.' if action == 'renameops' else ''}"
        )
        return
    if action == "archive":
        await asyncio.to_thread(repository.archive_category, user_id, kind, old_name)
        context.user_data.pop(PENDING_CATEGORY_ACTION, None)
        await query.message.reply_text("✅ Категория архивирована\nСтарые операции сохранены.")
        return
    if action == "move":
        categories = await asyncio.to_thread(repository.get_categories, user_id, kind)
        choices = [c for c in categories if c["category_name"].casefold() != old_name.casefold()]
        if not choices:
            await query.message.reply_text("Нет другой активной категории для переноса.")
            return
        await query.message.reply_text("Выберите новую категорию:", reply_markup=_inline_menu([
            [(c["category_name"], f"category:moveto:{c['_row_number']}")] for c in choices
        ]))
        return
    if action == "moveto":
        target = await asyncio.to_thread(repository.get_category_by_row, user_id, kind, int(parts[2]))
        if target is None:
            await query.message.reply_text("Категория назначения больше недоступна.")
            return
        count = await asyncio.to_thread(repository.move_operations_category, user_id, kind, old_name, target["category_name"])
        await asyncio.to_thread(repository.archive_category, user_id, kind, old_name)
        context.user_data.pop(PENDING_CATEGORY_ACTION, None)
        await query.message.reply_text(
            f"✅ Операции перенесены\nИз категории: {old_name}\n"
            f"В категорию: {target['category_name']}\nКоличество операций: {count}"
        )
        return
    if action == "deleteops":
        count = await asyncio.to_thread(repository.delete_operations_by_category, user_id, kind, old_name)
        await asyncio.to_thread(repository.archive_category, user_id, kind, old_name)
        context.user_data.pop(PENDING_CATEGORY_ACTION, None)
        await query.message.reply_text(
            f"✅ Операции удалены\nКатегория: {old_name}\nУдалено операций: {count}"
        )


async def menu_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """Route inline-menu buttons to the existing command handlers."""
    query = update.callback_query
    if query is None or query.message is None:
        return
    await query.answer()
    if (query.data or "").startswith("category:"):
        await category_callback(update, context)
        return
    if (query.data or "").startswith("delete_operation:"):
        await delete_operation_callback(update, context)
        return
    if (query.data or "").startswith("transfer:"):
        await transfer_callback(update, context)
        return
    callback_update = SimpleNamespace(message=query.message, effective_user=update.effective_user)
    if query.data.startswith("action:financial_mode:"):
        mode = query.data.removeprefix("action:financial_mode:")
        if mode in FINANCIAL_MODES:
            await set_financial_mode(callback_update, context, mode)
        return
    if query.data == "action:enable_daily_limit_reminder":
        await set_reminder_enabled(callback_update, context, True)
        return
    if query.data == "action:disable_daily_limit_reminder":
        await set_reminder_enabled(callback_update, context, False)
        return
    handlers = {
        "menu:add": add_menu,
        "menu:budget": budget_menu,
        "menu:debts": debt_menu,
        "menu:goals": goal_menu,
        "menu:analytics": analytics_menu,
        "menu:settings": settings_menu,
        "action:income": begin_income,
        "action:expense": begin_expense,
        "action:transfer": begin_transfer,
        "action:report": report,
        "action:operations": operations,
        "action:expenses": expenses,
        "action:incomes": incomes,
        "action:delete_operation": delete_operation,
        "action:set_plan": begin_set_plan,
        "action:plan": plan,
        "action:delete_plan": begin_delete_plan,
        "action:daily_limit": daily_limit,
        "action:set_daily_limit": begin_set_daily_limit,
        "action:delete_daily_limit": delete_daily_limit,
        "action:daily_limit_reminder": daily_limit_reminder,
        "action:set_daily_limit_reminder": begin_set_daily_limit_reminder,
        "action:keep_daily_limit": confirm_limit_today,
        "action:change_daily_limit_today": begin_daily_override,
        "action:disable_daily_limit_today": disable_limit_today,
        "action:add_debt": begin_add_debt,
        "action:debts": debts,
        "action:pay_debt": begin_pay_debt,
        "action:close_debt": begin_close_debt,
        "action:debt": begin_debt,
        "action:delete_debt": begin_delete_debt,
        "action:add_goal": begin_add_goal,
        "action:goals": goals,
        "action:fund_goal": begin_fund_goal,
        "action:goal": begin_goal,
        "action:delete_goal": begin_delete_goal,
        "action:forecast": forecast,
        "action:freedom": freedom,
        "action:health": health,
        "action:charts": charts,
        "action:insights": insights,
        "action:chart_expenses": chart_expenses,
        "action:chart_cashflow": chart_cashflow,
        "action:chart_plan": chart_plan,
        "action:chart_debts": chart_debts,
        "action:chart_goals": chart_goals,
        "action:setup_current_state": begin_setup_current_state,
        "action:new_month": new_month,
        "action:new_month_setup": begin_setup_current_state,
        "action:new_month_plan": new_month_plan,
        "action:new_month_cancel": cancel,
        "action:clear": clear_chat,
        "action:clear_confirm": confirm_clear_chat,
        "action:clear_cancel": cancel_clear_chat,
        "action:financial_mode": financial_mode_menu,
        "action:cancel": cancel,
    }
    handler = handlers.get(query.data)
    if handler is None:
        return
    await handler(callback_update, context)

"""Application entry point."""

import asyncio
import functools
import logging

from telegram import BotCommand, Message, Update
from telegram.ext import Application, CallbackQueryHandler, CommandHandler, ContextTypes, MessageHandler, TypeHandler, filters

from app.chat_history import MessageHistory
from app.config import Settings
from app.handlers import (
    add_menu, analytics_menu, begin_add_debt, begin_add_goal, begin_debt,
    begin_delete_debt, begin_delete_goal, begin_delete_plan, begin_expense,
    begin_fund_goal, begin_goal, begin_income, begin_transfer, begin_pay_debt, begin_close_debt, begin_set_plan,
    begin_setup_current_state, budget_menu, cancel, debt_menu, debts, forecast,
    freedom, goal_menu, goals, health, menu_callback, plan, report, save_transaction,
    settings_menu, start, clear_chat, new_month, operations, expenses, incomes,
    delete_operation, charts, insights, chart_expenses, chart_cashflow, chart_plan,
    chart_debts, chart_goals,
    begin_set_daily_limit, daily_limit, delete_daily_limit,
    daily_limit_reminder, begin_set_daily_limit_reminder,
)
from app.sheets import SheetsRepository
from app.scheduler import initialize_scheduler, shutdown_scheduler

BOT_COMMANDS = [
    BotCommand("start", "Главное меню"),
    BotCommand("add", "Добавить операцию"),
    BotCommand("transfer", "Перевод между счетами"),
    BotCommand("report", "Отчёт"),
    BotCommand("budget", "Бюджет"),
    BotCommand("set_daily_limit", "Установить дневной лимит"),
    BotCommand("daily_limit", "Дневной лимит"),
    BotCommand("delete_daily_limit", "Удалить дневной лимит"),
    BotCommand("daily_limit_reminder", "Настройки утреннего лимита"),
    BotCommand("set_daily_limit_reminder", "Изменить время утреннего лимита"),
    BotCommand("debt_menu", "Долги"),
    BotCommand("goal_menu", "Цели"),
    BotCommand("analytics", "Аналитика"),
    BotCommand("settings", "Настройки"),
    BotCommand("cancel", "Отмена"),
]

async def post_init(application: Application) -> None:
    await application.bot.set_my_commands(BOT_COMMANDS)
    await initialize_scheduler(application)

async def error_handler(update: object, context: ContextTypes.DEFAULT_TYPE) -> None:
    logging.getLogger(__name__).error("Unhandled exception while processing update %s", getattr(update, "update_id", None), exc_info=context.error)


async def track_incoming_message(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """Register user messages and connect a private chat to its user history."""
    if update.effective_user is None or update.effective_chat is None:
        return
    history: MessageHistory = context.bot_data["message_history"]
    history.register_chat(update.effective_chat.id, update.effective_user.id)
    if update.effective_message is not None and update.effective_message.from_user:
        if update.effective_message.from_user.id == update.effective_user.id:
            history.add(update.effective_user.id, update.effective_message.message_id)


def install_outgoing_message_tracker(application: Application) -> None:
    """Track messages sent through Message.reply_text without persisting any data."""
    if not hasattr(Message, "_finance_original_reply_text"):
        setattr(Message, "_finance_original_reply_text", Message.reply_text)
    original_reply_text = getattr(Message, "_finance_original_reply_text")

    @functools.wraps(original_reply_text)
    async def tracked_reply_text(message: Message, *args, **kwargs):
        reply = await original_reply_text(message, *args, **kwargs)
        history: MessageHistory = application.bot_data["message_history"]
        user_id = history.chat_users.get(message.chat_id)
        if user_id is not None:
            history.add(user_id, reply.message_id)
        return reply

    Message.reply_text = tracked_reply_text

def ensure_event_loop() -> None:
    try: asyncio.get_event_loop()
    except RuntimeError: asyncio.set_event_loop(asyncio.new_event_loop())

def main() -> None:
    logging.basicConfig(format="%(asctime)s %(levelname)s %(name)s: %(message)s", level=logging.INFO)
    settings = Settings.from_env()
    application = (Application.builder().token(settings.telegram_bot_token)
                   .post_init(post_init).post_shutdown(shutdown_scheduler).build())
    application.bot_data["repository"] = SheetsRepository(settings)
    application.bot_data["message_history"] = MessageHistory()
    install_outgoing_message_tracker(application)
    application.add_handler(TypeHandler(Update, track_incoming_message), group=-1)
    for command, handler in [
        ("start", start), ("add", add_menu), ("income", begin_income), ("expense", begin_expense), ("transfer", begin_transfer), ("report", report),
        ("budget", budget_menu), ("debt_menu", debt_menu), ("goal_menu", goal_menu),
        ("analytics", analytics_menu), ("settings", settings_menu),
        ("set_plan", begin_set_plan), ("plan", plan), ("delete_plan", begin_delete_plan),
        ("add_debt", begin_add_debt), ("debts", debts), ("pay_debt", begin_pay_debt), ("close_debt", begin_close_debt),
        ("debt", begin_debt), ("delete_debt", begin_delete_debt), ("add_goal", begin_add_goal),
        ("goals", goals), ("fund_goal", begin_fund_goal), ("goal", begin_goal),
        ("delete_goal", begin_delete_goal), ("forecast", forecast), ("freedom", freedom),
        ("health", health), ("setup_current_state", begin_setup_current_state), ("cancel", cancel),
        ("charts", charts), ("insights", insights), ("chart_expenses", chart_expenses),
        ("chart_cashflow", chart_cashflow), ("chart_plan", chart_plan),
        ("chart_debts", chart_debts), ("chart_goals", chart_goals),
        ("operations", operations), ("expenses", expenses), ("incomes", incomes),
        ("delete_operation", delete_operation),
        ("set_daily_limit", begin_set_daily_limit), ("daily_limit", daily_limit),
        ("delete_daily_limit", delete_daily_limit),
        ("daily_limit_reminder", daily_limit_reminder),
        ("set_daily_limit_reminder", begin_set_daily_limit_reminder),
        ("clear", clear_chat), ("new_month", new_month),
    ]: application.add_handler(CommandHandler(command, handler))
    application.add_handler(CallbackQueryHandler(menu_callback, pattern="^(menu|action|delete_operation|category|transfer):"))
    application.add_handler(MessageHandler(filters.Regex("^Доход$"), begin_income))
    application.add_handler(MessageHandler(filters.Regex("^Расход$"), begin_expense))
    application.add_handler(MessageHandler(filters.Regex("^Отчёт$"), report))
    application.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, save_transaction))
    application.add_error_handler(error_handler); ensure_event_loop(); application.run_polling(allowed_updates=Update.ALL_TYPES)

if __name__ == "__main__": main()

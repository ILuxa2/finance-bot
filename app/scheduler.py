"""Daily Moscow-time reminder scheduling for Telegram users."""

import asyncio
import logging
from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from telegram import InlineKeyboardButton, InlineKeyboardMarkup

from app.calculations import calculate_daily_limit, resolve_daily_limit

logger = logging.getLogger(__name__)
MOSCOW_TZ = ZoneInfo("Europe/Moscow")


def _job_id(user_id: int) -> str:
    return f"daily-limit-reminder-{user_id}"


def _format_amount(value: object) -> str:
    amount = value if isinstance(value, Decimal) else Decimal(str(value or 0))
    rendered = f"{amount:,.2f}" if amount % 1 else f"{amount:,.0f}"
    return f"{rendered.replace(',', ' ')} ₽"


async def send_daily_limit_reminder(application, user_id: int) -> None:
    repository = application.bot_data["repository"]
    now = datetime.now(MOSCOW_TZ)
    try:
        rows, monthly, override = await asyncio.gather(
            asyncio.to_thread(repository.get_transactions, user_id),
            asyncio.to_thread(repository.get_daily_limit, user_id, now.strftime("%Y-%m")),
            asyncio.to_thread(repository.get_daily_limit_override, user_id, now.strftime("%Y-%m-%d")),
        )
        effective = resolve_daily_limit(
            monthly.get("daily_limit") if monthly else None, override
        )
        usage = calculate_daily_limit(rows, effective["limit"], now=now)
        limit_text = (_format_amount(effective["limit"]) if effective["enabled"]
                      else "не установлен")
        await application.bot.send_message(
            chat_id=user_id,
            text=("🌅 Доброе утро!\n\nНужно ли установить дневной лимит на сегодня?\n\n"
                  f"Текущий лимит месяца: {limit_text} в день\n"
                  f"Уже потрачено сегодня: {_format_amount(usage['spent'])}"),
            reply_markup=InlineKeyboardMarkup([
                [InlineKeyboardButton("✅ Оставить лимит", callback_data="action:keep_daily_limit")],
                [InlineKeyboardButton("✏️ Изменить лимит на сегодня", callback_data="action:change_daily_limit_today")],
                [InlineKeyboardButton("🚫 Сегодня без лимита", callback_data="action:disable_daily_limit_today")],
            ]),
        )
    except Exception:
        logger.exception("Daily limit reminder failed for user %s", user_id)


def schedule_user_reminder(application, user_id: int, settings: dict[str, str]) -> None:
    scheduler: AsyncIOScheduler = application.bot_data["daily_limit_scheduler"]
    job_id = _job_id(user_id)
    enabled = str(settings.get("daily_limit_reminder_enabled", "")).lower() == "true"
    reminder_time = str(settings.get("daily_limit_reminder_time", ""))
    if not enabled or not reminder_time:
        if scheduler.get_job(job_id):
            scheduler.remove_job(job_id)
        return
    hour, minute = map(int, reminder_time.split(":"))
    scheduler.add_job(
        send_daily_limit_reminder,
        CronTrigger(hour=hour, minute=minute, timezone=MOSCOW_TZ),
        args=[application, user_id], id=job_id, replace_existing=True,
        coalesce=True, misfire_grace_time=300,
    )


async def initialize_scheduler(application) -> None:
    scheduler = AsyncIOScheduler(timezone=MOSCOW_TZ)
    application.bot_data["daily_limit_scheduler"] = scheduler
    repository = application.bot_data["repository"]
    try:
        settings_rows = await asyncio.to_thread(repository.get_reminder_settings)
        for settings in settings_rows:
            schedule_user_reminder(application, int(settings["telegram_user_id"]), settings)
    except Exception:
        logger.exception("Failed to load daily limit reminder settings")
    scheduler.start()


async def shutdown_scheduler(application) -> None:
    scheduler = application.bot_data.get("daily_limit_scheduler")
    if scheduler and scheduler.running:
        scheduler.shutdown(wait=False)

"""Application configuration loaded from environment variables."""

from dataclasses import dataclass
from pathlib import Path
from typing import Any
import json
import os

from dotenv import load_dotenv


load_dotenv()


@dataclass(frozen=True)
class Settings:
    telegram_bot_token: str
    spreadsheet_id: str
    service_account_json: dict[str, Any] | None
    service_account_file: Path | None
    worksheet_name: str

    @classmethod
    def from_env(cls) -> "Settings":
        required = {
            "TELEGRAM_BOT_TOKEN": os.getenv("TELEGRAM_BOT_TOKEN"),
            "GOOGLE_SHEETS_SPREADSHEET_ID": os.getenv("GOOGLE_SHEETS_SPREADSHEET_ID"),
        }
        missing = [name for name, value in required.items() if not value]
        service_account_json_raw = os.getenv("GOOGLE_SERVICE_ACCOUNT_JSON")
        service_account_file_raw = os.getenv("GOOGLE_SERVICE_ACCOUNT_FILE")
        if not service_account_json_raw and not service_account_file_raw:
            missing.append("GOOGLE_SERVICE_ACCOUNT_JSON or GOOGLE_SERVICE_ACCOUNT_FILE")
        if missing:
            raise ValueError(
                "Missing environment variables: " + ", ".join(missing) + ". Copy .env.example to .env."
            )

        service_account_json: dict[str, Any] | None = None
        if service_account_json_raw:
            try:
                parsed_credentials = json.loads(service_account_json_raw)
            except json.JSONDecodeError as error:
                raise ValueError("GOOGLE_SERVICE_ACCOUNT_JSON contains invalid JSON.") from error
            if not isinstance(parsed_credentials, dict):
                raise ValueError("GOOGLE_SERVICE_ACCOUNT_JSON must contain a JSON object.")
            service_account_json = parsed_credentials

        return cls(
            telegram_bot_token=required["TELEGRAM_BOT_TOKEN"] or "",
            spreadsheet_id=required["GOOGLE_SHEETS_SPREADSHEET_ID"] or "",
            service_account_json=service_account_json,
            service_account_file=(
                Path(service_account_file_raw) if not service_account_json and service_account_file_raw else None
            ),
            worksheet_name=os.getenv("GOOGLE_SHEETS_WORKSHEET", "Transactions"),
        )

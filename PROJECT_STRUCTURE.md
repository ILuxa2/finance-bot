# Структура проекта

```text
finance-bot/
├── app/
│   ├── __init__.py
│   ├── main.py
│   ├── config.py
│   ├── handlers.py
│   ├── sheets.py
│   ├── reports.py
│   ├── charts.py
│   └── chat_history.py
├── docs/
│   ├── ARCHITECTURE.md
│   ├── BUSINESS_LOGIC.md
│   ├── TEST_CASES.md
│   ├── ROADMAP.md
│   └── DEPLOYMENT.md
├── deploy/
│   └── finance-bot.service.example
├── scripts/
│   ├── install_service.bat
│   ├── start_service.bat
│   ├── stop_service.bat
│   ├── restart_service.bat
│   └── uninstall_service.bat
├── tools/nssm/
├── requirements.txt
├── start_bot.bat
├── stop_bot.bat
├── README.md
├── CHANGELOG.md
└── PROJECT_STRUCTURE.md
```

## Назначение

- `app/main.py` — сборка Telegram Application и точка входа.
- `app/config.py` — переменные окружения и credentials.
- `app/handlers.py` — команды, кнопки и пользовательские сценарии.
- `app/sheets.py` — слой доступа к Google Sheets.
- `app/reports.py` — расчёты и текстовые отчёты.
- `app/charts.py` — генерация PNG-диаграмм.
- `app/chat_history.py` — ограниченная in-memory история Telegram message ID.
- `docs/` — архитектура, правила расчётов, QA и эксплуатация.
- `deploy/` — шаблоны для серверного развёртывания.
- `scripts/` и `tools/nssm/` — управление Windows service.
- `requirements.txt` — Python-зависимости.
- `start_bot.bat`, `stop_bot.bat` — локальный Windows-запуск и остановка.

Файлы `.env`, service-account JSON, логи, временные диаграммы и виртуальное окружение являются локальными артефактами и не должны публиковаться.

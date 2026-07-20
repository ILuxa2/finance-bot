# Развёртывание

## Локальный запуск

Требуются Python 3.10+, интернет и доступ service account к Google Sheets.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m app.main
```

Перед запуском создайте `.env` по примеру в README. Процесс использует Telegram long polling, поэтому webhook и входящий HTTP-порт не нужны.

## Запуск через BAT

```powershell
.\start_bot.bat
```

Скрипт меняет каталог на корень проекта, включает UTF-8 code page и выполняет `python -m app.main`. `stop_bot.bat` завершает локальный процесс согласно своей реализации. Для предсказуемого выбора интерпретатора запускайте BAT из активированного venv либо добавьте venv Python в `PATH`.

## Windows autostart via Task Scheduler

Для Windows-автозапуска без Git Bash используйте скрипты из `scripts/windows/`.
Они вычисляют корень проекта через `%~dp0..\..`, поэтому не зависят от текущей
папки запуска.

Установка или обновление задачи:

```powershell
.\scripts\windows\install_autostart_task.bat
```

Скрипт создаёт задачу `FinanceOS Bot` в Windows Task Scheduler:

- триггер: вход пользователя в Windows;
- действие: `cmd.exe /c scripts\windows\start_bot.bat`;
- рабочая папка: корень проекта;
- запуск с наивысшими правами, если разрешено текущими правами пользователя;
- перезапуск при сбое: 3 попытки с интервалом 1 минута;
- повторный запуск скрипта обновляет существующую задачу.

Удаление задачи:

```powershell
.\scripts\windows\uninstall_autostart_task.bat
```

Удаляется только задача планировщика. Код проекта, `.env`, `credentials` и данные
Google Sheets не изменяются.

Ручное управление:

```powershell
.\scripts\windows\start_bot.bat
.\scripts\windows\stop_bot.bat
.\scripts\windows\restart_bot.bat
```

`start_bot.bat` создаёт `logs/`, если папки нет, и пишет stdout/stderr в
`logs/windows_stdout.log` и `logs/windows_stderr.log`. `stop_bot.bat` завершает
только процессы `python.exe` или `pythonw.exe`, у которых в `CommandLine` есть
`app.main`, чтобы не останавливать чужие Python-процессы.

Если бот не стартует, сначала проверьте:

- `logs/windows_stderr.log`;
- наличие `.env` в корне проекта;
- доступность JSON-ключа service account из `credentials`;
- что `python` доступен в `PATH` для пользователя Windows;
- что зависимости установлены: `pip install -r requirements.txt`.

## Windows service

В проекте предусмотрены скрипты `scripts/install_service.bat`, `start_service.bat`, `stop_service.bat`, `restart_service.bat` и `uninstall_service.bat`. Установка использует NSSM по пути `tools/nssm/nssm.exe`, требует права администратора, создаёт службу `FinanceBot`, запускает `python -m app.main` из корня проекта и пишет логи в `logs/`. До установки проверьте, что системный пользователь службы видит `.env`, credentials и нужный Python с зависимостями.

## Docker (будущее)

Планируется slim-образ Python с установкой `requirements.txt`, непривилегированным пользователем и командой `python -m app.main`. Секреты должны передаваться через environment/secret mount, а не встраиваться в образ. Для JSON service account предпочтителен secret-файл или `GOOGLE_SERVICE_ACCOUNT_JSON`.

## VPS (будущее)

Для Linux VPS подготовлен пример `deploy/finance-bot.service.example`. Предполагаемый путь — `/opt/finance-bot`, отдельный пользователь `financebot`, venv и `.env` с правами только владельца. После адаптации файла:

```bash
sudo cp deploy/finance-bot.service.example /etc/systemd/system/finance-bot.service
sudo systemctl daemon-reload
sudo systemctl enable --now finance-bot
sudo journalctl -u finance-bot -f
```

На хосте следует настроить обновления ОС, ротацию логов, резервное копирование таблицы и мониторинг перезапусков. Не храните ключ service account в Git.

# Публикация FinanceOS из GitHub

Ветка `codex/github-cloud-deploy` содержит актуальный код v0.18.15 и CI/CD. Подключение проверено успешным dry-run из main. Реальная загрузка новой версии через Actions ещё не выполнялась. Автоматической публикации по push нет.

## Как будет работать

1. Изменение кода в ветке → Pull Request → Java checks (62 исполняемых Java-набора и проверки скрипта публикации).
2. После слияния в main: Actions → Deploy Yandex Cloud → Run workflow → указать точную версию pom.xml.
3. Вначале оставить apply выключенным: проверяется вход и текущая конфигурация без создания версий.
4. Для публикации повторить запуск с apply включённым. Напоминания обновляются первыми для совместимости outbox, затем обработчик. Приёмник, триггеры, публичный доступ, хранилище и финансовые записи не меняются.
5. В отчёте Actions сохраняются commit, новые и предыдущие ID версий. Тесты обязательны перед публикацией, параллельные публикации запрещены.

## Однократное подключение (выполнено 16.11.2026)

Нужен отдельный сервисный аккаунт `financeos-github-deploy`. Применять команды после просмотра прав; личный OAuth-токен и ключи в GitHub не загружаются.

```powershell
yc iam service-account create --name financeos-github-deploy --folder-id b1gg84kgslh6vs2pe3q4
# Далее DEPLOY_SA_ID — ID только что созданного аккаунта.
yc serverless function add-access-binding d4ep8ea2jl4ldogoilcm --role functions.editor --subject serviceAccount:DEPLOY_SA_ID
yc serverless function add-access-binding d4ekbamg45gvbp1l8b6r --role functions.editor --subject serviceAccount:DEPLOY_SA_ID
yc iam service-account add-access-binding ajefkoc36mb0t28c0i5m --role iam.serviceAccounts.user --subject serviceAccount:DEPLOY_SA_ID
yc iam workload-identity oidc federation create --name financeos-github --folder-id b1gg84kgslh6vs2pe3q4 --issuer https://token.actions.githubusercontent.com --audiences https://github.com/ILuxa2 --jwks-url https://token.actions.githubusercontent.com/.well-known/jwks
yc iam workload-identity federated-credential create --service-account-id DEPLOY_SA_ID --federation-id FEDERATION_ID --external-subject-id repo:ILuxa2/finance-bot:environment:production
```

Права ограничены двумя существующими функциями и использованием прежнего runtime-аккаунта. Право использования runtime-аккаунта позволяет привязывать его к функциям; код, загруженный в эти функции, работает с его правами. Доверять следует только проверенному коду main. Доступ на чтение содержимого бакета или секрета непосредственно аккаунту публикации не выдаётся. Его роль functions.editor также позволяет изменять/удалять эти две функции — поэтому main и production должны быть защищены.

GitHub → Settings → Environments → production: выбрать разрешённую ветку main (Selected branches/tags), по желанию назначить обязательного проверяющего. В Environment variables окружения production сохранён `YC_DEPLOY_SA_ID` с ID аккаунта публикации. Это идентификатор, не секрет. Environment subject должен точно совпадать с указанным выше.

Сначала объединить PR с актуальным кодом, затем запустить apply=false. Если API вернёт отказ прав, добавлять только указанное недостающее право после проверки. Не назначать editor/admin на всё облако ради обхода ошибки.

## Ограничения и восстановление

- Старый main v0.9.0 нельзя публиковать поверх текущего облака. Сначала синхронизируется актуальный код.
- Развёртывание двух функций не является одной транзакцией. Если обработчик не загрузился, совместимая новая версия напоминаний остаётся активной. Статус каждой операции виден в Actions.
- При обрыве связи во время создания версии скрипт останавливается без повторного POST: сначала проверить операцию/последнюю версию.
- Прежние версии не удаляются. Откат требует проверки совместимости формата outbox; не переключать обработчик на старую версию вслепую после изменения схемы.
- Workflow не вызывает финансовые функции для smoke-теста, чтобы не отправлять реальные сообщения и не менять бюджет.
- Пакет сборки включает только классы приложения и Jackson. Ключи, токены, журналы, локальное состояние и личные резервные копии в GitHub/артефакт не входят.

Документация: https://yandex.cloud/en/docs/iam/tutorials/ci-cd-github-functions и https://yandex.cloud/en/docs/iam/operations/wlif/setup-wlif

## Результат подключения · 16.11.2026

- Аккаунт публикации: `aje4s25l711hdukfu88a` (`financeos-github-deploy`); постоянных ключей нет.
- Федерация: `ajef9bj6udadqu3oa89n`; credential `aje03mmeq3og6hjp3ehf`; subject только `repo:ILuxa2/finance-bot:environment:production`.
- Production: Selected branches and tags, разрешена только Branch `main`, тегов нет. ID аккаунта хранится как переменная production, секретов в этом окружении нет.
- Права: functions.editor только на обработчик и напоминания; iam.serviceAccounts.user на прежний runtime-аккаунт. Проверены после выдачи.
- [Успешная проверка подключения](https://github.com/ILuxa2/finance-bot/actions/runs/43906953115): 62 Java-набора и 2 проверки публикации прошли, OIDC принят Яндексом, обе текущие конфигурации прочитаны.
- Dry-run не создавал версий: напоминания `d4eju328ulntm8shjb1m`, обработчик `d4e1hncrr6jjo6v7n4vm` остались без изменений. Проверка реального создания версии будет при следующем разрешённом выпуске.

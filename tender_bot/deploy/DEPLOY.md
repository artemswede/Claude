# Развёртывание бота на VDS (FirstVDS / любой Linux-сервер)

Инструкция для Ubuntu 22.04/24.04 (по умолчанию у FirstVDS). Бот будет работать
24/7 и автоматически перезапускаться через systemd.

## 0. Подключиться к серверу по SSH

В письме от FirstVDS есть IP-адрес, логин (обычно `root`) и пароль.

```bash
ssh root@ВАШ_IP
```

Windows: используйте PowerShell (та же команда) или PuTTY.

## 1. Установить зависимости системы

```bash
apt update && apt -y upgrade
apt -y install python3 python3-venv python3-pip git
```

## 2. Создать отдельного пользователя для бота (безопаснее, чем root)

```bash
adduser --system --group --home /opt/tender-bot tenderbot
```

## 3. Скачать код

```bash
cd /opt/tender-bot
git clone https://github.com/artemswede/Claude.git .
git checkout claude/telegram-tender-api-4sRUX
```

> Репозиторий приватный — git попросит логин и **Personal Access Token** вместо
> пароля (GitHub → Settings → Developer settings → Personal access tokens →
> создать токен с правом `repo`). Либо просто загрузите папку `tender_bot` на
> сервер через `scp`.

## 4. Установить Python-зависимости

```bash
cd /opt/tender-bot/tender_bot
python3 -m venv .venv
.venv/bin/pip install --upgrade pip
.venv/bin/pip install -r requirements.txt
```

## 5. Настроить .env

```bash
cp .env.example .env
nano .env
```

Впишите токен и оставьте mock-источник, пока нет ключа агрегатора:

```ini
BOT_TOKEN=ваш_токен_от_BotFather
PROVIDER=mock
```

Сохранить в nano: `Ctrl+O`, `Enter`, затем `Ctrl+X`.

## 6. Проверить запуск вручную

```bash
.venv/bin/python run.py
```

Должно появиться `Бот запущен. Источник данных: mock`. Проверьте бота в Telegram
(`/start`, `/search`). Остановить: `Ctrl+C`.

## 7. Поставить под systemd (автозапуск 24/7)

```bash
# дать пользователю права на каталог
chown -R tenderbot:tenderbot /opt/tender-bot

# установить unit-файл
cp /opt/tender-bot/tender_bot/deploy/tender-bot.service /etc/systemd/system/

systemctl daemon-reload
systemctl enable --now tender-bot
```

Проверить статус и логи:

```bash
systemctl status tender-bot
journalctl -u tender-bot -f      # живые логи, выход — Ctrl+C
```

Теперь бот работает постоянно и переживёт перезагрузку сервера.

## Обновление кода в будущем

```bash
cd /opt/tender-bot/tender_bot
git pull
.venv/bin/pip install -r requirements.txt
systemctl restart tender-bot
```

## Когда появится ключ агрегатора (Seldon и т.п.)

Отредактируйте `.env`:

```ini
PROVIDER=aggregator
AGGREGATOR_BASE_URL=https://api.seldon.../v1
AGGREGATOR_API_KEY=ваш_ключ
```

и перезапустите: `systemctl restart tender-bot`.

## Полезные команды

| Действие              | Команда                              |
|-----------------------|--------------------------------------|
| Статус                | `systemctl status tender-bot`        |
| Логи (живые)          | `journalctl -u tender-bot -f`        |
| Перезапуск            | `systemctl restart tender-bot`       |
| Остановить            | `systemctl stop tender-bot`          |
| Запустить             | `systemctl start tender-bot`         |
| Отключить автозапуск  | `systemctl disable tender-bot`       |

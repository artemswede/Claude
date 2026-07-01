-- Настройки напоминаний на пользователе.
ALTER TABLE users ADD COLUMN reminders_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN last_reminder_date TEXT;

-- Этап 1: базовая схема ЕДОНДОН.
-- Хранилище: Cloudflare D1 (SQLite). Время — ISO-8601 строки (UTC).

CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  tg_id         INTEGER NOT NULL UNIQUE,
  created_at    TEXT    NOT NULL,
  -- профиль для расчёта целей
  sex           TEXT,                       -- 'male' | 'female'
  age           INTEGER,
  height_cm     REAL,
  weight_kg     REAL,
  activity      TEXT,                       -- 'sedentary'|'light'|'moderate'|'active'|'very_active'
  goal          TEXT,                       -- 'lose' | 'maintain' | 'gain'
  tz            TEXT    NOT NULL DEFAULT 'Europe/Moscow',
  -- цели КБЖУ (рассчитанные или скорректированные)
  goal_kcal     INTEGER,
  goal_prot     INTEGER,
  goal_fat      INTEGER,
  goal_carb     INTEGER,
  onboarded_at  TEXT                        -- NULL пока онбординг не завершён
);

CREATE TABLE IF NOT EXISTS food_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL REFERENCES users(id),
  ts          TEXT    NOT NULL,             -- время приёма (UTC)
  local_date  TEXT    NOT NULL,             -- YYYY-MM-DD в таймзоне пользователя (для «дня»)
  dish_name   TEXT    NOT NULL,
  kcal        REAL    NOT NULL,
  prot        REAL    NOT NULL,
  fat         REAL    NOT NULL,
  carb        REAL    NOT NULL,
  portion_g   REAL,
  confidence  REAL,                         -- уверенность распознавания 0..1
  source      TEXT    NOT NULL,             -- 'photo'|'menu'|'label'|'manual'|'manual_edit'
  photo_key   TEXT                          -- ключ в R2 (nullable, в MVP обычно NULL)
);
CREATE INDEX IF NOT EXISTS idx_food_user_date ON food_log(user_id, local_date);

CREATE TABLE IF NOT EXISTS state_log (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER NOT NULL REFERENCES users(id),
  ts            TEXT    NOT NULL,
  local_date    TEXT    NOT NULL,
  energy        INTEGER,                    -- шкалы 1..5 (NULL если не спрошено)
  sleepiness    INTEGER,
  hunger_spike  INTEGER,
  irritability  INTEGER,
  stress        INTEGER,
  mood_tag      TEXT,                       -- быстрый ярлык состояния ('Туплю','Бодр'...)
  note          TEXT
);
CREATE INDEX IF NOT EXISTS idx_state_user_date ON state_log(user_id, local_date);

CREATE TABLE IF NOT EXISTS weight_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL REFERENCES users(id),
  ts          TEXT    NOT NULL,
  local_date  TEXT    NOT NULL,
  weight_kg   REAL    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_weight_user_date ON weight_log(user_id, local_date);

CREATE TABLE IF NOT EXISTS recommendations (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL REFERENCES users(id),
  ts          TEXT    NOT NULL,
  meal        TEXT,                         -- 'lunch'|'dinner'|'snack'...
  status      TEXT,                         -- 'success'|'warning'|'danger'
  text        TEXT,
  basket_json TEXT                          -- сформированная корзина (JSON)
);

CREATE TABLE IF NOT EXISTS analytics_events (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER,
  ts          TEXT    NOT NULL,
  event       TEXT    NOT NULL,             -- 'photo','confirm','edit','state_checkin','rec_shown','basket_click','chat'...
  payload_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_event_ts ON analytics_events(event, ts);

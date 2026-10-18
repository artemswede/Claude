package com.obdlogger.core

object Strings {
    var locale = "ru"
        private set

    fun setLocale(l: String) {
        locale = l
        if (l == "en") applyEnglish() else applyRussian()
    }

    // Tabs
    var overview = "Обзор"
    var record = "Запись"
    var trips = "Поездки"
    var chat = "Чат"
    var settings = "Настройки"
    var back = "Назад"

    // Home / overview
    var allGood = "Всё в порядке"
    var attention = "Внимание"
    var checking = "Проверяю"
    var collecting = "Записываю данные"
    var waiting = "Ожидание"
    var noTrips = "Поездок пока нет"

    // Record
    var start = "Старт"
    var stop = "Стоп"
    var marker = "Метка"
    var recording = "Запись"
    var paused = "Пауза"
    var ready = "Готов к работе"

    // Connection
    var noConnection = "нет связи"
    var unknown = "неизвестно"
    var engineRunning = "мотор работает"
    var engineOff = "мотор выключен"
    var connecting = "подключение"
    var connected = "подключено"

    // Trips
    var trip = "Поездка"
    var duration = "Длительность"
    var distance = "Расстояние"
    var avgSpeed = "Средняя скорость"
    var maxSpeed = "Максимальная скорость"

    // Settings
    var autoRecord = "Автозапись поездок"
    var adapter = "Адаптер"
    var carProfile = "Профиль машины"
    var dtcCodes = "Коды ошибок ЭБУ"
    var ecuTests = "Тесты ЭБУ (Режим 06)"
    var theme = "Тема"
    var light = "Светлая"
    var dark = "Тёмная"
    var byTime = "По времени"
    var language = "Язык"
    var aboutApp = "О приложении"

    // AI
    var aiThinking = "ИИ думает…"
    var send = "Отправить"
    var askQuestion = "Задайте вопрос…"

    // Common
    var yes = "Да"
    var no = "Нет"
    var cancel = "Отмена"
    var ok = "OK"
    var error = "Ошибка"
    var retry = "Повторить"
    var noData = "Нет данных"

    private fun applyRussian() {
        overview = "Обзор"; record = "Запись"; trips = "Поездки"
        chat = "Чат"; settings = "Настройки"; back = "Назад"
        allGood = "Всё в порядке"; attention = "Внимание"
        checking = "Проверяю"; collecting = "Записываю данные"
        waiting = "Ожидание"; noTrips = "Поездок пока нет"
        start = "Старт"; stop = "Стоп"; marker = "Метка"
        recording = "Запись"; paused = "Пауза"; ready = "Готов к работе"
        noConnection = "нет связи"; unknown = "неизвестно"
        engineRunning = "мотор работает"; engineOff = "мотор выключен"
        connecting = "подключение"; connected = "подключено"
        trip = "Поездка"; duration = "Длительность"; distance = "Расстояние"
        avgSpeed = "Средняя скорость"; maxSpeed = "Максимальная скорость"
        autoRecord = "Автозапись поездок"; adapter = "Адаптер"
        carProfile = "Профиль машины"; dtcCodes = "Коды ошибок ЭБУ"
        ecuTests = "Тесты ЭБУ (Режим 06)"
        theme = "Тема"; light = "Светлая"; dark = "Тёмная"; byTime = "По времени"
        language = "Язык"; aboutApp = "О приложении"
        aiThinking = "ИИ думает…"; send = "Отправить"; askQuestion = "Задайте вопрос…"
        yes = "Да"; no = "Нет"; cancel = "Отмена"; ok = "OK"
        error = "Ошибка"; retry = "Повторить"; noData = "Нет данных"
    }

    private fun applyEnglish() {
        overview = "Overview"; record = "Record"; trips = "Trips"
        chat = "Chat"; settings = "Settings"; back = "Back"
        allGood = "All good"; attention = "Attention"
        checking = "Checking"; collecting = "Recording data"
        waiting = "Waiting"; noTrips = "No trips yet"
        start = "Start"; stop = "Stop"; marker = "Marker"
        recording = "Recording"; paused = "Paused"; ready = "Ready"
        noConnection = "no connection"; unknown = "unknown"
        engineRunning = "engine running"; engineOff = "engine off"
        connecting = "connecting"; connected = "connected"
        trip = "Trip"; duration = "Duration"; distance = "Distance"
        avgSpeed = "Average speed"; maxSpeed = "Max speed"
        autoRecord = "Auto-record trips"; adapter = "Adapter"
        carProfile = "Car profile"; dtcCodes = "ECU trouble codes"
        ecuTests = "ECU tests (Mode 06)"
        theme = "Theme"; light = "Light"; dark = "Dark"; byTime = "Auto"
        language = "Language"; aboutApp = "About"
        aiThinking = "AI thinking…"; send = "Send"; askQuestion = "Ask a question…"
        yes = "Yes"; no = "No"; cancel = "Cancel"; ok = "OK"
        error = "Error"; retry = "Retry"; noData = "No data"
    }
}

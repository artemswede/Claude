export const config = { runtime: "edge" };

const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const SERPER_API_KEY = process.env.SERPER_API_KEY;
const GEMINI_URL = `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=${GEMINI_API_KEY}`;

function needsSearch(text) {
  return /сегодня|сейчас|последн|свеж|актуальн|новост|погода|курс|цена|стоимость|когда выш|когда выход|когда будет|вышел ли|вышла ли|кто победил|кто выиграл|результат|счёт|счет|202[4-9]|203\d|текущ|недавн|вчера|завтра|в этом году|в прошлом году|последний фильм|новый фильм/.test(text.toLowerCase());
}

async function searchSerper(query) {
  if (!SERPER_API_KEY) return null;
  try {
    const res = await fetch("https://google.serper.dev/search", {
      method: "POST",
      headers: { "X-API-KEY": SERPER_API_KEY, "Content-Type": "application/json" },
      body: JSON.stringify({ q: query, gl: "ru", hl: "ru", num: 2 }),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const parts = [];
    if (data.answerBox) parts.push(data.answerBox.answer || data.answerBox.snippet || "");
    if (data.organic) for (const r of data.organic.slice(0, 2)) if (r.snippet) parts.push(r.snippet);
    return parts.length ? parts.join("\n") : null;
  } catch (e) {
    return null;
  }
}

function quickAnswer(text) {
  const t = text.toLowerCase().trim();
  if (/^(привет|здравствуй|хай|хелло|здарова|здорово|салют|йо|ку|добрый (день|вечер|утро))/.test(t))
    return "Привет! Чем могу помочь?";
  if (/^(как (ты|дела|жизнь|сам)|ты как|нормально)/.test(t))
    return "У меня всё отлично! Готов помочь. Спрашивай!";
  if (/^(спасибо|благодарю|спс|пасиб)/.test(t))
    return "Пожалуйста! Обращайся ещё.";
  if (/что (ты )?умеешь|что (ты )?можешь|что (ты )?делаешь|помощь|help/.test(t))
    return "Я умею отвечать на вопросы, искать информацию в интернете, решать задачки и просто болтать. Спрашивай что угодно!";
  if (/кто (ты|такой)|как (тебя )?зовут|твоё? имя/.test(t))
    return "Я Жожик — умный голосовой ассистент с доступом к интернету.";
  return null;
}

async function askGemini(userText, history, searchResults) {
  const contents = [];
  for (const msg of history) {
    contents.push({ role: "user", parts: [{ text: msg.u }] });
    contents.push({ role: "model", parts: [{ text: msg.a }] });
  }
  let prompt = userText;
  if (searchResults) prompt += "\n\nДанные из интернета:\n" + searchResults + "\n\nОтветь коротко.";
  contents.push({ role: "user", parts: [{ text: prompt }] });

  const res = await fetch(GEMINI_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      system_instruction: { parts: [{ text: "Ты голосовой ассистент Жожик. Твоё имя Жожик. Ты не Gemini и не Google. Отвечай коротко, 1-3 предложения, по-русски. Без маркдауна и списков." }] },
      contents,
      generationConfig: { maxOutputTokens: 200, temperature: 0.7 },
    }),
  });
  if (!res.ok) return "Ошибка Gemini: " + res.status;
  const data = await res.json();
  const parts = data.candidates?.[0]?.content?.parts;
  if (parts) {
    for (const p of parts) {
      if (p.text && !p.thought) return p.text;
    }
  }
  if (data.candidates?.[0]?.finishReason === "SAFETY") return "Извини, на этот вопрос я не могу ответить.";
  return "Не получилось ответить. Попробуй переформулировать.";
}

function withTimeout(promise, ms) {
  return Promise.race([
    promise.then(function(v) { return { ok: true, value: v }; }),
    new Promise(function(resolve) { setTimeout(function() { resolve({ ok: false }); }, ms); }),
  ]);
}

function isRetry(text) {
  return /^(ну что|готово|ответ|ну как|давай|жду|и$|ну\?|что там|ответил|думал|придумал|ну давай|скажи)/i.test(text.trim());
}

export default async function handler(request) {
  const headers = { "Content-Type": "application/json" };

  if (request.method !== "POST") {
    return new Response(JSON.stringify({ ok: true }), { headers });
  }

  try {
    var bodyText = await request.text();
    var body;
    try {
      body = JSON.parse(bodyText);
    } catch (e) {
      return new Response(JSON.stringify({
        response: { text: "Ошибка разбора запроса", tts: "Ошибка разбора запроса", end_session: false },
        version: "1.0"
      }), { headers });
    }

    if (!body || !body.request || !body.session) {
      return new Response(JSON.stringify({
        response: { text: "Некорректный запрос", tts: "Некорректный запрос", end_session: false },
        version: "1.0"
      }), { headers });
    }

    var userText = body.request.original_utterance || body.request.command || "";
    var state = (body.state && body.state.session) || {};
    var history = state.h || [];
    var pending = state.p || null;

    function respond(text, sessionState, end, tts) {
      var t = text.length > 1024 ? text.slice(0, 1021) + "..." : text;
      return new Response(JSON.stringify({
        response: { text: t, tts: tts || t, end_session: !!end },
        session_state: sessionState,
        version: "1.0"
      }), { headers });
    }

    if (body.session["new"] || !userText.trim()) {
      return respond("Привет! Я Жожик — умный ассистент с доступом к интернету. Спрашивай что угодно!", { h: [] });
    }

    var lower = userText.toLowerCase().trim();
    if (lower === "хватит" || lower === "стоп" || lower === "выход" || lower === "пока" || lower === "до свидания") {
      return respond("Пока! Было приятно поболтать.", { h: [] }, true);
    }

    var quick = quickAnswer(userText);
    if (quick) {
      var qh = history.slice(-3);
      qh.push({ u: userText, a: quick });
      return respond(quick, { h: qh });
    }

    var questionToAsk = userText;
    var searchForQuestion = userText;

    if (pending && isRetry(userText)) {
      questionToAsk = pending;
      searchForQuestion = pending;
    }

    var search = null;
    if (needsSearch(searchForQuestion)) {
      var searchResult = await withTimeout(searchSerper(searchForQuestion), 1000);
      if (searchResult.ok) search = searchResult.value;
    }

    var geminiResult = await withTimeout(
      askGemini(questionToAsk, history.slice(-3), search),
      3500
    );

    if (!geminiResult.ok) {
      return respond(
        "Хм, сложный вопрос! Думаю... Скажи \"ну что?\" через пару секунд.",
        { h: history, p: questionToAsk },
        false,
        "Хм, сложный вопрос! sil <[500]> Думаю... Скажи, ну что, через пару секунд."
      );
    }

    var answer = geminiResult.value;
    answer = answer.replace(/[*_#`~\[\]]/g, "").trim();
    var newHistory = history.slice(-3);
    newHistory.push({ u: questionToAsk, a: answer });
    return respond(answer, { h: newHistory });

  } catch (e) {
    return new Response(JSON.stringify({
      response: { text: "Что-то пошло не так: " + (e.message || "unknown"), tts: "Что-то пошло не так.", end_session: false },
      session_state: { h: [] },
      version: "1.0"
    }), { headers: { "Content-Type": "application/json" } });
  }
}

const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const SERPER_API_KEY = process.env.SERPER_API_KEY;
const GEMINI_URL = `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=${GEMINI_API_KEY}`;

const MAX_RESPONSE_LENGTH = 1024;

async function searchSerper(query) {
  if (!SERPER_API_KEY) return null;
  const res = await fetch("https://google.serper.dev/search", {
    method: "POST",
    headers: { "X-API-KEY": SERPER_API_KEY, "Content-Type": "application/json" },
    body: JSON.stringify({ q: query, gl: "ru", hl: "ru", num: 3 }),
    signal: AbortSignal.timeout(3000),
  });
  const data = await res.json();
  const snippets = [];
  if (data.answerBox) snippets.push(data.answerBox.answer || data.answerBox.snippet || "");
  if (data.organic) for (const item of data.organic.slice(0, 3)) if (item.snippet) snippets.push(item.snippet);
  return snippets.length > 0 ? snippets.join("\n") : null;
}

function needsSearch(text) {
  const lower = text.toLowerCase();
  return /сегодня|сейчас|последн|свеж|актуальн|новост|погода|курс|цена|стоимость|когда выш|когда выход|когда будет|вышел ли|вышла ли|кто победил|кто выиграл|результат|счёт|счет|202[4-9]|203\d|текущ|недавн|вчера|завтра|в этом году|в прошлом году|последний фильм|новый фильм/.test(lower);
}

async function askGemini(userText, history, searchResults) {
  const contents = [];
  for (const msg of history) {
    contents.push(
      { role: "user", parts: [{ text: msg.user }] },
      { role: "model", parts: [{ text: msg.assistant }] }
    );
  }

  let prompt = userText;
  if (searchResults) {
    prompt = `${userText}\n\nИнфо из интернета:\n${searchResults}\n\nОтветь коротко, 1-2 предложения.`;
  }
  contents.push({ role: "user", parts: [{ text: prompt }] });

  const res = await fetch(GEMINI_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      system_instruction: { parts: [{ text: "Ты голосовой ассистент. Отвечай коротко, 1-2 предложения, на русском. Без маркдауна и списков." }] },
      contents,
      generationConfig: { maxOutputTokens: 100, temperature: 0.7 },
    }),
    signal: AbortSignal.timeout(8000),
  });

  const data = await res.json();
  if (data.candidates?.[0]?.content) {
    return data.candidates[0].content.parts[0].text;
  }
  return "Не получилось найти ответ. Попробуй иначе.";
}

function truncate(text, maxLen) {
  if (text.length <= maxLen) return text;
  const cut = text.slice(0, maxLen - 3);
  const dot = cut.lastIndexOf(".");
  return dot > maxLen * 0.5 ? cut.slice(0, dot + 1) : cut + "...";
}

function aliceResponse(text, sessionState, endSession = false) {
  const t = truncate(text, MAX_RESPONSE_LENGTH);
  return { response: { text: t, tts: t, end_session: endSession }, session_state: sessionState, version: "1.0" };
}

async function handler(req, res) {
  if (req.method !== "POST") { res.status(200).json({ ok: true }); return; }

  try {
    const body = req.body;
    if (!body?.request || !body?.session) { res.status(400).json({ error: "Invalid" }); return; }

    const userText = body.request.original_utterance || body.request.command || "";
    const history = body.state?.session?.history || [];

    if (body.session.new || !userText.trim()) {
      return res.status(200).json(aliceResponse("Привет! Я умная Алиса с доступом к интернету. Спрашивай что угодно!", { history: [] }));
    }

    if (["хватит", "стоп", "выход", "пока", "до свидания"].includes(userText.toLowerCase().trim())) {
      return res.status(200).json(aliceResponse("Пока! Было приятно поболтать.", { history: [] }, true));
    }

    let searchResults = null;
    if (needsSearch(userText)) {
      try { searchResults = await searchSerper(userText); } catch {}
    }

    let answer;
    try {
      answer = await askGemini(userText, history.slice(-6), searchResults);
    } catch (err) {
      console.error("Gemini error:", err.message);
      answer = "Произошла ошибка. Попробуй ещё раз.";
    }

    answer = answer.replace(/[*_#`~\[\]]/g, "").trim();

    const newHistory = [...history.slice(-5), { user: userText, assistant: answer }];
    res.status(200).json(aliceResponse(answer, { history: newHistory }));
  } catch (err) {
    console.error("Handler error:", err);
    res.status(200).json(aliceResponse("Что-то пошло не так. Попробуй ещё раз.", { history: [] }));
  }
}

module.exports = handler;

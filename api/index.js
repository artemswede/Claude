export const config = { runtime: "edge" };

const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const SERPER_API_KEY = process.env.SERPER_API_KEY;
const GEMINI_URL = `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=${GEMINI_API_KEY}`;

function needsSearch(text) {
  return /сегодня|сейчас|последн|свеж|актуальн|новост|погода|курс|цена|стоимость|когда выш|когда выход|когда будет|вышел ли|вышла ли|кто победил|кто выиграл|результат|счёт|счет|202[4-9]|203\d|текущ|недавн|вчера|завтра|в этом году|в прошлом году|последний фильм|новый фильм/.test(text.toLowerCase());
}

async function searchSerper(query) {
  if (!SERPER_API_KEY) return null;
  const res = await fetch("https://google.serper.dev/search", {
    method: "POST",
    headers: { "X-API-KEY": SERPER_API_KEY, "Content-Type": "application/json" },
    body: JSON.stringify({ q: query, gl: "ru", hl: "ru", num: 3 }),
  });
  if (!res.ok) return null;
  const data = await res.json();
  const parts = [];
  if (data.answerBox) parts.push(data.answerBox.answer || data.answerBox.snippet || "");
  if (data.organic) for (const r of data.organic.slice(0, 3)) if (r.snippet) parts.push(r.snippet);
  return parts.length ? parts.join("\n") : null;
}

function quickAnswer(text) {
  const t = text.toLowerCase().trim();
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
      system_instruction: { parts: [{ text: "Ты голосовой ассистент Жожик. Твоё имя Жожик. Ты не Gemini и не Google. Отвечай коротко, 1-2 предложения, по-русски. Без маркдауна и списков." }] },
      contents,
      generationConfig: {
        maxOutputTokens: 256,
        temperature: 0.7,
        thinkingConfig: { thinkingBudget: 0 },
      },
    }),
  });
  if (!res.ok) return "Не получилось ответить. Попробуй переформулировать.";
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
    promise.then(v => ({ ok: true, value: v })),
    new Promise(resolve => setTimeout(() => resolve({ ok: false }), ms)),
  ]);
}

function json(body) {
  return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
}

function alice(text, state, end = false, tts = null) {
  const t = text.length > 1024 ? text.slice(0, 1021) + "..." : text;
  return json({
    response: { text: t, tts: tts || t, end_session: end },
    session_state: state,
    version: "1.0",
  });
}

function isRetry(text) {
  return /^(ну что|готово|ответ|ну как|давай|жду|и|ну\?|что там|ответил|думал|придумал|ну давай|скажи)/i.test(text.trim());
}

export default async function handler(request) {
  if (request.method !== "POST") return json({ ok: true });

  try {
    const body = await request.json();
    if (!body?.request || !body?.session) return json({ error: "Invalid" });

    const userText = body.request.original_utterance || body.request.command || "";
    const state = body.state?.session || {};
    const history = state.h || [];
    const pending = state.p || null;

    if (body.session.new || !userText.trim()) {
      return alice("Привет! Я Жожик — умный ассистент с доступом к интернету. Спрашивай что угодно!", { h: [] });
    }

    if (["хватит", "стоп", "выход", "пока", "до свидания"].includes(userText.toLowerCase().trim())) {
      return alice("Пока! Было приятно поболтать.", { h: [] }, true);
    }

    const quick = quickAnswer(userText);
    if (quick) return alice(quick, { h: [...history.slice(-3), { u: userText, a: quick }] });

    let questionToAsk = userText;
    let searchForQuestion = userText;

    if (pending && isRetry(userText)) {
      questionToAsk = pending;
      searchForQuestion = pending;
    }

    let search = null;
    if (needsSearch(searchForQuestion)) {
      const searchResult = await withTimeout(searchSerper(searchForQuestion), 1500);
      if (searchResult.ok) search = searchResult.value;
    }

    const geminiResult = await withTimeout(
      askGemini(questionToAsk, history.slice(-4), search),
      3500
    );

    if (!geminiResult.ok) {
      return alice(
        "Хм, сложный вопрос! Думаю... Скажи \"ну что?\" через пару секунд.",
        { h: history, p: questionToAsk },
        false,
        "Хм, сложный вопрос! sil <[500]> Думаю... Скажи, ну что, через пару секунд."
      );
    }

    let answer = geminiResult.value;
    answer = answer.replace(/[*_#`~\[\]]/g, "").trim();
    const newHistory = [...history.slice(-3), { u: questionToAsk, a: answer }];
    return alice(answer, { h: newHistory });
  } catch (e) {
    console.error("Error:", e);
    return alice("Что-то пошло не так.", { h: [] });
  }
}

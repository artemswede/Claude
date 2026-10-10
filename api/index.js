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
  const data = await res.json();
  return data.candidates?.[0]?.content?.parts?.[0]?.text || "Не получилось ответить.";
}

function json(body) {
  return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" } });
}

function alice(text, history, end = false) {
  const t = text.length > 1024 ? text.slice(0, 1021) + "..." : text;
  return json({ response: { text: t, tts: t, end_session: end }, session_state: { h: history }, version: "1.0" });
}

export default async function handler(request) {
  if (request.method !== "POST") return json({ ok: true });

  try {
    const body = await request.json();
    if (!body?.request || !body?.session) return json({ error: "Invalid" });

    const userText = body.request.original_utterance || body.request.command || "";
    const history = body.state?.session?.h || [];

    if (body.session.new || !userText.trim()) {
      return alice("Привет! Я умная Алиса с доступом к интернету. Спрашивай что угодно!", []);
    }

    if (["хватит", "стоп", "выход", "пока", "до свидания"].includes(userText.toLowerCase().trim())) {
      return alice("Пока! Было приятно поболтать.", [], true);
    }

    let search = null;
    if (needsSearch(userText)) {
      try { search = await searchSerper(userText); } catch {}
    }

    let answer;
    try {
      answer = await askGemini(userText, history.slice(-4), search);
    } catch (e) {
      console.error("Gemini:", e.message);
      answer = "Ошибка. Попробуй ещё раз.";
    }

    answer = answer.replace(/[*_#`~\[\]]/g, "").trim();
    const newHistory = [...history.slice(-3), { u: userText, a: answer }];
    return alice(answer, newHistory);
  } catch (e) {
    console.error("Error:", e);
    return alice("Что-то пошло не так.", []);
  }
}

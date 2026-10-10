const https = require("https");

const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const SERPER_API_KEY = process.env.SERPER_API_KEY;

const GEMINI_MODEL = "gemini-2.0-flash";
const GEMINI_URL = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${GEMINI_API_KEY}`;

const MAX_HISTORY = 10;
const MAX_RESPONSE_LENGTH = 1024;

const sessions = new Map();

function cleanSessions() {
  const now = Date.now();
  for (const [id, data] of sessions) {
    if (now - data.lastAccess > 30 * 60 * 1000) {
      sessions.delete(id);
    }
  }
}

setInterval(cleanSessions, 5 * 60 * 1000);

function getSession(sessionId) {
  if (!sessions.has(sessionId)) {
    sessions.set(sessionId, { history: [], lastAccess: Date.now() });
  }
  const session = sessions.get(sessionId);
  session.lastAccess = Date.now();
  return session;
}

function httpRequest(url, options, body) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, options, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          try {
            resolve(JSON.parse(data));
          } catch {
            reject(new Error(`JSON parse error: ${data.slice(0, 200)}`));
          }
        } else {
          reject(new Error(`HTTP ${res.statusCode}: ${data.slice(0, 300)}`));
        }
      });
    });
    req.on("error", reject);
    req.setTimeout(8000, () => {
      req.destroy();
      reject(new Error("Request timeout"));
    });
    if (body) req.write(body);
    req.end();
  });
}

async function searchSerper(query) {
  if (!SERPER_API_KEY) return null;

  const body = JSON.stringify({ q: query, gl: "ru", hl: "ru", num: 3 });

  const url = new URL("https://google.serper.dev/search");
  const result = await httpRequest(
    url,
    {
      method: "POST",
      headers: {
        "X-API-KEY": SERPER_API_KEY,
        "Content-Type": "application/json",
      },
    },
    body
  );

  const snippets = [];

  if (result.answerBox) {
    snippets.push(result.answerBox.answer || result.answerBox.snippet || "");
  }

  if (result.organic) {
    for (const item of result.organic.slice(0, 3)) {
      if (item.snippet) snippets.push(item.snippet);
    }
  }

  if (result.knowledgeGraph && result.knowledgeGraph.description) {
    snippets.push(result.knowledgeGraph.description);
  }

  return snippets.length > 0 ? snippets.join("\n\n") : null;
}

function needsSearch(text) {
  const lower = text.toLowerCase();

  const searchPatterns = [
    /\bсегодня\b/,
    /\bсейчас\b/,
    /\bпоследн/,
    /\bсвеж/,
    /\bактуальн/,
    /\bновост/,
    /\bпогода\b/,
    /\bкурс\b/,
    /\bцена\b/,
    /\bстоимость/,
    /\bкогда выш/,
    /\bкогда выход/,
    /\bкогда будет\b/,
    /\bвышел ли\b/,
    /\bвышла ли\b/,
    /\bкто победил/,
    /\bкто выиграл/,
    /\bрезультат\b/,
    /\bсчёт\b/,
    /\bсчет\b/,
    /\b202[4-9]\b/,
    /\b203\d\b/,
    /\bтекущ/,
    /\bнедавн/,
    /\bна этой неделе\b/,
    /\bна прошлой неделе\b/,
    /\bвчера\b/,
    /\bзавтра\b/,
    /\bв этом году\b/,
    /\bв прошлом году\b/,
    /\bпоследний фильм\b/,
    /\bновый фильм\b/,
    /\bноваяверсия\b/,
    /\bпоследняя версия\b/,
  ];

  return searchPatterns.some((pattern) => pattern.test(lower));
}

async function askGemini(userText, session, searchResults) {
  const systemPrompt = `Ты — умный голосовой ассистент Алиса. Отвечай коротко, по делу, не больше 2-3 предложений. Говори на русском языке. Не используй маркдаун, списки, звёздочки и специальное форматирование — только обычный текст. Если пользователь здоровается, поздоровайся в ответ.`;

  const contents = [];

  for (const msg of session.history) {
    contents.push(
      { role: "user", parts: [{ text: msg.user }] },
      { role: "model", parts: [{ text: msg.assistant }] }
    );
  }

  let prompt = userText;
  if (searchResults) {
    prompt = `Вопрос пользователя: ${userText}\n\nАктуальная информация из интернета:\n${searchResults}\n\nИспользуй эту информацию для ответа. Отвечай коротко, 2-3 предложения.`;
  }

  contents.push({ role: "user", parts: [{ text: prompt }] });

  const requestBody = JSON.stringify({
    system_instruction: {
      parts: [{ text: systemPrompt }],
    },
    contents,
    generationConfig: {
      maxOutputTokens: 200,
      temperature: 0.7,
    },
  });

  const url = new URL(GEMINI_URL);
  const result = await httpRequest(
    url,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
    },
    requestBody
  );

  if (
    result.candidates &&
    result.candidates[0] &&
    result.candidates[0].content
  ) {
    return result.candidates[0].content.parts[0].text;
  }

  return "Извини, не получилось найти ответ. Попробуй спросить иначе.";
}

function truncate(text, maxLen) {
  if (text.length <= maxLen) return text;
  const truncated = text.slice(0, maxLen - 3);
  const lastSentence = truncated.lastIndexOf(".");
  if (lastSentence > maxLen * 0.5) {
    return truncated.slice(0, lastSentence + 1);
  }
  return truncated + "...";
}

function buildAliceResponse(text, sessionState, endSession = false) {
  return {
    response: {
      text: truncate(text, MAX_RESPONSE_LENGTH),
      tts: truncate(text, MAX_RESPONSE_LENGTH),
      end_session: endSession,
    },
    session_state: sessionState,
    version: "1.0",
  };
}

module.exports = async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.status(200).end();
    return;
  }

  if (req.method !== "POST") {
    res.status(405).json({ error: "Method not allowed" });
    return;
  }

  try {
    const body = req.body;

    if (!body || !body.request || !body.session) {
      res.status(400).json({ error: "Invalid request" });
      return;
    }

    const sessionId = body.session.session_id;
    const userText = body.request.original_utterance || body.request.command || "";
    const isNewSession = body.session.new;

    const session = getSession(sessionId);

    if (body.state && body.state.session) {
      const savedHistory = body.state.session.history;
      if (savedHistory && Array.isArray(savedHistory)) {
        session.history = savedHistory;
      }
    }

    if (isNewSession || !userText.trim()) {
      const greeting = "Привет! Я умная Алиса с доступом к интернету. Спрашивай что угодно!";
      session.history = [];
      res.status(200).json(
        buildAliceResponse(greeting, { history: [] })
      );
      return;
    }

    const exitPhrases = ["хватит", "стоп", "выход", "пока", "до свидания"];
    if (exitPhrases.includes(userText.toLowerCase().trim())) {
      sessions.delete(sessionId);
      res.status(200).json(
        buildAliceResponse("Пока! Было приятно поболтать.", { history: [] }, true)
      );
      return;
    }

    let searchResults = null;
    if (needsSearch(userText)) {
      try {
        searchResults = await searchSerper(userText);
      } catch {
        // search failed, continue without it
      }
    }

    let answer;
    try {
      answer = await askGemini(userText, session, searchResults);
    } catch (err) {
      answer = "Произошла ошибка при обработке запроса. Попробуй ещё раз.";
      console.error("Gemini error:", err.message);
    }

    answer = answer.replace(/[*_#`~\[\]]/g, "").trim();

    session.history.push({ user: userText, assistant: answer });
    if (session.history.length > MAX_HISTORY) {
      session.history = session.history.slice(-MAX_HISTORY);
    }

    res.status(200).json(
      buildAliceResponse(answer, { history: session.history })
    );
  } catch (err) {
    console.error("Handler error:", err);
    res.status(200).json(
      buildAliceResponse(
        "Что-то пошло не так. Попробуй ещё раз.",
        { history: [] }
      )
    );
  }
};

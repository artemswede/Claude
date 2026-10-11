export const config = { runtime: "edge" };

export default async function handler(request) {
  var ctype = { "Content-Type": "application/json" };

  if (request.method !== "POST") {
    return new Response(JSON.stringify({ ok: true, method: request.method }), { headers: ctype });
  }

  try {
    var raw = "";
    try {
      raw = await request.text();
    } catch (e) {
      return new Response(JSON.stringify({
        response: { text: "read error: " + String(e), tts: "ошибка чтения", end_session: false },
        version: "1.0"
      }), { headers: ctype });
    }

    var body = null;
    try {
      body = JSON.parse(raw);
    } catch (e) {
      return new Response(JSON.stringify({
        response: { text: "parse error, got: " + raw.slice(0, 100), tts: "ошибка парсинга", end_session: false },
        version: "1.0"
      }), { headers: ctype });
    }

    var cmd = "пусто";
    if (body && body.request) {
      cmd = body.request.command || body.request.original_utterance || "пусто";
    }

    return new Response(JSON.stringify({
      response: { text: "Эхо: " + cmd, tts: "Эхо: " + cmd, end_session: false },
      version: "1.0"
    }), { headers: ctype });

  } catch (e) {
    return new Response(JSON.stringify({
      response: { text: "catch: " + String(e), tts: "ошибка", end_session: false },
      version: "1.0"
    }), { headers: ctype });
  }
}

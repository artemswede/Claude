export default async function handler(req, res) {
  if (req.method !== "POST") {
    return res.status(200).json({ ok: true, v: 3 });
  }

  try {
    var body = req.body;
    var cmd = "пусто";
    if (body && body.request) {
      cmd = body.request.command || body.request.original_utterance || "пусто";
    }

    return res.status(200).json({
      response: { text: "Эхо: " + cmd, tts: "Эхо " + cmd, end_session: false },
      version: "1.0"
    });
  } catch (e) {
    return res.status(200).json({
      response: { text: "Ошибка: " + String(e), tts: "ошибка", end_session: false },
      version: "1.0"
    });
  }
}

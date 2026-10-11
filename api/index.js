export default async function handler(req, res) {
  return res.status(200).json({
    response: {
      text: "Метод: " + req.method + " v4",
      tts: "Работает",
      end_session: false
    },
    version: "1.0"
  });
}

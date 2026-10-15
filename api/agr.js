const API_BASE = 'https://agr-test.kgk-global.com/api/reports';
const TOKEN = process.env.AGR_TOKEN || '788d5f7bafb462b7849ed3ed42356b9c';

const cache = new Map();
const CACHE_TTL = 30000;

const ALLOWED_REPORTS = [
  'route/detail', 'route/summary', 'trips/summary',
  'zones', 'mileage/summary', 'fuel/detail', 'fuel/summary'
];

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'GET only' });

  const { report, ...params } = req.query;
  if (!report || !ALLOWED_REPORTS.includes(report)) {
    return res.status(400).json({ error: 'Invalid report', allowed: ALLOWED_REPORTS });
  }

  const qs = new URLSearchParams({ ...params, token: TOKEN }).toString();
  const url = `${API_BASE}/${report}?${qs}`;

  const cached = cache.get(url);
  if (cached && Date.now() - cached.ts < CACHE_TTL) {
    return res.json(cached.data);
  }

  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      return res.status(resp.status).json({ error: `AGR returned ${resp.status}` });
    }
    const data = await resp.json();
    cache.set(url, { data, ts: Date.now() });
    if (cache.size > 100) {
      const oldest = cache.keys().next().value;
      cache.delete(oldest);
    }
    return res.json(data);
  } catch (e) {
    return res.status(502).json({ error: 'AGR unreachable', detail: e.message });
  }
};

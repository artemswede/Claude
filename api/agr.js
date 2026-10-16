const AGR_BASE = 'https://agr-test.kgk-global.com/api/reports';
const AGR_TOKEN = process.env.AGR_TOKEN;

const PACKAPI_BASE = 'https://packapi.kgk-global.com/api/v1';
const PACKAPI_AUTH = process.env.PACKAPI_AUTH;
const PACKAPI_GROUP = process.env.PACKAPI_GROUP || '10941';

const cache = new Map();
const CACHE_TTL = 5000;

const ALLOWED_REPORTS = [
  'route/detail', 'route/summary', 'trips/summary',
  'zones', 'mileage', 'mileage/summary', 'fuel/detail', 'fuel/summary',
  'trackers',
];

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'GET only' });

  const { report, source, ...params } = req.query;
  if (!report || !ALLOWED_REPORTS.includes(report)) {
    return res.status(400).json({ error: 'Invalid report', allowed: ALLOWED_REPORTS });
  }

  // PackAPI real-time trackers
  if (report === 'trackers' || source === 'packapi') {
    const cacheKey = 'packapi_trackers';
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.ts < CACHE_TTL) {
      return res.json(cached.data);
    }
    try {
      const url = `${PACKAPI_BASE}/get_trackers?group_id=${PACKAPI_GROUP}`;
      const resp = await fetch(url, {
        headers: { 'Authorization': PACKAPI_AUTH }
      });
      if (!resp.ok) {
        const text = await resp.text();
        return res.status(resp.status).json({ error: `PackAPI ${resp.status}`, detail: text });
      }
      const data = await resp.json();
      cache.set(cacheKey, { data, ts: Date.now() });
      return res.json(data);
    } catch (e) {
      return res.status(502).json({ error: 'PackAPI unreachable', detail: e.message });
    }
  }

  // Legacy AGR reports
  const qs = new URLSearchParams({ ...params, token: AGR_TOKEN }).toString();
  const cacheKey = `${report}?${qs}`;
  const cached = cache.get(cacheKey);
  if (cached && Date.now() - cached.ts < CACHE_TTL) {
    return res.json(cached.data);
  }

  const url = `${AGR_BASE}/${report}?${qs}`;
  try {
    const resp = await fetch(url);
    if (!resp.ok) {
      const text = await resp.text();
      return res.status(resp.status).json({ error: `AGR ${resp.status}`, detail: text });
    }
    const data = await resp.json();
    cache.set(cacheKey, { data, ts: Date.now() });
    if (cache.size > 100) {
      const oldest = cache.keys().next().value;
      cache.delete(oldest);
    }
    return res.json(data);
  } catch (e) {
    return res.status(502).json({ error: 'AGR unreachable', detail: e.message });
  }
};

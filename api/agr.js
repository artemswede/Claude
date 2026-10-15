// Try production first (real-time data), fall back to test server
const API_HOSTS = [
  'https://monitor.kgk-global.com/api/reports',
  'https://agr.kgk-global.com/api/reports',
  'https://agr-test.kgk-global.com/api/reports',
];
const TOKEN = process.env.AGR_TOKEN || '788d5f7bafb462b7849ed3ed42356b9c';

const cache = new Map();
const CACHE_TTL = 10000;

const ALLOWED_REPORTS = [
  'route/detail', 'route/summary', 'trips/summary',
  'zones', 'mileage', 'mileage/summary', 'fuel/detail', 'fuel/summary'
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
  const cacheKey = `${report}?${qs}`;

  const cached = cache.get(cacheKey);
  if (cached && Date.now() - cached.ts < CACHE_TTL) {
    return res.json(cached.data);
  }

  const errors = [];
  for (const base of API_HOSTS) {
    const url = `${base}/${report}?${qs}`;
    try {
      const resp = await fetch(url, { signal: AbortSignal.timeout(12000) });
      if (!resp.ok) {
        errors.push(`${base}: ${resp.status}`);
        continue;
      }
      const data = await resp.json();
      cache.set(cacheKey, { data, ts: Date.now() });
      if (cache.size > 100) {
        const oldest = cache.keys().next().value;
        cache.delete(oldest);
      }
      res.setHeader('X-AGR-Source', base);
      return res.json(data);
    } catch (e) {
      errors.push(`${base}: ${e.message}`);
    }
  }
  return res.status(502).json({ error: 'All AGR hosts unreachable', details: errors });
};

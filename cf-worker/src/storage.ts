/**
 * Хранилище на Cloudflare KV: подписки и дедупликация рассылки.
 *
 * Ключи:
 *   sub:<chatId>          -> "1"          (активная подписка)
 *   seen:<chatId>         -> JSON string[] (id уже отправленных тендеров, с ограничением)
 */

const SEEN_CAP = 500; // сколько последних id храним на чат

export class Storage {
  constructor(private kv: KVNamespace) {}

  // --- подписки ---
  async subscribe(chatId: number): Promise<boolean> {
    const key = `sub:${chatId}`;
    const existing = await this.kv.get(key);
    if (existing) return false;
    await this.kv.put(key, "1");
    return true;
  }

  async unsubscribe(chatId: number): Promise<boolean> {
    const key = `sub:${chatId}`;
    const existing = await this.kv.get(key);
    if (!existing) return false;
    await this.kv.delete(key);
    return true;
  }

  async isSubscribed(chatId: number): Promise<boolean> {
    return (await this.kv.get(`sub:${chatId}`)) != null;
  }

  async listSubscribers(): Promise<number[]> {
    const ids: number[] = [];
    let cursor: string | undefined;
    do {
      const res = await this.kv.list({ prefix: "sub:", cursor });
      for (const k of res.keys) {
        const id = parseInt(k.name.slice("sub:".length), 10);
        if (!isNaN(id)) ids.push(id);
      }
      cursor = res.list_complete ? undefined : res.cursor;
    } while (cursor);
    return ids;
  }

  // --- дедупликация ---
  private async getSeen(chatId: number): Promise<Set<string>> {
    const raw = await this.kv.get(`seen:${chatId}`);
    if (!raw) return new Set();
    try {
      return new Set(JSON.parse(raw) as string[]);
    } catch {
      return new Set();
    }
  }

  async filterUnseen(chatId: number, ids: string[]): Promise<string[]> {
    if (ids.length === 0) return [];
    const seen = await this.getSeen(chatId);
    return ids.filter((id) => !seen.has(id));
  }

  async markSeen(chatId: number, ids: string[]): Promise<void> {
    if (ids.length === 0) return;
    const seen = await this.getSeen(chatId);
    for (const id of ids) seen.add(id);
    // Храним только последние SEEN_CAP записей.
    const arr = Array.from(seen).slice(-SEEN_CAP);
    await this.kv.put(`seen:${chatId}`, JSON.stringify(arr));
  }
}

/**
 * Project 1950 — Cloudflare Worker (TypeScript).
 *
 * Это пустой каркас. Бизнес-логика НЕ реализуется до прохождения шагов 1–6
 * из docs/WORKFLOW.md (требования → архитектура → аудит → заморозка → тесты → стандарты).
 */

export interface Env {
  // Переменные окружения и привязки появятся на этапе архитектуры (Шаг 2).
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    return new Response("Project 1950 — Cloudflare Worker is running.", {
      headers: { "content-type": "text/plain; charset=utf-8" },
    });
  },
} satisfies ExportedHandler<Env>;

/** Скачивание файлов (фото) из Telegram по file_id. */

/**
 * Возвращает байты файла Telegram. Двухшаговый процесс:
 * getFile → file_path, затем загрузка с file API.
 * Токен не логируем (он в URL загрузки).
 */
export async function downloadTelegramFile(
  botToken: string,
  fileId: string,
): Promise<Uint8Array> {
  const infoRes = await fetch(
    `https://api.telegram.org/bot${botToken}/getFile?file_id=${encodeURIComponent(fileId)}`,
  );
  const info = (await infoRes.json()) as {
    ok: boolean;
    result?: { file_path?: string };
  };
  if (!info.ok || !info.result?.file_path) {
    throw new Error("getFile failed");
  }

  const fileRes = await fetch(
    `https://api.telegram.org/file/bot${botToken}/${info.result.file_path}`,
  );
  if (!fileRes.ok) throw new Error(`file download failed: ${fileRes.status}`);

  const buf = await fileRes.arrayBuffer();
  return new Uint8Array(buf);
}

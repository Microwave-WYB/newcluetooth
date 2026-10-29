// Downloads one large file as N concurrent HTTP Range segments. A failed segment is retried on
// its own, resuming from the bytes it already has; the parts are checked for size and then
// joined. If-Range ties every segment to the ETag (or Last-Modified) seen by the HEAD request,
// so a file that changes mid-download fails instead of being stitched from two versions.
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, rename, rm, stat, truncate } from "node:fs/promises";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";

export class DownloadError extends Error {}

const sizeOf = async (path) => (await stat(path).catch(() => ({ size: 0 }))).size;

/** HEAD the URL; returns the fields the daily check compares. */
export async function head(url, { userAgent, fetchImpl = fetch } = {}) {
  const res = await fetchImpl(url, { method: "HEAD", headers: { "user-agent": userAgent } });
  if (!res.ok) throw new DownloadError(`HEAD ${url}: HTTP ${res.status}`);
  const len = res.headers.get("content-length");
  return {
    etag: res.headers.get("etag"),
    lastModified: res.headers.get("last-modified"),
    contentLength: len === null ? null : Number(len),
    acceptRanges: (res.headers.get("accept-ranges") ?? "").includes("bytes"),
  };
}

/**
 * Downloads `url` (whose HEAD answer is `meta`) to `dest`. Parts go to `${dest}.parts/` and are
 * removed afterwards, whether or not the download succeeded.
 */
export async function download(url, dest, meta, opts = {}) {
  const {
    segments = 10,
    retries = 5,
    stallMs = 60_000,
    backoffMs = 2_000,
    userAgent,
    fetchImpl = fetch,
    log = () => {},
  } = opts;
  const size = meta.contentLength;
  if (!Number.isSafeInteger(size) || size <= 0) throw new DownloadError("upstream sent no usable Content-Length");
  if (!meta.acceptRanges) throw new DownloadError("upstream does not accept byte ranges");
  const validator = meta.etag ?? meta.lastModified;

  const partDir = `${dest}.parts`;
  await rm(partDir, { recursive: true, force: true });
  await mkdir(partDir, { recursive: true });
  const per = Math.ceil(size / segments);
  const ranges = [];
  for (let start = 0; start < size; start += per) ranges.push([start, Math.min(size, start + per) - 1]);

  async function fetchSegment(i, start, end) {
    const file = `${partDir}/${i}`;
    const want = end - start + 1;
    let lastError;
    for (let attempt = 1; attempt <= retries; attempt++) {
      let have = await sizeOf(file);
      if (have > want) {
        await truncate(file, 0);
        have = 0;
      }
      if (have === want) return;
      const abort = new AbortController();
      let timer = setTimeout(() => abort.abort(), stallMs);
      try {
        const headers = { range: `bytes=${start + have}-${end}`, "accept-encoding": "identity", "user-agent": userAgent };
        if (validator) headers["if-range"] = validator;
        const res = await fetchImpl(url, { headers, signal: abort.signal });
        if (res.status === 200) {
          // If-Range failed: the file changed since HEAD. Retrying cannot help.
          await res.body?.cancel();
          throw Object.assign(new DownloadError(`segment ${i}: upstream file changed during the download`), { fatal: true });
        }
        if (res.status !== 206) throw new DownloadError(`segment ${i}: HTTP ${res.status}`);
        const cr = res.headers.get("content-range") ?? "";
        if (!cr.startsWith(`bytes ${start + have}-${end}/`)) throw new DownloadError(`segment ${i}: unexpected Content-Range ${cr}`);
        const stallReset = new Transform({
          transform(chunk, _enc, cb) {
            clearTimeout(timer);
            timer = setTimeout(() => abort.abort(), stallMs);
            cb(null, chunk);
          },
        });
        await pipeline(Readable.fromWeb(res.body), stallReset, createWriteStream(file, { flags: "a" }));
        if ((await sizeOf(file)) === want) return;
        throw new DownloadError(`segment ${i}: short (${await sizeOf(file)} of ${want} bytes)`);
      } catch (e) {
        if (e.fatal) throw e;
        lastError = e;
        log({ event: "segment-retry", segment: i, attempt, have: await sizeOf(file), want, error: String(e.message ?? e).slice(0, 200) });
        if (attempt < retries) await new Promise((r) => setTimeout(r, backoffMs * attempt));
      } finally {
        clearTimeout(timer);
      }
    }
    throw new DownloadError(`segment ${i} incomplete after ${retries} attempts: ${lastError?.message ?? lastError}`);
  }

  try {
    const results = await Promise.allSettled(ranges.map(([s, e], i) => fetchSegment(i, s, e)));
    const failed = results.filter((r) => r.status === "rejected");
    if (failed.length) throw failed.find((r) => r.reason?.fatal)?.reason ?? failed[0].reason;

    let total = 0;
    for (let i = 0; i < ranges.length; i++) total += await sizeOf(`${partDir}/${i}`);
    if (total !== size) throw new DownloadError(`parts add up to ${total} bytes, expected ${size}`);

    const tmp = `${dest}.tmp`;
    await rm(tmp, { force: true });
    for (let i = 0; i < ranges.length; i++) await pipeline(createReadStream(`${partDir}/${i}`), createWriteStream(tmp, { flags: "a" }));
    if ((await sizeOf(tmp)) !== size) throw new DownloadError(`joined file is ${await sizeOf(tmp)} bytes, expected ${size}`);
    await rename(tmp, dest);
    return { bytes: size, segments: ranges.length };
  } finally {
    await rm(partDir, { recursive: true, force: true });
    await rm(`${dest}.tmp`, { force: true });
  }
}

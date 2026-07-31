/**
 * 2026-07-16 — Solar diagnostic ingest Worker (source in thesolarproject/solar).
 * Devices POST log bundles; issues are created on GITHUB_REPO (thesolarproject/solar).
 * Deploy this package from solar; do not put the GitHub PAT on the device.
 */

const REPORT_PATH = "/v1/report";
const COMMENT_CHUNK = 55000;
const MAX_FILES = 80;
const MAX_FILE_CHARS = 300000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      // 2026-07-21 — Report secret wiring so 502/github_create_failed is diagnosable
      // without exposing the secrets themselves (booleans only).
      return json(
        {
          ok: true,
          service: "solar-diag",
          ingest_token_configured: !!env.INGEST_TOKEN,
          github_token_configured: !!env.GITHUB_TOKEN,
          repo: env.GITHUB_REPO || "thesolarproject/solar",
          max_body_bytes: parseInt(env.MAX_BODY_BYTES || "2097152", 10) || 2097152,
        },
        200
      );
    }
    if (url.pathname !== REPORT_PATH) {
      return text("Not Found", 404);
    }
    if (request.method !== "POST") {
      return text("Method Not Allowed", 405, { Allow: "POST" });
    }
    // 2026-07-21 — Any unexpected exception must answer JSON, not Cloudflare's opaque 502
    // ("Worker threw exception"). SolarDiagClient parses the body, so keep it structured.
    try {
      return await handleReport(request, env);
    } catch (e) {
      return json(
        {
          ok: false,
          error: "internal",
          detail: e && e.message ? e.message : String(e),
        },
        500
      );
    }
  },
};

async function handleReport(request, env) {
  const ingest = env.INGEST_TOKEN || "";
  if (!ingest) {
    return json({ ok: false, error: "server_misconfigured" }, 500);
  }
  const provided =
    request.headers.get("X-Solar-Diag-Token") ||
    bearerToken(request.headers.get("Authorization")) ||
    "";
  if (!timingSafeEqual(provided, ingest)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  const maxBytes = parseInt(env.MAX_BODY_BYTES || "2097152", 10) || 2097152;
  const raw = await request.arrayBuffer();
  if (raw.byteLength > maxBytes) {
    return json({ ok: false, error: "payload_too_large", max: maxBytes }, 413);
  }

  let body;
  try {
    body = JSON.parse(new TextDecoder().decode(raw));
  } catch (e) {
    return json({ ok: false, error: "invalid_json" }, 400);
  }
  if (!body || typeof body !== "object") {
    return json({ ok: false, error: "invalid_body" }, 400);
  }

  const githubToken = env.GITHUB_TOKEN || "";
  const repo = env.GITHUB_REPO || "thesolarproject/solar";
  if (!githubToken) {
    return json({ ok: false, error: "github_token_missing" }, 500);
  }

  const type = safeStr(body.type, "other").toLowerCase();
  const feature = safeStr(body.feature, "");
  const trigger = safeStr(body.trigger, "routine").toLowerCase();
  const device = body.device && typeof body.device === "object" ? body.device : {};
  // Prefer Solar family (A5/Y1/Y2) over stock Build.MODEL (A5 ROMs often still say Y1).
  const model = displayModel(device);
  const sdk = device.sdk != null ? String(device.sdk) : "?";
  const versionName = safeStr(device.versionName, "");
  const summary = safeStr(body.summary, "");
  const titleHint = safeStr(body.title, "");
  // Quoted text from Report Issue / Solar Development message (optional).
  const userMessage = safeStr(body.user_message, "");

  // Soulseek username for remote pull + user-reported issues (never invent for routine).
  const soulseekUsername =
    trigger === "remote_pull" || trigger === "user_message"
      ? safeStr(body.soulseek_username, "")
      : "";

  const title = buildTitle({
    type,
    feature,
    model,
    sdk,
    versionName,
    soulseekUsername,
    titleHint,
    userMessage,
    trigger,
  });
  const labels = buildLabels(type, feature, trigger, device);
  const issueBody = buildIssueBody({
    type,
    feature,
    trigger,
    soulseekUsername,
    device,
    model,
    summary,
    userMessage,
    fileCount: Array.isArray(body.files) ? body.files.length : 0,
  });

  let issue;
  try {
    issue = await createIssue(githubToken, repo, title, issueBody, labels);
  } catch (e) {
    return json(
      {
        ok: false,
        error: "github_create_failed",
        detail: e && e.message ? e.message : String(e),
      },
      502
    );
  }

  const files = Array.isArray(body.files) ? body.files.slice(0, MAX_FILES) : [];
  for (const f of files) {
    if (!f || typeof f !== "object") continue;
    const name = safeStr(f.name, "file.txt").slice(0, 200);
    let text = typeof f.text === "string" ? f.text : "";
    if (text.length > MAX_FILE_CHARS) {
      text = text.slice(text.length - MAX_FILE_CHARS);
    }
    const chunks = chunkText(text, COMMENT_CHUNK);
    for (let i = 0; i < chunks.length; i++) {
      const header =
        chunks.length === 1
          ? `### \`${name}\`\n\n`
          : `### \`${name}\` (${i + 1}/${chunks.length})\n\n`;
      const comment = header + "```\n" + chunks[i] + "\n```\n";
      try {
        await createComment(githubToken, repo, issue.number, comment);
      } catch (e) {
        // Keep going — partial attach is better than failing the whole report.
      }
    }
  }

  return json(
    {
      ok: true,
      issue_number: issue.number,
      html_url: issue.html_url,
    },
    201
  );
}

function buildTitle({
  type,
  feature,
  model,
  sdk,
  versionName,
  soulseekUsername,
  titleHint,
  userMessage,
  trigger,
}) {
  const ver = versionName ? ` ${versionName}` : "";
  if (type === "user_report" || type === "user-report") {
    const who = soulseekUsername ? `@${soulseekUsername} ` : "";
    const hint = titleHint || (userMessage ? userMessage.replace(/\s+/g, " ").trim() : "");
    if (hint) {
      return truncate(`[user-report] ${who}${hint} — ${model} sdk${sdk}${ver}`, 240);
    }
    return truncate(`[user-report] ${who}${model} sdk${sdk}${ver}`, 240);
  }
  if (titleHint) {
    return truncate(`[${type}] ${titleHint} ${model} sdk${sdk}${ver}`, 240);
  }
  if (type === "diag_pull") {
    const who = soulseekUsername ? `@${soulseekUsername} ` : "";
    return truncate(`[diag-pull] ${who}${model} sdk${sdk}${ver}`, 240);
  }
  if (type === "error" && feature) {
    return truncate(`[error/${feature}] ${model} sdk${sdk}${ver}`, 240);
  }
  if (type === "crash") {
    return truncate(`[crash] ${model} sdk${sdk}${ver}`, 240);
  }
  if (type === "startup") {
    return truncate(`[startup] ${model} sdk${sdk}${ver}`, 240);
  }
  if (type === "wifi") {
    return truncate(`[wifi/${trigger || "event"}] ${model} sdk${sdk}${ver}`, 240);
  }
  if (type === "power") {
    return truncate(`[power/${trigger || "event"}] ${model} sdk${sdk}${ver}`, 240);
  }
  if (type === "rockbox") {
    return truncate(`[rockbox] ${model} sdk${sdk}${ver}`, 240);
  }
  return truncate(`[${type}] ${model} sdk${sdk}${ver}`, 240);
}

function buildLabels(type, feature, trigger, device) {
  const out = [];
  const known = [
    "crash",
    "error",
    "diag-pull",
    "user-report",
    "reach",
    "playback",
    "rockbox",
    "theme",
    "radio",
    "startup",
    "wifi",
    "power",
    "other",
    "deezer",
    "podcasts",
    "y1",
    "y2",
    "a5",
  ];
  if (type === "diag_pull") out.push("diag-pull");
  else if (type === "user_report" || type === "user-report") out.push("user-report");
  else if (known.indexOf(type) >= 0) out.push(type);
  else out.push("other");
  if (feature && known.indexOf(feature) >= 0 && out.indexOf(feature) < 0) {
    out.push(feature);
  }
  if (trigger === "remote_pull" && out.indexOf("diag-pull") < 0) out.push("diag-pull");
  if (trigger === "user_message" && out.indexOf("user-report") < 0) out.push("user-report");
  if (trigger === "wifi_off" || trigger === "wifi_connect") {
    if (out.indexOf("wifi") < 0) out.push("wifi");
  }
  if (trigger === "power_off" || trigger === "restart") {
    if (out.indexOf("power") < 0) out.push("power");
  }
  // Device family label so A5 issues are filterable even when Build.MODEL is stock Y1.
  const fam = normalizeFamily(
    (device && (device.familyLabel || device.family || device.model)) || ""
  );
  if (fam && out.indexOf(fam) < 0) out.push(fam);
  return out.slice(0, 8);
}

/** Solar family for titles/labels — A5 must not appear as stock Y1. */
function displayModel(device) {
  if (!device || typeof device !== "object") return "unknown";
  const fam = normalizeFamily(device.familyLabel || device.family || "");
  if (fam === "a5") return "A5";
  if (fam === "y2") return "Y2";
  if (fam === "y1") return "Y1";
  const label = safeStr(device.familyLabel, "");
  if (label) return label;
  const model = safeStr(device.model, "");
  if (model) return model;
  return safeStr(device.device, "unknown");
}

function normalizeFamily(v) {
  const s = String(v || "")
    .trim()
    .toLowerCase();
  if (s === "a5" || s === "timmkoo a5") return "a5";
  if (s === "y2") return "y2";
  if (s === "y1") return "y1";
  return "";
}

function buildIssueBody({
  type,
  feature,
  trigger,
  soulseekUsername,
  device,
  model,
  summary,
  userMessage,
  fileCount,
}) {
  const lines = [];
  lines.push("## Solar diagnostic report");
  lines.push("");
  lines.push("| Field | Value |");
  lines.push("| --- | --- |");
  lines.push(`| type | \`${esc(type)}\` |`);
  if (feature) lines.push(`| feature | \`${esc(feature)}\` |`);
  lines.push(`| trigger | \`${esc(trigger)}\` |`);
  // Username on remote developer pull and user-reported issues.
  if (
    (trigger === "remote_pull" || trigger === "user_message") &&
    soulseekUsername
  ) {
    lines.push(`| soulseek_username | \`${esc(soulseekUsername)}\` |`);
  }
  lines.push(`| model | \`${esc(model || device.model || "")}\` |`);
  if (device.family || device.familyLabel) {
    lines.push(
      `| family | \`${esc(device.familyLabel || device.family || "")}\` |`
    );
  }
  if (device.buildModel) {
    lines.push(`| buildModel | \`${esc(device.buildModel)}\` |`);
  }
  lines.push(`| device | \`${esc(device.device || "")}\` |`);
  lines.push(`| brand | \`${esc(device.brand || "")}\` |`);
  lines.push(`| sdk | \`${esc(device.sdk)}\` |`);
  lines.push(`| release | \`${esc(device.release || "")}\` |`);
  lines.push(`| versionName | \`${esc(device.versionName || "")}\` |`);
  lines.push(`| versionCode | \`${esc(device.versionCode)}\` |`);
  lines.push(`| fingerprint | \`${esc(device.fingerprint || "")}\` |`);
  lines.push(`| files | ${fileCount} |`);
  lines.push(`| received_at | ${new Date().toISOString()} |`);
  if (userMessage) {
    lines.push("");
    lines.push("### User message");
    lines.push("");
    lines.push("> " + userMessage.replace(/\r?\n/g, "\n> "));
    lines.push("");
  }
  if (summary) {
    lines.push("");
    lines.push("### Summary");
    lines.push("");
    lines.push(esc(summary));
  }
  lines.push("");
  lines.push("_Log file contents follow in issue comments._");
  lines.push("");
  return lines.join("\n");
}

async function createIssue(token, repo, title, body, labels) {
  // Try with labels first; fall back without if labels are missing on the repo.
  const payload = { title, body };
  if (labels && labels.length) payload.labels = labels;
  let res = await fetch(`https://api.github.com/repos/${repo}/issues`, {
    method: "POST",
    headers: githubHeaders(token),
    body: JSON.stringify(payload),
  });
  let textBody = await res.text();
  if (!res.ok && labels && labels.length) {
    res = await fetch(`https://api.github.com/repos/${repo}/issues`, {
      method: "POST",
      headers: githubHeaders(token),
      body: JSON.stringify({ title, body }),
    });
    textBody = await res.text();
  }
  if (!res.ok) {
    throw new Error(`GitHub ${res.status}: ${textBody.slice(0, 400)}`);
  }
  return JSON.parse(textBody);
}

function githubHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: "application/vnd.github+json",
    "User-Agent": "solar-diag-worker",
    "Content-Type": "application/json",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

async function createComment(token, repo, number, body) {
  const res = await fetch(
    `https://api.github.com/repos/${repo}/issues/${number}/comments`,
    {
      method: "POST",
      headers: githubHeaders(token),
      body: JSON.stringify({ body }),
    }
  );
  if (!res.ok) {
    const t = await res.text();
    throw new Error(`comment ${res.status}: ${t.slice(0, 200)}`);
  }
}

function chunkText(s, size) {
  if (!s) return [""];
  if (s.length <= size) return [s];
  const out = [];
  for (let i = 0; i < s.length; i += size) {
    out.push(s.slice(i, i + size));
  }
  return out;
}

function safeStr(v, fallback) {
  if (v == null) return fallback;
  const s = String(v).trim();
  return s || fallback;
}

function esc(v) {
  return String(v == null ? "" : v)
    .replace(/\|/g, "\\|")
    .replace(/\r?\n/g, " ");
}

function truncate(s, n) {
  if (!s || s.length <= n) return s;
  return s.slice(0, n - 1) + "…";
}

function bearerToken(h) {
  if (!h) return "";
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1].trim() : "";
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const enc = new TextEncoder();
  const ba = enc.encode(a);
  const bb = enc.encode(b);
  if (ba.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ba.length; i++) diff |= ba[i] ^ bb[i];
  return diff === 0;
}

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status: status || 200,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

function text(msg, status, extraHeaders) {
  const headers = Object.assign(
    { "Content-Type": "text/plain; charset=utf-8" },
    extraHeaders || {}
  );
  return new Response(msg, { status: status || 200, headers });
}

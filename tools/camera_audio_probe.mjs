#!/usr/bin/env node
// Проверка поддержки обратного звука (talkback) на IP-камере. Только чтение: звук камере не отправляется.
//
// Использование:
//   node camera_audio_probe.mjs 192.168.1.204 -u admin -p пароль [--rtsp-path /live/ch0]
//
// Проверяет: порты, ONVIF (аудиовыходы, профили, декодеры), RTSP DESCRIBE с
// Require: www.onvif.org/ver20/backchannel (ищет a=sendonly), HTTP-эндпоинты производителей.
import net from "node:net";
import http from "node:http";
import crypto from "node:crypto";

const args = process.argv.slice(2);
const host = args.find((a) => !a.startsWith("-") && !args[args.indexOf(a) - 1]?.startsWith("-"));
const opt = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : ""; };
const user = opt("-u"), pw = opt("-p"), rtspPathArg = opt("--rtsp-path");
if (!host) { console.log("Использование: node camera_audio_probe.mjs <ip> -u user -p pass"); process.exit(1); }

const TIMEOUT = 4000;
const md5 = (s) => crypto.createHash("md5").update(s).digest("hex");
const hdr = (t) => console.log(`\n=== ${t} ===`);
const flat = (s, n = 90) => String(s).replace(/\s+/g, " ").slice(0, n);

function portOpen(port) {
  return new Promise((res) => {
    const s = net.connect({ host, port, timeout: 1500 });
    s.on("connect", () => { s.destroy(); res(true); });
    s.on("error", () => res(false));
    s.on("timeout", () => { s.destroy(); res(false); });
  });
}

function digestHeader(method, uri, challenge) {
  const p = {};
  for (const m of challenge.matchAll(/(\w+)="?([^",]+)"?/g)) p[m[1]] = m[2];
  const ha1 = md5(`${user}:${p.realm ?? ""}:${pw}`), ha2 = md5(`${method}:${uri}`);
  let h;
  if (p.qop) {
    const cn = crypto.randomBytes(8).toString("hex");
    const r = md5(`${ha1}:${p.nonce}:00000001:${cn}:auth:${ha2}`);
    h = `Digest username="${user}", realm="${p.realm}", nonce="${p.nonce}", uri="${uri}", qop=auth, nc=00000001, cnonce="${cn}", response="${r}"`;
  } else {
    h = `Digest username="${user}", realm="${p.realm}", nonce="${p.nonce}", uri="${uri}", response="${md5(`${ha1}:${p.nonce}:${ha2}`)}"`;
  }
  return p.opaque ? h + `, opaque="${p.opaque}"` : h;
}

function httpOnce(method, port, path, body, headers) {
  return new Promise((res) => {
    const req = http.request({ host, port, path, method, headers, timeout: TIMEOUT }, (r) => {
      let d = ""; r.setEncoding("utf8");
      r.on("data", (c) => { if (d.length < 200000) d += c; });
      r.on("end", () => res({ status: r.statusCode, headers: r.headers, text: d }));
    });
    req.on("timeout", () => { req.destroy(); res({ status: null, headers: {}, text: "timeout" }); });
    req.on("error", (e) => res({ status: null, headers: {}, text: e.message }));
    if (body) req.write(body);
    req.end();
  });
}

async function httpReq(method, port, path, body, headers = {}) {
  let r = await httpOnce(method, port, path, body, headers);
  if (r.status === 401 && user) {
    const www = r.headers["www-authenticate"] ?? "";
    const auth = /^digest/i.test(www) ? digestHeader(method, path, www)
      : "Basic " + Buffer.from(`${user}:${pw}`).toString("base64");
    r = await httpOnce(method, port, path, body, { ...headers, Authorization: auth });
  }
  return r;
}

// ---------- ONVIF ----------
function wsse() {
  if (!user) return "";
  const nonce = crypto.randomBytes(16);
  const created = new Date().toISOString().replace(/\.\d+Z$/, "Z");
  const digest = crypto.createHash("sha1").update(Buffer.concat([nonce, Buffer.from(created + pw)])).digest("base64");
  return `<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${user}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${digest}</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${nonce.toString("base64")}</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">${created}</Created></UsernameToken></Security></s:Header>`;
}
function soap(port, path, ns, body) {
  const env = `<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">${wsse()}<s:Body xmlns="${ns}">${body}</s:Body></s:Envelope>`;
  return httpOnce("POST", port, path, env, { "Content-Type": "application/soap+xml; charset=utf-8", "Content-Length": Buffer.byteLength(env) });
}
const DEV = "http://www.onvif.org/ver10/device/wsdl", MEDIA = "http://www.onvif.org/ver10/media/wsdl";
const pathOf = (u) => { try { const x = new URL(u); return [Number(x.port || 80), x.pathname]; } catch { return null; } };

async function onvif(httpPorts) {
  hdr("ONVIF");
  let devPort = null, devPath = "/onvif/device_service";
  for (const p of httpPorts) {
    const r = await soap(p, devPath, DEV, "<GetServices><IncludeCapability>true</IncludeCapability></GetServices>");
    if (r.status === null) continue;
    console.log(`http://${host}:${p}${devPath} -> HTTP ${r.status}`);
    if (r.status === 200 && r.text.includes("Envelope")) {
      devPort = p;
      for (const m of r.text.matchAll(/<(?:\w+:)?Namespace>([^<]+)<\/(?:\w+:)?Namespace>\s*<(?:\w+:)?XAddr>([^<]+)</g))
        console.log("  сервис:", m[1].replace("http://www.onvif.org/", ""), m[2]);
      if (r.text.includes("ver20/media/wsdl")) console.log("  + Media2 (Profile T) есть");
      break;
    }
    if (r.status >= 400) console.log("  ответ:", flat(r.text, 250));
  }
  if (!devPort) { console.log("ONVIF не ответил (порт/логин/пароль или ONVIF выключен)"); return []; }

  let r = await soap(devPort, devPath, DEV, "<GetCapabilities><Category>All</Category></GetCapabilities>");
  const mm = r.text.match(/<(?:\w+:)?Media>[\s\S]*?<(?:\w+:)?XAddr>([^<]+)</);
  const [mp, mpath] = (mm && pathOf(mm[1])) || [devPort, "/onvif/media_service"];
  console.log(`Media XAddr: ${mm?.[1] ?? "(по умолчанию)"}`);

  r = await soap(mp, mpath, MEDIA, "<GetAudioOutputs/>");
  console.log(`GetAudioOutputs: HTTP ${r.status}, выходы:`, [...r.text.matchAll(/AudioOutputs[^>]*token="([^"]+)"/g)].map((m) => m[1]));
  if (r.status !== 200) console.log("  ", flat(r.text, 250));
  r = await soap(mp, mpath, MEDIA, "<GetAudioOutputConfigurations/>");
  console.log(`GetAudioOutputConfigurations: HTTP ${r.status}, конфигурации:`, [...r.text.matchAll(/Configurations[^>]*token="([^"]+)"/g)].map((m) => m[1]));
  r = await soap(mp, mpath, MEDIA, "<GetProfiles/>");
  const profs = [...r.text.matchAll(/Profiles[^>]*token="([^"]+)"/g)].map((m) => m[1]);
  console.log(`GetProfiles: HTTP ${r.status}, профили:`, profs);
  console.log("  AudioOutputConfiguration в профилях:", r.text.includes("AudioOutputConfiguration"),
    "| AudioDecoderConfiguration:", r.text.includes("AudioDecoderConfiguration"));
  r = await soap(mp, mpath, MEDIA, "<GetAudioDecoderConfigurationOptions/>");
  console.log(`GetAudioDecoderConfigurationOptions: HTTP ${r.status}, кодеки:`, [...new Set([...r.text.matchAll(/<(?:\w+:)?Encoding>(\w+)</g)].map((m) => m[1]))]);

  const uris = [];
  for (const t of profs.slice(0, 3)) {
    r = await soap(mp, mpath, MEDIA, `<GetStreamUri><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>${t}</ProfileToken></GetStreamUri>`);
    const m = r.text.match(/<(?:\w+:)?Uri>([^<]+)</);
    console.log(`  RTSP uri [${t}]:`, m ? m[1] : `HTTP ${r.status}`);
    if (m) uris.push(m[1]);
  }
  return uris;
}

// ---------- RTSP backchannel ----------
function rtspDescribe(port, path) {
  const url = `rtsp://${host}:${port}${path}`;
  return new Promise((res) => {
    const s = net.connect({ host, port, timeout: TIMEOUT });
    let cseq = 1, buf = "", stage = 0, last = "";
    const send = (extra = "") => {
      last = extra;
      s.write(`DESCRIBE ${url} RTSP/1.0\r\nCSeq: ${cseq++}\r\nUser-Agent: probe\r\nAccept: application/sdp\r\nRequire: www.onvif.org/ver20/backchannel\r\n${extra}\r\n`);
    };
    const done = (v) => { s.destroy(); res(v); };
    s.on("connect", () => send());
    s.on("timeout", () => done({ head: "timeout", body: "" }));
    s.on("error", (e) => done({ head: null, body: e.message }));
    s.on("data", (c) => {
      buf += c.toString("latin1");
      const i = buf.indexOf("\r\n\r\n");
      if (i < 0) return;
      const head = buf.slice(0, i), cl = Number(head.match(/Content-Length:\s*(\d+)/i)?.[1] ?? 0);
      const body = buf.slice(i + 4);
      if (body.length < cl) return;
      if (/^RTSP\/1\.0 401/.test(head) && stage === 0 && user) {
        stage = 1; buf = "";
        const www = head.match(/WWW-Authenticate:\s*(.+)/i)?.[1].trim() ?? "";
        send("Authorization: " + (/^digest/i.test(www) ? digestHeader("DESCRIBE", url, www)
          : "Basic " + Buffer.from(`${user}:${pw}`).toString("base64")) + "\r\n");
        return;
      }
      done({ head, body });
    });
  });
}

async function rtsp(port, extraUris) {
  hdr(`RTSP backchannel (порт ${port})`);
  const paths = [];
  for (const u of extraUris) { const m = u.match(/^rtsp:\/\/[^/]+(\/.*)$/); if (m) paths.push(m[1]); }
  if (rtspPathArg) paths.unshift(rtspPathArg);
  paths.push("/", "/live/ch0", "/live/ch1", "/stream1", "/stream2", "/h264", "/11", "/12",
    "/Streaming/Channels/101", "/cam/realmonitor?channel=1&subtype=0", "/h264Preview_01_main",
    "/onvif1", "/live", "/av0_0", "/ch0_0.h264", "/ch0");
  let found = false;
  for (const p of [...new Set(paths)]) {
    const { head, body } = await rtspDescribe(port, p);
    if (head === null) { console.log("Нет соединения:", body); return; }
    const status = head.split("\r\n")[0];
    console.log(`${p.padEnd(42)} -> ${status}`);
    if (!/ 200 /.test(status)) continue;
    found = true;
    let backchannel = false;
    for (const b of body.split(/^m=/m).slice(1)) {
      const kind = b.split(" ")[0], dir = b.match(/a=(sendonly|recvonly|sendrecv|inactive)/)?.[1] ?? "не указано";
      const codec = [...b.matchAll(/a=rtpmap:\d+ ([^\r\n]+)/g)].map((m) => m[1]);
      const ctrl = b.match(/a=control:([^\r\n]+)/)?.[1] ?? "-";
      console.log(`    m=${kind}: направление ${dir}, кодек ${JSON.stringify(codec)}, control ${ctrl}`);
      if (kind === "audio" && dir === "sendonly") backchannel = true;
    }
    console.log("    >>> BACKCHANNEL (audio sendonly):", backchannel ? "ДА" : "нет");
    console.log("    заголовки:", head.replace(/\r\n/g, " | ").slice(0, 300));
    break;
  }
  if (!found) console.log("Рабочий RTSP-путь не найден (задайте -u/-p или --rtsp-path)");
}

// ---------- HTTP-эндпоинты производителей ----------
const VENDOR = [
  ["Hikvision TwoWayAudio", "/ISAPI/System/TwoWayAudio/channels"],
  ["Hikvision TWA caps", "/ISAPI/System/TwoWayAudio/channels/1/capabilities"],
  ["Hikvision AudioOut", "/ISAPI/System/Audio/AudioOut/channels"],
  ["Dahua audio caps", "/cgi-bin/audio.cgi?action=getAudioOutputCaps"],
  ["Dahua devAudioOutput", "/cgi-bin/devAudioOutput.cgi?action=getCollect"],
  ["Dahua systemInfo", "/cgi-bin/magicBox.cgi?action=getSystemInfo"],
  ["Reolink GetAbility", "/cgi-bin/api.cgi?cmd=GetAbility&user=U&password=P"],
  ["Foscam audio", "/cgi-bin/CGIProxy.fcgi?cmd=getAudioStreamParam&usr=U&pwd=P"],
  ["Axis audio transmit", "/axis-cgi/audio/transmit.cgi"],
  ["Axis param Audio", "/axis-cgi/param.cgi?action=list&group=Audio"],
  ["Uniview LAPI", "/LAPI/V1.0/Channel/0/Media/AudioOutput"],
  ["Hi3510 audio attr", "/cgi-bin/hi3510/param.cgi?cmd=getaudioattr"],
  ["Hi3510 audio out", "/cgi-bin/hi3510/audiooutput.cgi"],
  ["generic audio.cgi", "/cgi-bin/audio.cgi"],
];
async function vendor(httpPorts) {
  hdr("HTTP-эндпоинты производителей");
  for (const p of httpPorts) {
    console.log(`-- порт ${p}`);
    const r = await httpReq("GET", p, "/", null);
    console.log(`   / -> ${r.status}  Server: ${r.headers.server ?? "-"}  WWW-Auth: ${(r.headers["www-authenticate"] ?? "-").slice(0, 60)}`);
    const title = r.text.match(/<title>([^<]*)/i)?.[1];
    if (title) console.log("   title:", title.trim().slice(0, 80));
    for (const [name, path0] of VENDOR) {
      const path = path0.replace("=U&", `=${encodeURIComponent(user)}&`).replace(/=P$/, `=${encodeURIComponent(pw)}`).replace("password=P", `password=${encodeURIComponent(pw)}`);
      const x = await httpReq("GET", p, path, null);
      if (x.status === null || x.status === 404) continue;
      console.log(`   ${x.status === 200 ? "ОК " : "   "}${name.padEnd(22)} ${path0.slice(0, 50).padEnd(50)} -> ${x.status}  ${flat(x.text)}`);
    }
  }
}

hdr(`Порты ${host}`);
const PORTS = [80, 81, 443, 554, 1935, 2020, 5000, 8000, 8080, 8554, 8899, 34567, 37777];
const open = (await Promise.all(PORTS.map(async (p) => ((await portOpen(p)) ? p : 0)))).filter(Boolean);
console.log("открыты:", open.length ? open : "ничего");
const httpPorts = open.filter((p) => [80, 81, 2020, 5000, 8000, 8080, 8899].includes(p));
const rtspPorts = open.filter((p) => p === 554 || p === 8554);
const uris = httpPorts.length ? await onvif(httpPorts) : [];
for (const p of rtspPorts) await rtsp(p, uris);
if (httpPorts.length) await vendor(httpPorts);
console.log("\nГотово. Пришлите весь вывод.");

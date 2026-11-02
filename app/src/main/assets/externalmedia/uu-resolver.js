// UU Doomscroller resolver page.
//
// Runs in a hidden WebView with no network of its own: every request it needs
// is handed to the app through UUChatResolver.postMessage and performed
// natively. It is given one video id and returns one deciphered, attested
// stream address, which the app then plays itself. No YouTube page is ever
// shown, so no player, no ads and no social controls are ever loaded.
import { Innertube, Platform, Log } from './youtubei-18.1.0.js';

try { Log.setLevel(0); } catch (_) {}

const send = (message) => {
  try { UUChatResolver.postMessage(JSON.stringify(message)); } catch (_) {}
};

const waiting = new Map();
let seq = 0;

const bytes = (base64) => Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));

async function mediatedFetch(input, init = {}) {
  const request = new Request(input, init);
  const method = (init.method || request.method || 'GET').toUpperCase();
  const headers = Object.fromEntries(new Headers(init.headers || request.headers).entries());
  const body = init.body !== undefined
    ? init.body
    : method === 'POST' ? await request.text() : '';
  if (typeof body !== 'string') throw new Error('Unsupported request body');
  const n = ++seq;
  if (n > 64) throw new Error('Too many requests');
  const reply = await new Promise((resolve) => {
    waiting.set(n, resolve);
    send({ k: 'fetch', n, request: JSON.stringify({ url: request.url, method, headers, body }) });
  });
  if (!reply || !reply.ok) throw new Error('Mediated request refused ' + (reply && reply.status));
  return new Response(bytes(reply.body), {
    status: reply.status,
    headers: { 'Content-Type': reply.type || 'application/json' },
  });
}

Platform.shim.eval = async (data, env = {}) =>
  new Function(...Object.keys(env), data.output)(...Object.values(env));

let session = null;
let pendingResolve = false;

// Native calls this with a JSON string.
globalThis.__uuOnMessage = (json) => {
  let message;
  try { message = JSON.parse(json); } catch (_) { return; }
  if (!message || typeof message !== 'object') return;
  if (message.k === 'fetched' && waiting.has(message.n)) {
    const resolve = waiting.get(message.n);
    waiting.delete(message.n);
    resolve(message);
  } else if (message.k === 'resolve' && !pendingResolve) {
    pendingResolve = true;
    resolveVideo(message);
  }
};

async function resolveVideo(start) {
  try {
    await doResolve(start);
  } catch (_) {
    send({ k: 'result', ok: false, reason: 'THROWN' });
  } finally {
    pendingResolve = false;   // every outcome releases the next request
  }
}

async function doResolve(start) {
  const id = start.id;
  if (!/^[A-Za-z0-9_-]{11}$/.test(id)) {
    send({ k: 'result', ok: false, reason: 'BAD_ID' });
    return;
  }
  try {
    send({ k: 'note', v: 'CONFIGURING' });
    const options = {
      fetch: mediatedFetch,
      client_type: 'WEB',
      generate_session_locally: true,
      user_agent: navigator.userAgent,
      device_category: 'mobile',
      retrieve_innertube_config: false,
      retrieve_player: true,
      enable_session_cache: false,
      fast_fail: true,
      cache: { get: async () => undefined, set: async () => {}, remove: async () => {} },
    };
    if (start.token) options.po_token = start.token;
    if (start.client && start.client.visitorData) options.visitor_data = start.client.visitorData;

    // The session is built once and reused: creating it costs ~25 s, and it
    // holds the player code that deciphering needs.
    if (!session) {
      session = await Innertube.create(options);
    }
    const yt = session;
    if (start.client) Object.assign(yt.session.context.client, start.client);

    send({ k: 'note', v: 'RESOLVING' });

    // No single client is reliably served a muxed stream, so they are tried in
    // turn until one returns a file this renderer can play. The tally is kept
    // for diagnosis: "web:0/14" means no muxed and fourteen adaptive formats.
    const clients = ['WEB', 'ANDROID', 'IOS', 'MWEB', 'TV'];
    const tally = [];
    for (let c = 0; c < clients.length; c++) {
      const client = clients[c];
      let info;
      try {
        info = await yt.getBasicInfo(id, { client });
      } catch (e) {
        tally.push(client + ':ERR');
        continue;
      }
      const play = info.playability_status || {};
      const sd = info.streaming_data || {};
      const muxed = sd.formats || [];
      const adaptive = sd.adaptive_formats || [];
      tally.push(client + ':' + muxed.length + '/' + adaptive.length + '/'
        + String(play.status || '?').slice(0, 8));
      if (play.status !== 'OK') {
        continue;
      }
      const usable = muxed.filter((f) => f.has_audio && f.has_video
        && /^video\/mp4/.test(f.mime_type || '')
        && !f.drm_families?.length && !f.drm_track_type && (f.height || 0) <= 720);
      if (!usable.length) {
        continue;
      }
      usable.sort((a, b) => Math.abs((a.height || 0) - 360)
        - Math.abs((b.height || 0) - 360) || a.itag - b.itag);
      let url;
      try {
        url = await usable[0].decipher(yt.session.player);
      } catch (_) {
        tally.push(client + ':DECIPHER');
        continue;
      }
      send({
        k: 'result',
        ok: true,
        id,
        title: (info.basic_info && info.basic_info.title)
          ? String(info.basic_info.title).slice(0, 200) : '',
        channel: (info.basic_info && info.basic_info.channel_id)
          ? String(info.basic_info.channel_id) : '',
        itag: usable[0].itag,
        width: usable[0].width || 0,
        height: usable[0].height || 0,
        url,
      });
      return;
    }
    send({ k: 'result', ok: false, reason: tally.join(',') });
  } catch (error) {
    const code = error?.code;
    send({
      k: 'result',
      ok: false,
      reason: typeof code === 'string' && /^[A-Z_]{1,40}$/.test(code) ? code : 'RESOLVER_FAILED',
    });
  }
}

send({ k: 'note', v: 'BOOTED' });

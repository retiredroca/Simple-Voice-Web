package com.retiredroca.svcweb.web;

/**
 * The page served at a keyed link.
 *
 * <p>Self-contained: no CDN, no external script, no analytics. Audio is Opus over WebCodecs, which
 * the browser requires to run in a secure context - hence TLS.
 *
 * <p>Nothing here knows a player's coordinates or direction. The server sends one mixed stereo
 * stream, encoded from positions that never leave the server.
 */
public final class Page {

    private Page() {}

    public static String html(String title) {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%TITLE%</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; font: 15px/1.5 system-ui, sans-serif; background: #14161a; color: #e6e8eb;
         display: flex; align-items: center; justify-content: center; min-height: 100vh; }
  main { width: min(560px, 92vw); padding: 24px; background: #1b1e24; border-radius: 12px;
         box-shadow: 0 8px 32px #0008; }
  h1 { font-size: 18px; margin: 0 0 4px; }
  p { margin: 0 0 16px; color: #9aa3af; }
  button { font: inherit; padding: 10px 16px; border: 0; border-radius: 8px; cursor: pointer;
           background: #3b82f6; color: #fff; }
  button:disabled { background: #2a2f3a; color: #6b7280; cursor: default; }
  .controls { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
  .volume-row { display: flex; gap: 8px; align-items: center; margin-top: 12px;
                font-size: 13px; color: #9aa3af; }
  .volume-row input { width: 140px; accent-color: #3b82f6; }
  .volume-row output { min-width: 44px; text-align: right; font-variant-numeric: tabular-nums; }
  #status { margin-top: 16px; font-size: 13px; color: #9aa3af; white-space: pre-wrap; }
  #people { margin-top: 16px; display: flex; flex-direction: column; gap: 6px; }
  .person { display: grid; grid-template-columns: minmax(0, 1fr) 88px 42px;
            gap: 8px; align-items: center; padding: 6px 10px;
            background: #22262e; border-radius: 6px; font-size: 13px; }
  .person .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .person input[type="range"] { width: 88px; accent-color: #3b82f6; }
  .person.speaking { background: #1e3a2f; }
  .volume { color: #9aa3af; font-variant-numeric: tabular-nums; text-align: right; }
  .error { color: #f87171; }
</style>
</head>
<body>
<main>
  <h1>%TITLE%</h1>
  <p>Proximity voice, in the browser. Your position stays on the server.</p>
  <div class="controls">
    <button id="start">Join voice</button>
    <button id="mute" disabled>Mute mic</button>
    <button id="muteOutput" disabled>Mute speakers</button>
  </div>
  <div class="volume-row">
    <label for="volume">Volume</label>
    <input id="volume" type="range" min="0" max="150" step="5" value="50">
    <output id="volumeValue">50%</output>
  </div>
  <div id="status">Not connected.</div>
  <div id="people"></div>
</main>
<script>
(() => {
  "use strict";
  const SAMPLE_RATE = 48000;
  const FRAME = 960;
  const startButton = document.getElementById("start");
  const muteButton = document.getElementById("mute");
  const muteOutputButton = document.getElementById("muteOutput");
  const volumeInput = document.getElementById("volume");
  const volumeValue = document.getElementById("volumeValue");
  const statusLine = document.getElementById("status");
  const people = document.getElementById("people");

  let socket = null;
  let context = null;
  let encoder = null;
  let leftDecoder = null;
  let rightDecoder = null;
  let nextPlayTime = 0;
  let rxTimestamp = 0;
  let stream = null;
  let worklet = null;
  // The key is in the path of the page that was opened; a live session token is kept for
  // reconnects so the player does not have to run the command again after a network drop.
  const linkKey = location.pathname.startsWith("/join/")
    ? decodeURIComponent(location.pathname.slice(6).split(/[?#]/)[0])
    : "";
  let sessionToken = sessionStorage.getItem("svcweb-session") || "";
  let muted = false;
  let reconnectAttempts = 0;
  let txTimestamp = 0;
  let serverError = "";
  let offlineReason = "";
  let sessionEnded = false;
  let selfUuid = "";

  const MASTER_VOLUME_KEY = "svcweb-master-volume";
  const SPEAKER_VOLUMES_KEY = "svcweb-speaker-volumes";
  const SPEAKERS_MUTED_KEY = "svcweb-speakers-muted";

  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
  const readStored = (key) => {
    try { return localStorage.getItem(key); } catch (e) { return null; }
  };
  const writeStored = (key, value) => {
    try { localStorage.setItem(key, value); } catch (e) {}
  };
  const readStoredNumber = (key, fallback, min, max) => {
    const raw = readStored(key);
    if (raw === null || raw === "") return fallback;
    const value = Number(raw);
    return Number.isFinite(value) ? clamp(value, min, max) : fallback;
  };
  const readSpeakerVolumes = () => {
    try {
      const value = JSON.parse(readStored(SPEAKER_VOLUMES_KEY) || "{}");
      if (!value || typeof value !== "object" || Array.isArray(value)) return {};
      const out = {};
      for (const [uuid, volume] of Object.entries(value)) {
        const n = Number(volume);
        if (Number.isFinite(n)) out[uuid] = clamp(n, 0, 200);
      }
      return out;
    } catch (e) {
      return {};
    }
  };

  let masterVolume = readStoredNumber(MASTER_VOLUME_KEY, 50, 0, 150);
  let speakersMuted = readStored(SPEAKERS_MUTED_KEY) === "true";
  let speakerVolumes = readSpeakerVolumes();
  let masterGain = null;

  const names = new Map();
  const nodes = new Map();
  // The server mixes every audible speaker into two mono Opus streams. The browser joins the
  // decoded left and right frames into one stereo buffer.
  const pending = new Map();

  const saveSpeakerVolumes = () => {
    writeStored(SPEAKER_VOLUMES_KEY, JSON.stringify(speakerVolumes));
  };

  const applyMasterVolume = () => {
    if (masterGain) {
      masterGain.gain.value = speakersMuted ? 0 : masterVolume / 100;
    }
    volumeInput.value = String(masterVolume);
    volumeValue.textContent = speakersMuted ? "Muted" : masterVolume + "%";
    muteOutputButton.textContent = speakersMuted ? "Unmute speakers" : "Mute speakers";
  };

  applyMasterVolume();

  const setStatus = (text, isError) => {
    statusLine.textContent = text;
    statusLine.className = isError ? "error" : "";
  };

  let lastRender = 0;
  const renderPeople = (force) => {
    const now = Date.now();
    if (!force && now - lastRender < 100) return;
    lastRender = now;
    for (const [uuid, name] of names) {
      let node = nodes.get(uuid);
      if (!node) {
        node = document.createElement("div");
        node.className = "person";
        const label = document.createElement("span");
        label.className = "name";
        const volume = document.createElement("input");
        volume.type = "range";
        volume.min = "0";
        volume.max = "200";
        volume.step = "5";
        volume.setAttribute("aria-label", "Speaker volume");
        const volumeText = document.createElement("span");
        volumeText.className = "volume";
        node.append(label, volume, volumeText);
        people.append(node);
        nodes.set(uuid, { node, label, volume, volumeText });
      }
      const entry = nodes.get(uuid);
      entry.label.textContent = name || uuid;
      const userVolume = speakerVolumes[uuid] ?? 100;
      if (document.activeElement !== entry.volume) entry.volume.value = String(userVolume);
      entry.volume.disabled = uuid === selfUuid;
      entry.volumeText.textContent = uuid === selfUuid ? "self" : userVolume + "%";
      entry.volume.oninput = () => {
        const value = clamp(Number(entry.volume.value) || 0, 0, 200);
        speakerVolumes[uuid] = value;
        saveSpeakerVolumes();
        entry.volumeText.textContent = value + "%";
        send({ type: "volume", uuid, volume: value });
      };
      entry.node.classList.toggle("speaking", Date.now() - (entry.last ?? 0) < 400);
    }
  };

  setInterval(() => renderPeople(true), 200);

  const send = (message) => {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(message));
    }
  };

  const sendVolumes = () => {
    for (const [uuid, value] of Object.entries(speakerVolumes)) {
      send({ type: "volume", uuid, volume: value });
    }
  };

  const encode = async (pcm) => {
    if (!encoder || muted) return;
    const frame = new AudioData({
      format: "f32-planar",
      sampleRate: SAMPLE_RATE,
      numberOfFrames: FRAME,
      numberOfChannels: 1,
      timestamp: txTimestamp,
      data: pcm,
    });
    txTimestamp += 20000;
    encoder.encode(frame);
    frame.close();
  };

  const onDecoded = (channel, data) => {
    try {
      if (!context || sessionEnded) return;
      const timestamp = data.timestamp;
      const pcm = new Float32Array(data.numberOfFrames);
      data.copyTo(pcm, { planeIndex: 0, format: "f32-planar" });
      let pair = pending.get(timestamp);
      if (!pair) {
        pair = {};
        pending.set(timestamp, pair);
      }
      pair[channel] = pcm;
      if (pair.left && pair.right) {
        pending.delete(timestamp);
        playStereo(pair.left, pair.right);
      }
      const cutoff = timestamp - 2000000;
      for (const key of pending.keys()) {
        if (key < cutoff) pending.delete(key);
      }
    } catch (e) {
      setStatus("Playback error: " + e.message, true);
      console.error("svcweb playback", e);
    } finally {
      data.close();
    }
  };

  const createDecoder = (channel) => {
    const decoder = new AudioDecoder({
      output: (data) => onDecoded(channel, data),
      error: (e) => {
        setStatus("Decoder error: " + e.message, true);
        console.error("svcweb decoder", e);
      },
    });
    decoder.configure({ codec: "opus", sampleRate: SAMPLE_RATE, numberOfChannels: 1 });
    return decoder;
  };

  const playStereo = (left, right) => {
    if (!context || !masterGain || sessionEnded) return;
    const frames = Math.min(left.length, right.length);
    if (frames === 0) return;
    const buffer = context.createBuffer(2, frames, SAMPLE_RATE);
    buffer.copyToChannel(left.subarray(0, frames), 0);
    buffer.copyToChannel(right.subarray(0, frames), 1);
    const node = context.createBufferSource();
    node.buffer = buffer;
    node.connect(masterGain);
    const at = Math.max(context.currentTime + 0.04, nextPlayTime);
    node.start(at);
    nextPlayTime = at + buffer.duration;
  };

  const playPair = (left, right) => {
    if (!context || !leftDecoder || !rightDecoder) return;
    if (context.state !== "running") context.resume().catch(() => {});
    rxTimestamp += 20000;
    const chunk = (data) => new EncodedAudioChunk({
      type: "key",
      timestamp: rxTimestamp,
      duration: 20000,
      data,
    });
    leftDecoder.decode(chunk(left));
    rightDecoder.decode(chunk(right));
  };

  const connect = () => {
    if (socket && (socket.readyState === WebSocket.CONNECTING
                   || socket.readyState === WebSocket.OPEN)) {
      return;
    }
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const query = sessionToken
      ? "?session=" + encodeURIComponent(sessionToken)
      : "?link=" + encodeURIComponent(linkKey);
    socket = new WebSocket(scheme + "://" + location.host + "/ws" + query);
    socket.binaryType = "arraybuffer";

    socket.onopen = () => {
      reconnectAttempts = 0;
      serverError = "";
      sendVolumes();
      setStatus("Connected. Speak normally.");
    };
    socket.onclose = (event) => {
      console.warn("svcweb socket closed", event.code, event.reason);
      if (offlineReason || event.code === 4001) {
        sessionToken = "";
        sessionStorage.removeItem("svcweb-session");
        stopAudio();
        startButton.disabled = true;
        startButton.textContent = "Session ended";
        setStatus(offlineReason || "You left the game. Rejoin and run /svcweb link again.", true);
        return;
      }
      // A refused upgrade (expired or already-used link) arrives as 1006, because the browser
      // never saw a WebSocket-level close. Only retry when a live session token can be used:
      // the link key itself is single-use and would fail again.
      const expired = event.code === 1008 || !sessionToken;
      if (expired || reconnectAttempts >= 3) {
        sessionStorage.removeItem("svcweb-session");
        sessionToken = "";
        stopAudio();
        startButton.disabled = true;
        startButton.textContent = "Session ended";
        setStatus("This link has expired or the connection was lost. Run /svcweb link in game "
                  + "for a new one.", true);
        return;
      }
      reconnectAttempts++;
      setStatus("Disconnected. Reconnecting...");
      setTimeout(connect, 2000);
    };
    socket.onerror = () => {
      console.warn("svcweb socket error");
      setStatus("Connection error.", true);
    };

    socket.onmessage = (event) => {
      if (typeof event.data === "string") {
        const message = JSON.parse(event.data);
        if (message.type === "hello") {
          if (message.session) {
            sessionToken = message.session;
            sessionStorage.setItem("svcweb-session", sessionToken);
          }
          selfUuid = message.uuid;
          names.set(message.uuid, message.name);
          renderPeople(true);
        } else if (message.type === "offline") {
          offlineReason = message.message || "You left the game. Rejoin and run /svcweb link again.";
          sessionToken = "";
          sessionStorage.removeItem("svcweb-session");
          stopAudio();
          startButton.disabled = true;
          startButton.textContent = "Session ended";
          setStatus(offlineReason, true);
        } else if (message.type === "attached") {
          if (message.attached === false) {
            serverError = "Voice chat could not attach this player. Make sure Simple Voice Chat is "
                        + "running and this client does not have the mod installed.";
            setStatus(serverError, true);
          } else {
            serverError = "";
            sendVolumes();
            if (encoder) setStatus("Connected. Speak normally.");
          }
        } else if (message.type === "speaker") {
          names.set(message.uuid, message.name);
          const entry = nodes.get(message.uuid);
          if (entry) entry.last = message.speaking === false ? 0 : Date.now();
          renderPeople(true);
        } else if (message.type === "error") {
          serverError = message.message;
          setStatus(serverError, true);
        }
      } else {
        const bytes = new Uint8Array(event.data);
        if (bytes.length < 5 || bytes[0] !== 3) return;
        const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        const leftLength = view.getUint16(1, false);
        const rightLength = view.getUint16(3, false);
        if (leftLength === 0 || rightLength === 0
            || bytes.length !== 5 + leftLength + rightLength) return;
        playPair(bytes.subarray(5, 5 + leftLength),
                 bytes.subarray(5 + leftLength));
      }
    };
  };

  const stopAudio = () => {
    sessionEnded = true;
    if (stream) {
      for (const track of stream.getTracks()) track.stop();
      stream = null;
    }
    if (encoder) {
      try { encoder.close(); } catch (e) {}
      encoder = null;
    }
    if (leftDecoder) {
      try { leftDecoder.close(); } catch (e) {}
      leftDecoder = null;
    }
    if (rightDecoder) {
      try { rightDecoder.close(); } catch (e) {}
      rightDecoder = null;
    }
    pending.clear();
    nextPlayTime = 0;
    rxTimestamp = 0;
    if (context) {
      context.close().catch(() => {});
      context = null;
    }
    masterGain = null;
    worklet = null;
    muteButton.disabled = true;
    muteOutputButton.disabled = true;
    muteButton.textContent = "Mute mic";
    muted = false;
  };

  const startAudio = async () => {
    sessionEnded = false;
    context = new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: "interactive" });
    await context.resume();
    if (context.state !== "running") {
      throw new Error("The browser blocked audio output. Click Join voice again or check autoplay "
                    + "settings.");
    }
    context.onstatechange = () => {
      console.debug("svcweb audio context", context.state);
      if (context.state === "suspended") context.resume().catch(() => {});
    };
    masterGain = context.createGain();
    masterGain.connect(context.destination);
    applyMasterVolume();

    const monoSupported = await AudioDecoder.isConfigSupported({
      codec: "opus", sampleRate: SAMPLE_RATE, numberOfChannels: 1,
    });
    if (!monoSupported.supported) {
      throw new Error("This browser cannot decode Opus.");
    }
    leftDecoder = createDecoder("left");
    rightDecoder = createDecoder("right");
    pending.clear();
    nextPlayTime = context.currentTime + 0.05;
    rxTimestamp = 0;

    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true,
               autoGainControl: true, sampleRate: SAMPLE_RATE },
    });
    if (sessionEnded) {
      stopAudio();
      return;
    }

    if (context.state !== "running") await context.resume();
    if (context.state !== "running") {
      throw new Error("The browser blocked audio input. Click Join voice again or check autoplay "
                    + "settings.");
    }
    await context.audioWorklet.addModule(URL.createObjectURL(new Blob([`
      class Capture extends AudioWorkletProcessor {
        constructor() {
          super();
          this.buffer = new Float32Array(960);
          this.offset = 0;
        }
        process(inputs) {
          const input = inputs[0];
          if (!input || !input[0]) return true;
          const samples = input[0];
          for (let i = 0; i < samples.length; i++) {
            this.buffer[this.offset++] = samples[i];
            if (this.offset === 960) {
              this.port.postMessage(this.buffer.slice(0));
              this.offset = 0;
            }
          }
          return true;
        }
      }
      registerProcessor("capture", Capture);
    `], { type: "application/javascript" })));

    if (sessionEnded) {
      stopAudio();
      return;
    }
    worklet = new AudioWorkletNode(context, "capture");
    const source = context.createMediaStreamSource(stream);
    // The worklet only runs while something pulls it. Route it through a silent gain node so the
    // microphone is processed without echoing it into the page.
    const silent = context.createGain();
    silent.gain.value = 0;
    silent.connect(context.destination);
    source.connect(worklet);
    worklet.connect(silent);

    const supported = await AudioEncoder.isConfigSupported({
      codec: "opus", sampleRate: SAMPLE_RATE, numberOfChannels: 1, bitrate: 32000,
    });
    if (!supported.supported) {
      setStatus("This browser cannot encode Opus. Try a recent Chrome, Edge, Firefox or Safari.",
                true);
      return;
    }

    encoder = new AudioEncoder({
      output: (chunk) => {
        const bytes = new Uint8Array(chunk.byteLength);
        chunk.copyTo(bytes);
        if (socket && socket.readyState === WebSocket.OPEN) socket.send(bytes);
      },
      error: (e) => setStatus("Encoder error: " + e.message, true),
    });
    encoder.configure({ codec: "opus", sampleRate: SAMPLE_RATE, numberOfChannels: 1,
                        bitrate: 32000 });

    if (sessionEnded) {
      stopAudio();
      return;
    }
    worklet.port.onmessage = (event) => encode(event.data);

    startButton.disabled = true;
    startButton.textContent = serverError ? "Mic on (not connected)" : "In voice";
    muteButton.disabled = false;
    muteOutputButton.disabled = false;
    if (!serverError && !sessionEnded) setStatus("Connected. Speak normally.");
  };

  volumeInput.oninput = () => {
    masterVolume = clamp(Number(volumeInput.value) || 0, 0, 150);
    speakersMuted = false;
    writeStored(MASTER_VOLUME_KEY, String(masterVolume));
    writeStored(SPEAKERS_MUTED_KEY, "false");
    applyMasterVolume();
  };

  muteOutputButton.onclick = () => {
    speakersMuted = !speakersMuted;
    writeStored(SPEAKERS_MUTED_KEY, String(speakersMuted));
    applyMasterVolume();
  };

  muteButton.onclick = () => {
    muted = !muted;
    muteButton.textContent = muted ? "Unmute mic" : "Mute mic";
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: muted ? "mute" : "end" }));
    }
  };

  startButton.onclick = async () => {
    startButton.disabled = true;
    try {
      connect();
      await startAudio();
    } catch (e) {
      if (!sessionEnded) {
        setStatus("Could not start audio: " + e.message, true);
        startButton.disabled = false;
      }
    }
  };

  if (!("AudioEncoder" in window) || !("AudioDecoder" in window)) {
    setStatus("This browser does not support WebCodecs audio. Use a recent Chrome, Edge, Firefox "
              + "or Safari over HTTPS.", true);
    startButton.disabled = true;
  }
})();
</script>
</body>
</html>
""".replace("%TITLE%", escapeHtml(title));
    }

    public static String error(int status, String message) {
        return """
<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"><title>%CODE%</title>
<style>body{font:15px/1.5 system-ui,sans-serif;background:#14161a;color:#e6e8eb;
display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}
main{max-width:420px;padding:24px;background:#1b1e24;border-radius:12px;text-align:center}
h1{font-size:18px;margin:0 0 8px}p{color:#9aa3af;margin:0}</style></head>
<body><main><h1>%CODE%</h1><p>%MESSAGE%</p></main></body></html>
""".replace("%CODE%", Integer.toString(status)).replace("%MESSAGE%", escapeHtml(message));
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

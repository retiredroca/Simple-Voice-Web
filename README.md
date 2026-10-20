# Simple Voice Web

A server-side Fabric/NeoForge mod that lets players **without** the Simple Voice Chat mod join a
server's proximity voice chat through a web page.

A player runs `/svcweb link` in game and receives a keyed, single-use URL. Opening it on a phone,
tablet or second machine gives them a microphone and speakers for their existing in-game character:
other players hear them from their avatar, and they hear nearby players with the same distance
falloff as a normal client. **No player position is ever sent to the browser** - the server computes
a per-speaker gain from positions it already has.

## Requirements

- Minecraft Java Edition **1.21.1 or 26.3** server, Fabric or NeoForge.
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) **2.6.0 or newer** on the server
  (the 26.3 build requires **2.6.24**, the SVC version for that Minecraft release).
- A modern browser with WebCodecs audio over **HTTPS** (or `http://localhost`): current Chrome,
  Edge, Firefox or Safari. Browsers only allow microphone capture and WebCodecs in a secure context,
  so the page must be served over TLS.
- The player using the web page must **not** have the Simple Voice Chat mod installed. The mod uses
  Simple Voice Chat's `AudioSender`, which is deliberately refused for players who already have it;
  they should just use the mod.

## How it works

Two public Simple Voice Chat API primitives do the work:

- `AudioSender` injects browser audio as if the player had spoken, so proximity, permissions and
  groups behave normally.
- `PlayerAudioListener` receives every packet the player would have heard, including when they have
  no voice chat connection at all.

The web page captures the microphone, encodes Opus with WebCodecs, and sends 20 ms frames over a
WebSocket. Incoming audio is decoded per speaker and played through Web Audio. The server sends a
scalar gain with each packet, never a coordinate.

## Commands

All commands run on the server; the player must be in game to receive a link.

| Command | Who | Effect |
|---|---|---|
| `/svcweb link` | any player | Issues a single-use link valid for `link_ttl_seconds` (default 10 minutes) |
| `/svcweb revoke` | any player | Revokes your links and ends your sessions |
| `/svcweb groups` | any player | Lists groups that can be joined from the browser (no password) |
| `/svcweb group <name>` | any player | Joins a passwordless group |
| `/svcweb group leave` | any player | Leaves your group |
| `/svcweb status` | any player | Shows the listener state, TLS mode and whether voice chat is attached |

A link is consumed the first time the browser connects. The page keeps a separate session token so a
network drop reconnects without a new command.

## Configuration

`config/simplevoiceweb.properties` is written with defaults on first start. The important settings:

```properties
enabled=true
bind_address=0.0.0.0
port=8080
# The external address players reach. Required, and must be https:// unless tls_mode=off.
public_url=https://voice.example.com
# Where "/" redirects. The mod never serves anything at the root.
redirect_url=https://github.com/retiredroca/Simple-Voice-Web

link_ttl_seconds=600
session_ttl_minutes=720
max_links_per_player=3
command_requires_op=false

# acme | manual | off
tls_mode=acme

# tls_mode=manual
tls_certificate_file=certs/fullchain.pem
tls_private_key_file=certs/privkey.pem

# tls_mode=acme (built-in Let's Encrypt via DNS-01 + DuckDNS)
acme_email=you@example.com
acme_domain=voice.example.duckdns.org
duckdns_subdomain=voice.example
duckdns_token=your-duckdns-token
acme_directory=https://acme-v02.api.letsencrypt.org/directory
acme_renew_days=30
acme_staging=false
dns_resolver=

audio_proximity=true
audio_groups=true
```

### TLS modes

- **`acme`** - built into the mod. On startup it registers an ACME account, publishes the DNS-01
  challenge as a TXT record through DuckDNS, waits for it to propagate, obtains the certificate and
  renews it daily when fewer than `acme_renew_days` remain. DuckDNS keeps one TXT record per domain,
  so the mod orders one name at a time. `acme_domain` must be the DuckDNS name, e.g.
  `voice.example.duckdns.org`, and `duckdns_subdomain` is the part before `.duckdns.org`. Set
  `acme_staging=true` to test against Let's Encrypt staging first; browsers will not trust staging
  certificates, so only use it to validate the flow.
- **`manual`** - point `tls_certificate_file` and `tls_private_key_file` at a certificate and key
  (PKCS#8 or RSA private key, PEM). This is for certificates you manage yourself.
- **`off`** - serve plain HTTP and let a reverse proxy terminate TLS. **This is the recommended
  setup** (below). With this mode ACME and DuckDNS are not used at all and can be left blank.

Changing `bind_address`, `port`, `public_url` or the TLS settings requires a server restart.

## Recommended: Nginx Proxy Manager in front

Terminating TLS at a proxy is simpler and more reliable than exposing the mod's own listener, and it
keeps ACME/DuckDNS out of the mod entirely. Set `tls_mode=off`, bind the mod to a private interface
(for example `bind_address=127.0.0.1`, `port=8080`), and set `public_url` to the external HTTPS URL.

In Nginx Proxy Manager:

1. **Hosts → Proxy Hosts → Add Proxy Host**.
2. **Details**: Domain Names `voice.example.com`; Scheme `http`; Forward Hostname/IP `127.0.0.1`
   (or the server's LAN address); Forward Port `8080`. Enable **Websockets Support**.
3. **SSL**: request a new Let's Encrypt certificate, enable **Force SSL** and **HTTP/2**.
   Nginx Proxy Manager performs HTTP-01 validation and renews the certificate itself.
4. Save. Open `https://voice.example.com/` and confirm it redirects to the project's GitHub page;
   `/health` should return `ok`.

WebSocket support is required: the voice transport is a WebSocket. The proxy must not buffer it, so
keep proxy buffering off for this host. If you use raw nginx instead, the relevant location is:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
    proxy_buffering off;
}
```

Do not expose the mod's plain-HTTP port directly to the internet: the keyed link and the audio would
travel in clear text.

## Security model

- The link is 32 random bytes, base64url, valid for a short time and **single-use**. It is revoked
  when the player disconnects from the server.
- Only `/join/<key>` serves the page; `/` redirects; everything else is a 404. There is no directory
  listing and no static file serving.
- The key is bound to the player it was issued to. If they leave the server, any open session is
  closed.
- The web page contains no third-party scripts, fonts, analytics or CDNs.
- **Positions stay on the server.** The browser receives a gain per audio packet and no coordinates,
  so it cannot reconstruct anyone's location. There is consequently no stereo panning: proximity is
  reproduced as loudness only.

## Building

```bash
./gradlew -Pmc=1.21.1 build   # Java 21 toolchain
./gradlew -Pmc=26.3 build     # Java 25 toolchain (modern, deobfuscated generation)
```

Gradle 9.5.1 runs on JDK 21 or 25. The 1.21.1 build compiles with a Java 21 toolchain and the
26.3 build with Java 25, so both JDKs must be available. Run Gradle on JDK 21 and make sure Gradle
can detect the JDK 25 installation; if it cannot, set `org.gradle.java.installations.paths` in
`gradle.properties`.

Both versions use the same sources. 26.3 is the deobfuscated generation and builds with the renamed
`net.fabricmc.fabric-loom` plugin for Fabric and ModDevGradle for NeoForge; 1.21.1 uses Loom 1.9.2
and NeoGradle. The release artifacts are `build/release/`:

- `*-fabric.jar` and `*-neoforge.jar` - per-loader.
- `*-universal.jar` - one jar for both loaders.

Use the universal jar unless your server setup requires a loader-specific one.

## Limitations

- Voice chat's own audio is not relayed through the browser for players who have the mod installed;
  `AudioSender` refuses them by design.
- Password-protected groups cannot be joined from the browser.
- Opus packets from the browser are paced by the page; the server does not re-time them.
- The web listener is HTTP/1.1 only. Reverse proxies should keep HTTP/1.1 to the backend.
- The browser is a second audio device for a player who is already in the game. It does not log a
  player into the Minecraft server.

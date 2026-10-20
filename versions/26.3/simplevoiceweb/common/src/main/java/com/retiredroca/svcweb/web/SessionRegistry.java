package com.retiredroca.svcweb.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live web sessions.
 *
 * <p>The keyed link is single-use, so it cannot also be the reconnect credential. When a link is
 * redeemed the session issues its own token; the page keeps it for the tab and uses it to reconnect
 * after a network drop, without the player having to run the command again.
 */
public final class SessionRegistry {

    public record Session(String token, UUID player, long expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SessionRegistry(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public synchronized Session create(UUID player) {
        purge();
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        Session session = new Session(ENCODER.encodeToString(token), player,
                System.currentTimeMillis() + ttlMillis);
        sessions.put(session.token(), session);
        return session;
    }

    /** Looks up a session, extending its life while it is in use. */
    public Session touch(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        Session renewed = new Session(session.token(), session.player(),
                System.currentTimeMillis() + ttlMillis);
        sessions.put(token, renewed);
        return renewed;
    }

    public synchronized void remove(String token) {
        sessions.remove(token);
    }

    /** Drops every session for a player, used when they disconnect. */
    public synchronized void revoke(UUID player) {
        sessions.values().removeIf(session -> session.player().equals(player));
    }

    public synchronized void clear() {
        sessions.clear();
    }

    public synchronized int size() {
        purge();
        return sessions.size();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> session.expiresAt() < now);
    }
}

package com.retiredroca.svcweb.web;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and redeems the keyed links players are given.
 *
 * <p>A link is short-lived and single-use: it is removed the moment a session consumes it, so a URL
 * that leaks after use (a screenshot, browser history) cannot be replayed. It is also revoked when
 * the player leaves the server, so a link cannot outlive the session it belongs to.
 */
public final class LinkRegistry {

    public record Link(UUID player, long expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxPerPlayer;

    public LinkRegistry(long ttlMillis, int maxPerPlayer) {
        this.ttlMillis = ttlMillis;
        this.maxPerPlayer = Math.max(1, maxPerPlayer);
    }

    /** Creates a link for a player, evicting that player's oldest link if they are at the cap. */
    public synchronized String issue(UUID player) {
        purgeExpired();
        List<Map.Entry<String, Link>> owned = new ArrayList<>();
        for (Map.Entry<String, Link> entry : links.entrySet()) {
            if (entry.getValue().player().equals(player)) {
                owned.add(entry);
            }
        }
        owned.sort((a, b) -> Long.compare(a.getValue().expiresAt(), b.getValue().expiresAt()));
        while (owned.size() >= maxPerPlayer) {
            links.remove(owned.remove(0).getKey());
        }

        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        String key = ENCODER.encodeToString(token);
        links.put(key, new Link(player, System.currentTimeMillis() + ttlMillis));
        return key;
    }

    /** Redeems a link, removing it. Returns {@code null} if it is unknown or expired. */
    public synchronized UUID consume(String key) {
        Link link = links.remove(key);
        if (link == null || link.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        return link.player();
    }

    /** True when the link exists and has not expired, without consuming it. */
    public boolean valid(String key) {
        Link link = links.get(key);
        return link != null && link.expiresAt() >= System.currentTimeMillis();
    }

    /** Drops every link belonging to a player, used when they disconnect. */
    public synchronized void revoke(UUID player) {
        links.entrySet().removeIf(entry -> entry.getValue().player().equals(player));
    }

    public synchronized void clear() {
        links.clear();
    }

    public synchronized int size() {
        purgeExpired();
        return links.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Link>> iterator = links.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt() < now) {
                iterator.remove();
            }
        }
    }
}

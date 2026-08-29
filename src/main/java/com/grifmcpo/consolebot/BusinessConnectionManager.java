package com.grifmcpo.consolebot;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранит соответствие Telegram userId -> business_connection_id
 */
public class BusinessConnectionManager {
    private final ConcurrentHashMap<Long, String> map = new ConcurrentHashMap<>();

    public void put(long userId, String connectionId) {
        if (connectionId == null) return;
        map.put(userId, connectionId);
    }

    public String get(long userId) {
        return map.get(userId);
    }

    public boolean has(long userId) {
        return map.containsKey(userId);
    }

    public void remove(long userId) {
        map.remove(userId);
    }
}

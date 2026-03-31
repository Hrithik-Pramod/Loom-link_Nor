package com.loomlink.edge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central event bus for the Live Feed — powers the real-time dashboard ticker.
 *
 * The SAP OData listener scheduler publishes classification events here.
 * The SSE endpoint subscribes dashboard clients to receive them in real-time.
 */
@Service
public class LiveFeedEventBus {

    private static final Logger log = LoggerFactory.getLogger(LiveFeedEventBus.class);

    /** Active SSE connections from dashboard clients. */
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Recent events buffer (last 50) for new subscribers to catch up. */
    private final LinkedList<Map<String, Object>> recentEvents = new LinkedList<>();
    private static final int MAX_RECENT = 50;

    /**
     * Subscribe a new dashboard client to the live feed.
     * Returns an SseEmitter that streams events in real-time.
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // No timeout — stays open
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // Send recent events buffer so new subscribers see recent activity
        try {
            synchronized (recentEvents) {
                for (Map<String, Object> event : recentEvents) {
                    emitter.send(SseEmitter.event()
                            .name("live-feed")
                            .data(event));
                }
            }
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        log.info("Live Feed subscriber connected — {} active clients", emitters.size());
        return emitter;
    }

    /**
     * Publish an event to all connected dashboard clients.
     *
     * @param source Event source: "SAP_ODATA", "SARA", "MISSION", "SYSTEM"
     * @param type Event type: "CLASSIFICATION", "EMISSION", "MISSION_PLANNED", "RISK_UPDATE", etc.
     * @param title Short title for the ticker
     * @param detail Detailed description
     * @param metadata Additional key-value data
     */
    public void publish(String source, String type, String title, String detail, Map<String, Object> metadata) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("timestamp", Instant.now().toString());
        event.put("source", source);
        event.put("type", type);
        event.put("title", title);
        event.put("detail", detail);
        if (metadata != null) {
            event.putAll(metadata);
        }

        // Buffer for new subscribers
        synchronized (recentEvents) {
            recentEvents.addLast(event);
            while (recentEvents.size() > MAX_RECENT) {
                recentEvents.removeFirst();
            }
        }

        // Broadcast to all active clients
        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("live-feed")
                        .data(event));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }

    /** Get count of active subscribers. */
    public int getSubscriberCount() {
        return emitters.size();
    }

    /** Get recent events buffer. */
    public List<Map<String, Object>> getRecentEvents() {
        synchronized (recentEvents) {
            return new ArrayList<>(recentEvents);
        }
    }

    /** Clear the recent events buffer — used during demo reset. */
    public void clearHistory() {
        synchronized (recentEvents) {
            recentEvents.clear();
        }
        log.info("Live Feed event history cleared");
    }
}

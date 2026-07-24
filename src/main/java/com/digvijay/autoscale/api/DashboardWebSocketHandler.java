package com.digvijay.autoscale.api;

import com.digvijay.autoscale.metrics.IntervalRecord;
import com.digvijay.autoscale.metrics.RunTimelineStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Broadcasts each completed interval to connected dashboards. On connect a
 * client is replayed the full timeline so far (backlog), so opening the page
 * mid-run — or after a run has finished — still shows the whole chart. Live
 * updates then arrive one {@link IntervalRecord} at a time. Sending is
 * best-effort: a dead client is dropped and never breaks the run loop.
 */
@Slf4j
@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private final RunTimelineStore timeline;
    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    public DashboardWebSocketHandler(RunTimelineStore timeline, ObjectMapper objectMapper) {
        this.timeline = timeline;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        for (IntervalRecord record : timeline.all()) {
            send(session, record);
        }
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @EventListener
    public void onInterval(IntervalRecord record) {
        for (WebSocketSession session : sessions) {
            send(session, record);
        }
    }

    /** Tell every connected dashboard to clear its charts for a fresh run. */
    public void broadcastReset() {
        TextMessage reset = new TextMessage("{\"type\":\"reset\"}");
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                session.sendMessage(reset);
            } catch (Exception e) {
                sessions.remove(session);
            }
        }
    }

    private void send(WebSocketSession session, IntervalRecord record) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            // Best-effort: serialization (Jackson, unchecked) or the socket write
            // (IOException) failing must never break the run loop — just drop the client.
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(record)));
        } catch (Exception e) {
            log.debug("Dropping dashboard client after send failure: {}", e.getMessage());
            sessions.remove(session);
        }
    }

}
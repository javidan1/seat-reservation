package com.seatres.infra.sse;

import com.seatres.application.SeatChangedEvent;
import com.seatres.domain.SeatChange;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans committed seat changes out to SSE subscribers, per event.
 *
 * <p>Listens AFTER_COMMIT only — a client must never see a state that later rolled back. Changes
 * carry the seat {@code version}; clients drop anything older than what they already show, which
 * makes delivery order and duplicates irrelevant. A missed message is healed by the client's
 * snapshot re-fetch on reconnect.
 */
@Component
public class SeatMapBroadcaster {

  private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

  private final Map<UUID, Set<SseEmitter>> subscribersByEvent = new ConcurrentHashMap<>();

  public SseEmitter subscribe(UUID eventId) {
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
    Set<SseEmitter> subscribers =
        subscribersByEvent.computeIfAbsent(eventId, k -> ConcurrentHashMap.newKeySet());
    subscribers.add(emitter);
    emitter.onCompletion(() -> subscribers.remove(emitter));
    emitter.onTimeout(() -> subscribers.remove(emitter));
    emitter.onError(t -> subscribers.remove(emitter));
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException e) {
      subscribers.remove(emitter);
    }
    return emitter;
  }

  @TransactionalEventListener
  public void onSeatChanged(SeatChangedEvent event) {
    Map<UUID, List<SeatChange>> byEvent =
        event.changes().stream().collect(Collectors.groupingBy(SeatChange::eventId));
    byEvent.forEach(
        (eventId, changes) -> {
          Set<SseEmitter> subscribers = subscribersByEvent.get(eventId);
          if (subscribers == null) {
            return;
          }
          for (SseEmitter emitter : subscribers) {
            try {
              emitter.send(SseEmitter.event().name("seat-change").data(changes));
            } catch (Exception e) {
              subscribers.remove(emitter);
            }
          }
        });
  }

  /** Keeps intermediaries from closing quiet connections. */
  @Scheduled(fixedDelay = 15_000)
  public void heartbeat() {
    subscribersByEvent.forEach(
        (eventId, subscribers) -> {
          for (SseEmitter emitter : subscribers) {
            try {
              emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
              subscribers.remove(emitter);
            }
          }
        });
  }
}

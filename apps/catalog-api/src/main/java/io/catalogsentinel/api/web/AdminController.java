package io.catalogsentinel.api.web;

import io.catalogsentinel.core.messaging.DeadLetterService;
import io.catalogsentinel.core.messaging.QueueClient.ReceivedMessage;
import io.catalogsentinel.core.messaging.QueueNames;
import io.catalogsentinel.core.outbox.OutboxRepository;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Operator endpoints (role ADMIN when security is enabled): inspect and replay dead-letter queues, inspect the
 * outbox backlog and requeue rows that exhausted their publish attempts. Every action is logged with the
 * request's correlation id.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Operations", description = "Dead-letter queues and outbox (ADMIN)")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final int MAX_BODY_CHARS = 4_000;

    private final DeadLetterService deadLetters;
    private final QueueNames names;
    private final OutboxRepository outbox;
    private final Clock clock;

    public AdminController(DeadLetterService deadLetters, QueueNames names, OutboxRepository outbox, Clock clock) {
        this.deadLetters = deadLetters;
        this.names = names;
        this.outbox = outbox;
        this.clock = clock;
    }

    public record DeadLetterView(String messageId, int receiveCount, Instant sentAt, Map<String, String> attributes,
                                 String body, boolean bodyTruncated) {
    }

    public record DeadLetterPeek(String queue, String sourceQueue, List<DeadLetterView> messages, String note) {
    }

    public record ReplayRequest(@NotBlank String queue, @Min(1) @Max(1000) Integer maxMessages) {
    }

    @GetMapping("/dead-letters")
    @Operation(summary = "Peek at up to 10 dead-lettered messages",
            description = "Messages are received with a 1 s visibility timeout and reappear in the DLQ; peeking does "
                    + "not remove them. Each carries failureReason and receiveCount attributes when the consumer "
                    + "dead-lettered it explicitly; messages moved by the queue's redrive policy have neither.")
    public DeadLetterPeek peek(@RequestParam String queue, @RequestParam(defaultValue = "10") int max) {
        String source = sourceOf(queue);
        if (max < 1 || max > 10) {
            throw new DomainValidationException("max", "must be between 1 and 10");
        }
        List<DeadLetterView> views = deadLetters.peek(queue, max).stream().map(AdminController::view).toList();
        return new DeadLetterPeek(queue, source, views,
                "Approximate: SQS returns a sample of available messages, not necessarily the oldest.");
    }

    @PostMapping("/dead-letters/replay")
    @Operation(summary = "Replay dead-lettered messages to their source queue",
            description = "Safe to repeat: consumers are idempotent, so a message that was already applied is a "
                    + "no-op. Fix the cause first (for example register the missing source), or the messages "
                    + "return to the DLQ.")
    public DeadLetterService.ReplayResult replay(@Valid @RequestBody ReplayRequest request) {
        sourceOf(request.queue());
        int max = request.maxMessages() == null ? 100 : request.maxMessages();
        DeadLetterService.ReplayResult result = deadLetters.replay(request.queue(), max);
        log.info("operator replay of {}: replayed={} failed={}", request.queue(), result.replayed(), result.failed());
        return result;
    }

    @GetMapping("/outbox")
    @Operation(summary = "Outbox backlog: pending and failed rows, age of the oldest pending row")
    public Map<String, Object> outboxStats() {
        OutboxRepository.OutboxStats s = outbox.stats(clock.instant());
        return Map.of("pending", s.pending(), "failed", s.failed(),
                "oldestPendingSeconds", s.oldestPendingAge().toMillis() / 1000.0);
    }

    @PostMapping("/outbox/requeue-failed")
    @Operation(summary = "Give FAILED outbox rows a fresh set of publish attempts")
    public Map<String, Integer> requeueFailed() {
        int n = outbox.requeueFailed(clock.instant());
        log.info("operator requeued {} failed outbox row(s)", n);
        return Map.of("requeued", n);
    }

    private String sourceOf(String dlq) {
        try {
            return names.sourceOfDlq(dlq);
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("queue", "must be one of " + names.listingEventsDlq() + ", "
                    + names.changeEventsDlq());
        }
    }

    private static DeadLetterView view(ReceivedMessage m) {
        String body = m.body();
        boolean truncated = body != null && body.length() > MAX_BODY_CHARS;
        return new DeadLetterView(m.messageId(), m.receiveCount(), m.sentAt(), m.attributes(),
                truncated ? body.substring(0, MAX_BODY_CHARS) : body, truncated);
    }
}

package io.catalogsentinel.platform.sqs;

import io.catalogsentinel.core.messaging.QueueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS SDK v2 implementation of {@link QueueClient}. Works against Amazon SQS and SQS-compatible servers
 * (ElasticMQ locally). Queue URLs are resolved once and cached. Batch APIs are used for send and delete
 * (SQS limit: 10 entries per batch).
 */
public final class SqsQueueClient implements QueueClient {

    private static final Logger log = LoggerFactory.getLogger(SqsQueueClient.class);
    private static final int SQS_BATCH_LIMIT = 10;
    private static final int MAX_VISIBILITY_SECONDS = 12 * 60 * 60;

    private final SqsClient sqs;
    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();

    public SqsQueueClient(SqsClient sqs) {
        this.sqs = sqs;
    }

    public String queueUrl(String queue) {
        return queueUrls.computeIfAbsent(queue, q ->
                sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(q).build()).queueUrl());
    }

    @Override
    public List<ReceivedMessage> receive(String queue, int maxMessages, Duration wait, Duration visibility) {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl(queue))
                .maxNumberOfMessages(Math.min(Math.max(maxMessages, 1), SQS_BATCH_LIMIT))
                .waitTimeSeconds((int) Math.min(20, Math.max(0, wait.toSeconds())))
                .visibilityTimeout(clampVisibility(visibility))
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                        MessageSystemAttributeName.SENT_TIMESTAMP)
                .messageAttributeNames("All")
                .build();
        List<ReceivedMessage> out = new ArrayList<>();
        for (Message m : sqs.receiveMessage(request).messages()) {
            Map<String, String> attributes = new HashMap<>();
            m.messageAttributes().forEach((k, v) -> {
                if (v.stringValue() != null) {
                    attributes.put(k, v.stringValue());
                }
            });
            Map<MessageSystemAttributeName, String> system = m.attributes();
            int receiveCount = parseInt(system.get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT), 1);
            String sent = system.get(MessageSystemAttributeName.SENT_TIMESTAMP);
            Instant sentAt = sent == null ? Instant.now() : Instant.ofEpochMilli(Long.parseLong(sent));
            out.add(new ReceivedMessage(m.messageId(), m.receiptHandle(), m.body(), attributes, receiveCount, sentAt));
        }
        return out;
    }

    @Override
    public void delete(String queue, List<String> receiptHandles) {
        String url = queueUrl(queue);
        for (int start = 0; start < receiptHandles.size(); start += SQS_BATCH_LIMIT) {
            List<String> chunk = receiptHandles.subList(start, Math.min(start + SQS_BATCH_LIMIT, receiptHandles.size()));
            List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                entries.add(DeleteMessageBatchRequestEntry.builder().id("d" + i).receiptHandle(chunk.get(i)).build());
            }
            var response = sqs.deleteMessageBatch(DeleteMessageBatchRequest.builder().queueUrl(url).entries(entries).build());
            for (BatchResultErrorEntry failed : response.failed()) {
                // Usually an expired receipt handle: the message will be redelivered and handled idempotently.
                log.warn("sqs delete failed queue={} entry={} code={} message={}", queue, failed.id(), failed.code(),
                        failed.message());
            }
        }
    }

    @Override
    public void changeVisibility(String queue, String receiptHandle, Duration visibility) {
        sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl(queue))
                .receiptHandle(receiptHandle)
                .visibilityTimeout(clampVisibility(visibility))
                .build());
    }

    @Override
    public SendResult send(String queue, List<OutboundMessage> messages) {
        List<String> ok = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        String url;
        try {
            url = queueUrl(queue);
        } catch (SdkException e) {
            messages.forEach(m -> failed.put(m.id(), "queue unavailable: " + e.getMessage()));
            return new SendResult(ok, failed);
        }
        for (int start = 0; start < messages.size(); start += SQS_BATCH_LIMIT) {
            List<OutboundMessage> chunk = messages.subList(start, Math.min(start + SQS_BATCH_LIMIT, messages.size()));
            // Batch entry ids must be unique within a batch and match [A-Za-z0-9_-]{1,80}; map them back afterwards.
            Map<String, String> entryToMessageId = new HashMap<>();
            List<SendMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                OutboundMessage m = chunk.get(i);
                String entryId = "e" + i;
                entryToMessageId.put(entryId, m.id());
                Map<String, MessageAttributeValue> attrs = new HashMap<>();
                m.attributes().forEach((k, v) -> attrs.put(k,
                        MessageAttributeValue.builder().dataType("String").stringValue(v).build()));
                entries.add(SendMessageBatchRequestEntry.builder()
                        .id(entryId)
                        .messageBody(m.body())
                        .messageAttributes(attrs)
                        .delaySeconds((int) Math.min(900, m.delay().toSeconds()))
                        .build());
            }
            try {
                SendMessageBatchResponse response = sqs.sendMessageBatch(
                        SendMessageBatchRequest.builder().queueUrl(url).entries(entries).build());
                for (SendMessageBatchResultEntry s : response.successful()) {
                    ok.add(entryToMessageId.get(s.id()));
                }
                for (BatchResultErrorEntry f : response.failed()) {
                    failed.put(entryToMessageId.get(f.id()), f.code() + ": " + f.message());
                }
            } catch (SdkException e) {
                chunk.forEach(m -> failed.put(m.id(), e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return new SendResult(ok, failed);
    }

    @Override
    public QueueDepth depth(String queue) {
        Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl(queue))
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)
                .build()).attributes();
        return new QueueDepth(
                parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES), 0),
                parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE), 0),
                parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED), 0));
    }

    private static int clampVisibility(Duration visibility) {
        return (int) Math.min(MAX_VISIBILITY_SECONDS, Math.max(0, visibility.toSeconds()));
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

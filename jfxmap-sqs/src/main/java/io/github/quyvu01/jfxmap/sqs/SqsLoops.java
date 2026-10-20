package io.github.quyvu01.jfxmap.sqs;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the client and the server share: long polling, deleting the received messages, string attributes. */
final class SqsLoops {
    /** FxMap's {@code DefaultWaitTimeSeconds}: the longest that SQS allows. */
    static final Duration DEFAULT_WAIT_TIME = Duration.ofSeconds(20);
    static final int MAX_MESSAGES = 10;

    private SqsLoops() {
    }

    static List<Message> receive(SqsClient sqs, String queueUrl, Duration waitTime) {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(MAX_MESSAGES)
                .waitTimeSeconds((int) waitTime.toSeconds())
                .messageAttributeNames("All")
                .build()).messages();
    }

    static void delete(SqsClient sqs, String queueUrl, List<Message> messages) {
        if (messages.isEmpty()) return;
        var entries = new ArrayList<DeleteMessageBatchRequestEntry>(messages.size());
        for (int i = 0; i < messages.size(); i++)
            entries.add(DeleteMessageBatchRequestEntry.builder().id(Integer.toString(i))
                    .receiptHandle(messages.get(i).receiptHandle()).build());
        sqs.deleteMessageBatch(b -> b.queueUrl(queueUrl).entries(entries));
    }

    static MessageAttributeValue string(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    /** The string value of an attribute, or null. */
    static String attribute(Message message, String name) {
        var value = message.messageAttributes().get(name);
        return value == null ? null : value.stringValue();
    }

    /** Every attribute of a message as a string: what an FxMap server gives to the handler as headers. */
    static Map<String, String> headers(Message message) {
        var headers = new LinkedHashMap<String, String>();
        message.messageAttributes().forEach((name, value) -> {
            if (value.stringValue() != null) headers.put(name, value.stringValue());
        });
        return headers;
    }

    static Duration checkWaitTime(Duration waitTime) {
        if (waitTime == null || waitTime.isNegative() || waitTime.compareTo(DEFAULT_WAIT_TIME) > 0)
            throw new IllegalArgumentException("The wait time must be between 0 and 20 seconds.");
        return waitTime;
    }

    static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

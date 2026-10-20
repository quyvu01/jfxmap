package io.github.quyvu01.jfxmap.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/** Creates and finds topics, as FxMap does: a topic that already exists is left as it is. */
final class KafkaTopics {
    private KafkaTopics() {
    }

    static void create(Map<String, Object> config, Collection<String> topics, int partitions, short replicationFactor) {
        try (var admin = Admin.create(config)) {
            var newTopics = topics.stream().map(topic -> new NewTopic(topic, partitions, replicationFactor)).toList();
            for (var result : admin.createTopics(newTopics).values().values()) {
                try {
                    result.get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException))
                        throw new IllegalStateException("A Kafka topic could not be created: " + e.getCause(), e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating Kafka topics.", e);
        }
    }

    static boolean exists(Map<String, Object> config, String topic) {
        try (var admin = Admin.create(config)) {
            admin.describeTopics(List.of(topic)).allTopicNames().get();
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) return false;
            throw new IllegalStateException("The Kafka topic " + topic + " could not be described: " + e.getCause(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while describing a Kafka topic.", e);
        }
    }
}

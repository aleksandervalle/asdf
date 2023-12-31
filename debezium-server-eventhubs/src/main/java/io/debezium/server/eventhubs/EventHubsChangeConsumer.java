/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.eventhubs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;

import io.debezium.DebeziumException;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.DebeziumEngine.RecordCommitter;
import io.debezium.server.BaseChangeConsumer;
import io.debezium.server.CustomConsumerBuilder;

/**
 * This sink adapter delivers change event messages to Azure Event Hubs
 *
 * @author Abhishek Gupta
 */
@Named("eventhubs")
@Dependent
public class EventHubsChangeConsumer extends BaseChangeConsumer
        implements DebeziumEngine.ChangeConsumer<ChangeEvent<Object, Object>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventHubsChangeConsumer.class);

    private static final String PROP_PREFIX = "debezium.sink.eventhubs.";
    private static final String PROP_CONNECTION_STRING_NAME = PROP_PREFIX + "connectionstring";
    private static final String PROP_EVENTHUB_NAME = PROP_PREFIX + "hubname";
    private static final String PROP_EVENTHUB_NAME_LIST = PROP_PREFIX + "hubname.list";
    private static final String PROP_PARTITION_ID = PROP_PREFIX + "partitionid";
    private static final String PROP_PARTITION_KEY = PROP_PREFIX + "partitionkey";
    // maximum size for the batch of events (bytes)
    private static final String PROP_MAX_BATCH_SIZE = PROP_PREFIX + "maxbatchsize";

    private String connectionString;
    private String eventHubName;
    private String[] eventHubNameList;
    private String configuredPartitionId;
    private String configuredPartitionKey;
    private Integer maxBatchSize;

    // connection string format -
    // Endpoint=sb://<NAMESPACE>/;SharedAccessKeyName=<KEY_NAME>;SharedAccessKey=<ACCESS_KEY>;EntityPath=<HUB_NAME>
    private static final String CONNECTION_STRING_FORMAT = "%s;EntityPath=%s";
    private Map<String, BatchManager> batchManagers = new HashMap<>();

    @Inject
    @CustomConsumerBuilder
    Instance<EventHubProducerClient> customProducer;

    @PostConstruct
    void connect() {
        final Config config = ConfigProvider.getConfig();

        // Required config
        connectionString = config.getValue(PROP_CONNECTION_STRING_NAME, String.class);
        eventHubName = config.getValue(PROP_EVENTHUB_NAME, String.class);

        // Optional config
        maxBatchSize = config.getOptionalValue(PROP_MAX_BATCH_SIZE, Integer.class).orElse(0);
        configuredPartitionId = config.getOptionalValue(PROP_PARTITION_ID, String.class).orElse("");
        configuredPartitionKey = config.getOptionalValue(PROP_PARTITION_KEY, String.class).orElse("");
        eventHubNameList = config.getOptionalValue(PROP_EVENTHUB_NAME_LIST, String.class).orElse("").split(",");

        try {
            createDefaultProducerAndBatchManager();
            createBatchManagersFromEventHubNameList();
        }
        catch (Exception e) {
            throw new DebeziumException(e);
        }
    }

    private void createDefaultProducerAndBatchManager() {
        EventHubProducerClient defaultProducer;
        BatchManager defaultBatchManager;

        if (customProducer.isResolvable()) {
            defaultProducer = customProducer.get();
            int partitionCount = (int) defaultProducer.getPartitionIds().stream().count();
            validatePartitionId(partitionCount);

            defaultBatchManager = new BatchManager(defaultProducer, configuredPartitionId, configuredPartitionKey, partitionCount, maxBatchSize);
            LOGGER.info("Obtained custom configured Event Hubs client ({} partitions in hub) for namespace '{}'",
                    partitionCount,
                    customProducer.get().getFullyQualifiedNamespace());
        }
        else {
            String defaultConnectionString = String.format(CONNECTION_STRING_FORMAT, connectionString, eventHubName);
            defaultProducer = new EventHubClientBuilder().connectionString(defaultConnectionString).buildProducerClient();
            int partitionCount = (int) defaultProducer.getPartitionIds().stream().count();
            validatePartitionId(partitionCount);

            defaultBatchManager = new BatchManager(defaultProducer, configuredPartitionId, configuredPartitionKey, partitionCount, maxBatchSize);
            LOGGER.info("Obtained default configured Event Hubs client for event hub '{}' ({} partitions)", eventHubName, partitionCount);
        }

        batchManagers.put(eventHubName, defaultBatchManager);
    }

    private void createBatchManagersFromEventHubNameList() {
        for (String hubName : eventHubNameList) {
            if (!hubName.equals(eventHubName)) {
                String finalConnectionString = String.format(CONNECTION_STRING_FORMAT, connectionString, hubName);
                EventHubProducerClient producer = new EventHubClientBuilder().connectionString(finalConnectionString).buildProducerClient();

                int partitionCount = (int) producer.getPartitionIds().stream().count();
                validatePartitionId(partitionCount);

                BatchManager batchManager = new BatchManager(producer, configuredPartitionId, configuredPartitionKey, partitionCount, maxBatchSize);
                batchManagers.put(hubName, batchManager);
                LOGGER.info("Obtained Event Hubs client for event hub '{}' ({} partitions)", hubName, partitionCount);
            }
        }
    }

    @PreDestroy
    void close() {
        try {
            batchManagers.values().forEach(BatchManager::closeProducer);
            LOGGER.info("Closed Event Hubs producer clients");
        }
        catch (Exception e) {
            LOGGER.warn("Exception while closing Event Hubs producers: {}", e);
        }
    }

    @Override
    public void handleBatch(List<ChangeEvent<Object, Object>> records,
                            RecordCommitter<ChangeEvent<Object, Object>> committer)
            throws InterruptedException {
        LOGGER.trace("Event Hubs sink adapter processing change events");

        batchManagers.values().forEach(BatchManager::initializeBatch);

        for (int recordIndex = 0; recordIndex < records.size();) {
            int start = recordIndex;
            LOGGER.trace("Emitting events starting from index {}", start);

            // The inner loop adds as many records to the batch as possible, keeping track of the batch size
            for (; recordIndex < records.size(); recordIndex++) {
                ChangeEvent<Object, Object> record = records.get(recordIndex);

                if (null == record.value()) {
                    continue;
                }

                EventData eventData;
                if (record.value() instanceof String) {
                    eventData = new EventData((String) record.value());
                }
                else if (record.value() instanceof byte[]) {
                    eventData = new EventData(getBytes(record.value()));
                }
                else {
                    LOGGER.warn("Event data in record.value() is not of type String or byte[]");

                    continue;
                }

                // Find the partition to send eventData to.
                Integer targetPartitionId;

                if (!configuredPartitionId.isEmpty()) {
                    targetPartitionId = Integer.parseInt(configuredPartitionId);
                }
                else if (!configuredPartitionKey.isEmpty()) {
                    // The BatchManager
                    targetPartitionId = BatchManager.BATCH_INDEX_FOR_PARTITION_KEY;
                }
                else {
                    targetPartitionId = record.partition();

                    if (targetPartitionId == null) {
                        targetPartitionId = BatchManager.BATCH_INDEX_FOR_NO_PARTITION_ID;
                    }
                }

                try {
                    String destinationHub = record.destination();
                    BatchManager batchManager = batchManagers.get(destinationHub);

                    if (batchManager == null) {
                        batchManager = batchManagers.get(eventHubName);

                        if (batchManager == null) {
                            throw new DebeziumException(String.format("Could not find batch manager for destination hub {}, nor for the default configured event hub {}",
                                    destinationHub, eventHubName));
                        }
                    }

                    // Check that the target partition exists.
                    if (targetPartitionId < BatchManager.BATCH_INDEX_FOR_NO_PARTITION_ID || targetPartitionId > batchManager.getPartitionCount() - 1) {
                        throw new IndexOutOfBoundsException(
                                String.format("Target partition id %d does not exist in target EventHub %s", targetPartitionId, batchManager.getEventHubName()));
                    }

                    batchManager.sendEventToPartitionId(eventData, recordIndex, targetPartitionId);
                }
                catch (IllegalArgumentException e) {
                    // thrown by tryAdd if event data is null
                    throw new DebeziumException(e);
                }
                catch (AmqpException e) {
                    // tryAdd throws AmqpException if "eventData is larger than the maximum size of
                    // the EventDataBatch."
                    throw new DebeziumException("Event data was larger than the maximum size of the batch", e);
                }
                catch (Exception e) {
                    throw new DebeziumException(e);
                }
            }
        }

        batchManagers.values().forEach(BatchManager::closeAndEmitBatches);

        LOGGER.trace("Marking {} records as processed.", records.size());
        for (ChangeEvent<Object, Object> record : records) {
            committer.markProcessed(record);
        }
        committer.markBatchFinished();
        LOGGER.trace("Batch marked finished");
    }

    private void validatePartitionId(int partitionCount) {
        if (!configuredPartitionId.isEmpty() && Integer.parseInt(configuredPartitionId) > partitionCount - 1) {
            throw new IndexOutOfBoundsException(
                    String.format("Target partition id %s does not exist in target EventHub %s", configuredPartitionId, eventHubName));
        }
    }
}

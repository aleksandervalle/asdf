/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.Header;

/**
 * Basic services provided to all change consumers.
 *
 * @author Jiri Pechanec
 *
 */
public class BaseChangeConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseChangeConsumer.class);

    protected StreamNameMapper streamNameMapper = (x) -> x;

    @Inject
    Instance<StreamNameMapper> customStreamNameMapper;

    @PostConstruct
    void init() {
        if (customStreamNameMapper.isResolvable()) {
            streamNameMapper = customStreamNameMapper.get();
        }
        LOGGER.info("Using '{}' stream name mapper", streamNameMapper);
    }

    /**
     * Get a subset of the configuration properties that matches the given prefix.
     *
     * @param config    The global configuration object to extract the subset from.
     * @param prefix    The prefix to filter property names.
     *
     * @return          A subset of the original configuration properties containing property names
     *                  without the prefix.
     */
    protected Map<String, Object> getConfigSubset(Config config, String prefix) {
        final Map<String, Object> ret = new HashMap<>();

        for (String propName : config.getPropertyNames()) {
            if (propName.startsWith(prefix)) {
                final String newPropName = propName.substring(prefix.length());
                ret.put(newPropName, config.getConfigValue(propName).getValue());
            }
        }

        return ret;
    }

    protected byte[] getBytes(Object object) {
        if (object instanceof byte[]) {
            return (byte[]) object;
        }
        else if (object instanceof String) {
            return ((String) object).getBytes(StandardCharsets.UTF_8);
        }
        throw new DebeziumException(unsupportedTypeMessage(object));
    }

    protected String getString(Object object) {
        if (object instanceof String) {
            return (String) object;
        }
        throw new DebeziumException(unsupportedTypeMessage(object));
    }

    protected String unsupportedTypeMessage(Object object) {
        final String type = (object == null) ? "null" : object.getClass().getName();
        return "Unexpected data type '" + type + "'";
    }

    protected Map<String, String> convertHeaders(ChangeEvent<Object, Object> record) {
        List<Header<Object>> headers = record.headers();
        Map<String, String> result = new HashMap<>();
        for (Header<Object> header : headers) {
            result.put(header.getKey(), getString(header.getValue()));
        }
        return result;
    }
}

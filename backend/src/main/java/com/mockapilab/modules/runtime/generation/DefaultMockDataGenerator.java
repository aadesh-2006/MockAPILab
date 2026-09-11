package com.mockapilab.modules.runtime.generation;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedEndpoint;
import com.mockapilab.modules.contract.model.normalized.NormalizedMediaType;
import com.mockapilab.modules.contract.model.normalized.NormalizedResponse;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link MockDataGenerator}.
 */
@Component
public class DefaultMockDataGenerator implements MockDataGenerator {

    private final SchemaDataGenerator schemaDataGenerator;

    public DefaultMockDataGenerator(SchemaDataGenerator schemaDataGenerator) {
        this.schemaDataGenerator = schemaDataGenerator;
    }

    @Override
    public Object generate(NormalizedSchema schema, DataGenerationContext context) {
        return schemaDataGenerator.generate(schema, context);
    }

    @Override
    public Object generateResponsePayload(NormalizedEndpoint endpoint, String targetStatusCode, NormalizedContract contract, long seedValue) {
        NormalizedResponse response = findResponse(endpoint, targetStatusCode);
        if (response == null || response.contentTypes() == null || response.contentTypes().isEmpty()) {
            return null;
        }

        NormalizedMediaType mediaType = response.contentTypes().get("application/json");
        if (mediaType == null) {
            mediaType = response.contentTypes().values().iterator().next();
        }

        if (mediaType.example() != null) {
            return mediaType.example();
        }

        if (mediaType.schema() == null) {
            return null;
        }

        GenerationSeed seed = GenerationSeed.from(seedValue);
        DataGenerationContext context = DataGenerationContext.root(seed, contract);
        return schemaDataGenerator.generate(mediaType.schema(), context);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generateCollection(NormalizedSchema itemSchema, int count, long seedValue, NormalizedContract contract) {
        if (itemSchema == null || count <= 0) {
            return Collections.emptyList();
        }

        GenerationSeed rootSeed = GenerationSeed.from(seedValue);
        List<Map<String, Object>> list = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            GenerationSeed itemSeed = rootSeed.branch("item-" + i);
            DataGenerationContext context = DataGenerationContext.root(itemSeed, contract);

            Object generated = schemaDataGenerator.generate(itemSchema, context);
            Map<String, Object> entity;
            if (generated instanceof Map<?, ?> m) {
                entity = new LinkedHashMap<>((Map<String, Object>) m);
            } else {
                entity = new LinkedHashMap<>();
                entity.put("value", generated);
            }

            // Ensure stable entity ID if missing
            if (!entity.containsKey("id") || entity.get("id") == null) {
                entity.put("id", itemSeed.nextUuid().toString());
            }

            list.add(entity);
        }

        return list;
    }

    private NormalizedResponse findResponse(NormalizedEndpoint endpoint, String targetStatusCode) {
        if (endpoint.responses() == null || endpoint.responses().isEmpty()) {
            return null;
        }

        for (NormalizedResponse resp : endpoint.responses()) {
            if (targetStatusCode != null && targetStatusCode.equals(resp.statusCode())) {
                return resp;
            }
        }

        // Return first 2xx
        for (NormalizedResponse resp : endpoint.responses()) {
            if (resp.statusCode() != null && resp.statusCode().startsWith("2")) {
                return resp;
            }
        }

        return endpoint.responses().get(0);
    }
}

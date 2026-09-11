package com.mockapilab.modules.runtime.generation.generators;

import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.generation.DataGenerationContext;
import com.mockapilab.modules.runtime.generation.SchemaDataGenerator;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generator for nested object structures and arrays.
 */
@Component
public class CollectionValueGenerator {

    private SchemaDataGenerator schemaDataGenerator;

    public CollectionValueGenerator(@Lazy SchemaDataGenerator schemaDataGenerator) {
        this.schemaDataGenerator = schemaDataGenerator;
    }

    public void setSchemaDataGenerator(SchemaDataGenerator schemaDataGenerator) {
        this.schemaDataGenerator = schemaDataGenerator;
    }

    public List<Object> generateArray(NormalizedSchema schema, DataGenerationContext context) {
        if (context.isDepthExceeded() || schemaDataGenerator == null) {
            return Collections.emptyList();
        }

        NormalizedSchema itemSchema = schema.items();
        if (itemSchema == null) {
            return Collections.emptyList();
        }

        int size = context.getArraySize();
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            DataGenerationContext itemContext = context.forArrayItem(i);
            Object item = schemaDataGenerator.generate(itemSchema, itemContext);
            if (item != null) {
                list.add(item);
            }
        }
        return list;
    }

    public Map<String, Object> generateObject(NormalizedSchema schema, DataGenerationContext context) {
        if (context.isDepthExceeded() || schemaDataGenerator == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (schema.properties() == null || schema.properties().isEmpty()) {
            return result;
        }

        for (Map.Entry<String, NormalizedSchema> entry : schema.properties().entrySet()) {
            String propName = entry.getKey();
            NormalizedSchema propSchema = entry.getValue();

            DataGenerationContext propContext = context.forProperty(propName, propSchema);
            Object propVal = schemaDataGenerator.generate(propSchema, propContext);
            result.put(propName, propVal);
        }

        return result;
    }
}

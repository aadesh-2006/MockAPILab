package com.mockapilab;

import com.mockapilab.modules.contract.model.normalized.ContractMetadata;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.generation.DataGenerationContext;
import com.mockapilab.modules.runtime.generation.DefaultMockDataGenerator;
import com.mockapilab.modules.runtime.generation.GenerationSeed;
import com.mockapilab.modules.runtime.generation.SchemaDataGenerator;
import com.mockapilab.modules.runtime.generation.generators.BooleanValueGenerator;
import com.mockapilab.modules.runtime.generation.generators.CollectionValueGenerator;
import com.mockapilab.modules.runtime.generation.generators.DateTimeValueGenerator;
import com.mockapilab.modules.runtime.generation.generators.NumberValueGenerator;
import com.mockapilab.modules.runtime.generation.generators.StringValueGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockDataGeneratorTest {

    private DefaultMockDataGenerator generator;

    @BeforeEach
    void setUp() {
        StringValueGenerator stringGen = new StringValueGenerator();
        NumberValueGenerator numberGen = new NumberValueGenerator();
        BooleanValueGenerator booleanGen = new BooleanValueGenerator();
        DateTimeValueGenerator dateTimeGen = new DateTimeValueGenerator();
        CollectionValueGenerator collectionGen = new CollectionValueGenerator(null);

        SchemaDataGenerator schemaGen = new SchemaDataGenerator(
                stringGen,
                numberGen,
                booleanGen,
                dateTimeGen,
                collectionGen
        );

        this.generator = new DefaultMockDataGenerator(schemaGen);
    }

    private NormalizedContract emptyContract() {
        return new NormalizedContract(
                ContractMetadata.of("Test API", "Desc", "1.0"),
                Collections.emptyList(),
                Collections.emptyMap()
        );
    }

    @Test
    @DisplayName("1. Same seed produces identical generated object across executions")
    void testDeterminismWithSameSeed() {
        NormalizedSchema userSchema = NormalizedSchema.object(
                Map.of(
                        "id", NormalizedSchema.string("uuid", "User ID"),
                        "name", NormalizedSchema.string(null, "Full name"),
                        "email", NormalizedSchema.string("email", "Email address"),
                        "age", NormalizedSchema.integer(null, "Age in years")
                ),
                List.of("id", "name"),
                "User"
        );

        long seed = 12345L;
        DataGenerationContext ctx1 = DataGenerationContext.root(GenerationSeed.from(seed), emptyContract());
        DataGenerationContext ctx2 = DataGenerationContext.root(GenerationSeed.from(seed), emptyContract());

        Object result1 = generator.generate(userSchema, ctx1);
        Object result2 = generator.generate(userSchema, ctx2);

        assertThat(result1).isEqualTo(result2);
    }

    @Test
    @DisplayName("2. Different seeds produce different generated values")
    void testDifferentSeedsProduceDifferentValues() {
        NormalizedSchema schema = NormalizedSchema.string("email", "Email");

        DataGenerationContext ctx1 = DataGenerationContext.root(GenerationSeed.from(111L), emptyContract());
        DataGenerationContext ctx2 = DataGenerationContext.root(GenerationSeed.from(999L), emptyContract());

        Object email1 = generator.generate(schema, ctx1);
        Object email2 = generator.generate(schema, ctx2);

        assertThat(email1).isNotNull();
        assertThat(email2).isNotNull();
        assertThat(email1).isNotEqualTo(email2);
    }

    @Test
    @DisplayName("3. Format heuristics: email, uuid, date-time, date, and India-friendly phone")
    void testFormatHeuristics() {
        NormalizedSchema emailSchema = NormalizedSchema.string("email", "Email");
        NormalizedSchema uuidSchema = NormalizedSchema.string("uuid", "UUID");
        NormalizedSchema dateSchema = NormalizedSchema.string("date", "Date");
        NormalizedSchema dateTimeSchema = NormalizedSchema.string("date-time", "Timestamp");
        NormalizedSchema phoneSchema = NormalizedSchema.string(null, "Phone number");

        DataGenerationContext ctx = DataGenerationContext.root(GenerationSeed.from(42L), emptyContract());
        DataGenerationContext phoneCtx = ctx.forProperty("phone", phoneSchema);

        String email = (String) generator.generate(emailSchema, ctx);
        String uuidStr = (String) generator.generate(uuidSchema, ctx);
        String date = (String) generator.generate(dateSchema, ctx);
        String dateTime = (String) generator.generate(dateTimeSchema, ctx);
        String phone = (String) generator.generate(phoneSchema, phoneCtx);

        assertThat(email).contains("@").contains(".");
        assertThat(UUID.fromString(uuidStr)).isNotNull();
        assertThat(date).matches("^\\d{4}-\\d{2}-\\d{2}$");
        assertThat(dateTime).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");
        assertThat(phone).startsWith("+91-");
    }

    @Test
    @DisplayName("4. Enum generation selects from allowed constants deterministically")
    void testEnumGeneration() {
        List<String> statuses = List.of("PENDING", "ACTIVE", "ARCHIVED");
        NormalizedSchema enumSchema = NormalizedSchema.enumeration(statuses, "Status");

        DataGenerationContext ctx = DataGenerationContext.root(GenerationSeed.from(100L), emptyContract());
        Object val = generator.generate(enumSchema, ctx);

        assertThat(statuses).contains((String) val);
    }

    @Test
    @DisplayName("5. Numeric minimum and maximum bounds are strictly respected")
    void testNumericBounds() {
        NormalizedSchema boundedInt = new NormalizedSchema(
                "integer", null, "Bounded int", false, null, null, null, null, null, null, null,
                10.0, 20.0, null, null, null
        );

        for (long s = 1; s <= 20; s++) {
            DataGenerationContext ctx = DataGenerationContext.root(GenerationSeed.from(s), emptyContract());
            Number val = (Number) generator.generate(boundedInt, ctx);
            assertThat(val.intValue()).isBetween(10, 20);
        }
    }

    @Test
    @DisplayName("6. String minLength and maxLength constraints are strictly respected")
    void testStringLengthConstraints() {
        NormalizedSchema boundedStr = new NormalizedSchema(
                "string", null, "Bounded string", false, null, null, null, null, null, null, null,
                null, null, 5, 8, null
        );

        DataGenerationContext ctx = DataGenerationContext.root(GenerationSeed.from(77L), emptyContract());
        String val = (String) generator.generate(boundedStr, ctx);

        assertThat(val.length()).isBetween(5, 8);
    }

    @Test
    @DisplayName("7. Explicit examples and default values override generated values")
    void testExampleAndDefaultPriority() {
        NormalizedSchema withExample = new NormalizedSchema(
                "string", "email", "Email", false, "default@test.com", "explicit_example@test.com",
                null, null, null, null, null
        );
        NormalizedSchema withDefaultOnly = new NormalizedSchema(
                "string", "email", "Email", false, "default_only@test.com", null,
                null, null, null, null, null
        );

        DataGenerationContext ctx = DataGenerationContext.root(GenerationSeed.from(42L), emptyContract());

        assertThat(generator.generate(withExample, ctx)).isEqualTo("explicit_example@test.com");
        assertThat(generator.generate(withDefaultOnly, ctx)).isEqualTo("default_only@test.com");
    }

    @Test
    @DisplayName("8. Nested objects and arrays are recursively generated")
    @SuppressWarnings("unchecked")
    void testNestedObjectsAndArrays() {
        NormalizedSchema addressSchema = NormalizedSchema.object(
                Map.of(
                        "city", NormalizedSchema.string(null, "City"),
                        "country", NormalizedSchema.string(null, "Country")
                ),
                List.of("city"),
                "Address"
        );

        NormalizedSchema userSchema = NormalizedSchema.object(
                Map.of(
                        "name", NormalizedSchema.string(null, "Name"),
                        "address", addressSchema,
                        "tags", NormalizedSchema.array(NormalizedSchema.string(null, "Tag"), "Tags")
                ),
                List.of("name", "address"),
                "User"
        );

        DataGenerationContext ctx = DataGenerationContext.root(GenerationSeed.from(88L), emptyContract(), 3);
        Map<String, Object> result = (Map<String, Object>) generator.generate(userSchema, ctx);

        assertThat(result).containsKey("name");
        assertThat(result).containsKey("address");
        assertThat(result).containsKey("tags");

        Map<String, Object> addr = (Map<String, Object>) result.get("address");
        assertThat(addr).containsKey("city");
        assertThat(addr).containsKey("country");

        List<Object> tags = (List<Object>) result.get("tags");
        assertThat(tags).hasSize(3);
    }

    @Test
    @DisplayName("9. Collection generation produces stable IDs and configured counts")
    void testGenerateCollection() {
        NormalizedSchema itemSchema = NormalizedSchema.object(
                Map.of(
                        "name", NormalizedSchema.string(null, "Name"),
                        "price", NormalizedSchema.number(null, "Price")
                ),
                List.of("name"),
                "Item"
        );

        List<Map<String, Object>> items = generator.generateCollection(itemSchema, 5, 999L, emptyContract());

        assertThat(items).hasSize(5);
        for (Map<String, Object> item : items) {
            assertThat(item).containsKey("id");
            assertThat(item.get("id")).isNotNull();
            assertThat(item).containsKey("name");
            assertThat(item).containsKey("price");
        }

        // Distinct IDs
        long distinctIds = items.stream().map(m -> m.get("id")).distinct().count();
        assertThat(distinctIds).isEqualTo(5);
    }
}

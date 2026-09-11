package com.mockapilab.modules.runtime.generation.generators;

import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;
import com.mockapilab.modules.runtime.generation.DataGenerationContext;
import com.mockapilab.modules.runtime.generation.GenerationSeed;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Generator for realistic and deterministic string values according to formats, property names, and constraints.
 */
@Component
public class StringValueGenerator {

    private static final List<String> FIRST_NAMES = List.of(
            "Aarav", "Aditi", "Ananya", "Dev", "Diya", "Ishaan", "Kavya", "Neha",
            "Priya", "Rahul", "Riya", "Rohan", "Sanjay", "Tanvi", "Vikram",
            "James", "Emma", "Liam", "Olivia", "Noah", "Sophia", "Lucas", "Ava"
    );

    private static final List<String> LAST_NAMES = List.of(
            "Sharma", "Patel", "Verma", "Rao", "Gupta", "Mehta", "Nair", "Reddy",
            "Singh", "Joshi", "Smith", "Johnson", "Williams", "Brown", "Jones", "Miller", "Davis"
    );

    private static final List<String> CITIES = List.of(
            "Bengaluru", "Mumbai", "Delhi", "Hyderabad", "Pune", "Chennai",
            "Kolkata", "Ahmedabad", "San Francisco", "London", "Tokyo", "Berlin", "Singapore", "Sydney"
    );

    private static final List<String> COUNTRIES = List.of(
            "India", "United States", "United Kingdom", "Germany", "Japan",
            "Singapore", "Australia", "Canada", "France", "Netherlands"
    );

    private static final List<String> COMPANIES = List.of(
            "TechCorp", "Nova Systems", "Apex Digital", "InnoWave Solutions",
            "Starlight Analytics", "Quantum Labs", "Horizon Technologies", "Omni Enterprises", "Vertex Dynamics"
    );

    private static final List<String> EMAIL_DOMAINS = List.of(
            "example.com", "mail.com", "devmail.io", "techcorp.org", "innowave.net"
    );

    private static final List<String> STREET_NAMES = List.of(
            "MG Road", "Indiranagar 100ft Rd", "Brigade Rd", "Park Street", "Outer Ring Rd",
            "Cyber City Blvd", "Oak Avenue", "Broadway", "Pine Street"
    );

    private static final List<String> ROLES = List.of("ADMIN", "USER", "EDITOR", "VIEWER", "MANAGER");
    private static final List<String> STATUSES = List.of("ACTIVE", "PENDING", "COMPLETED", "IN_PROGRESS", "APPROVED");

    public String generate(NormalizedSchema schema, DataGenerationContext context) {
        GenerationSeed seed = context.getSeed();
        String format = schema.format() != null ? schema.format().toLowerCase() : "";
        String prop = context.getPropertyName() != null ? context.getPropertyName().toLowerCase() : "";

        String result;

        // 1. Format-based generation
        if ("uuid".equals(format) || prop.endsWith("id") || prop.equals("id")) {
            result = seed.nextUuid().toString();
        } else if ("email".equals(format) || prop.contains("email")) {
            result = generateEmail(seed);
        } else if ("uri".equals(format) || "url".equals(format) || prop.contains("url") || prop.contains("uri")) {
            result = generateUrl(seed);
        } else if ("ipv4".equals(format) || prop.contains("ipaddress") || prop.equals("ip")) {
            result = "192.168." + seed.nextInt(1, 254) + "." + seed.nextInt(1, 254);
        }
        // 2. Property-name heuristic generation
        else if (prop.contains("firstname") || prop.equals("first")) {
            result = seed.choose(FIRST_NAMES);
        } else if (prop.contains("lastname") || prop.equals("last")) {
            result = seed.choose(LAST_NAMES);
        } else if (prop.equals("name") || prop.contains("fullname") || prop.contains("user_name") || prop.contains("username")) {
            result = seed.choose(FIRST_NAMES) + " " + seed.choose(LAST_NAMES);
        } else if (prop.contains("phone") || prop.contains("mobile") || prop.contains("contact")) {
            result = generatePhone(seed, "IN");
        } else if (prop.contains("company") || prop.contains("organization") || prop.contains("org")) {
            result = seed.choose(COMPANIES);
        } else if (prop.contains("city")) {
            result = seed.choose(CITIES);
        } else if (prop.contains("country")) {
            result = seed.choose(COUNTRIES);
        } else if (prop.contains("street") || prop.contains("address")) {
            result = seed.nextInt(1, 999) + ", " + seed.choose(STREET_NAMES);
        } else if (prop.contains("zip") || prop.contains("postal") || prop.contains("pincode")) {
            result = String.valueOf(seed.nextInt(560001, 560099));
        } else if (prop.contains("role")) {
            result = seed.choose(ROLES);
        } else if (prop.contains("status")) {
            result = seed.choose(STATUSES);
        } else if (prop.contains("tag") || prop.contains("category")) {
            result = "tag-" + seed.nextInt(1, 10);
        } else if (prop.contains("title")) {
            result = "Sample " + capitalize(context.getPropertyName());
        } else if (prop.contains("desc")) {
            result = "Realistic description for " + (context.getPropertyName() != null ? context.getPropertyName() : "item") + ".";
        } else {
            result = (context.getPropertyName() != null ? context.getPropertyName() : "value") + "_" + seed.nextInt(100, 999);
        }

        // 3. Apply minLength / maxLength constraints if defined
        return applyLengthConstraints(result, schema.minLength(), schema.maxLength());
    }

    public String generateEmail(GenerationSeed seed) {
        String first = seed.choose(FIRST_NAMES).toLowerCase();
        String last = seed.choose(LAST_NAMES).toLowerCase();
        String domain = seed.choose(EMAIL_DOMAINS);
        return first + "." + last + "@" + domain;
    }

    public String generateUrl(GenerationSeed seed) {
        String company = seed.choose(COMPANIES).replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        String domain = seed.choose(EMAIL_DOMAINS);
        return "https://" + company + "." + domain + "/api/v1";
    }

    /**
     * Extensible locale-aware phone number generator.
     * Default locale is 'IN' (+91-98XXXXXXXX).
     */
    public String generatePhone(GenerationSeed seed, String locale) {
        if ("IN".equalsIgnoreCase(locale) || locale == null) {
            int prefix = seed.nextInt(70, 99);
            int mid = seed.nextInt(1000, 9999);
            int last = seed.nextInt(1000, 9999);
            return "+91-" + prefix + mid + last;
        } else if ("US".equalsIgnoreCase(locale)) {
            int area = seed.nextInt(200, 999);
            int mid = seed.nextInt(200, 999);
            int last = seed.nextInt(1000, 9999);
            return "+1-" + area + "-" + mid + "-" + last;
        } else if ("UK".equalsIgnoreCase(locale)) {
            int part = seed.nextInt(100000000, 999999999);
            return "+44-7" + part;
        }
        return "+91-98" + seed.nextInt(10000000, 99999999);
    }

    private String applyLengthConstraints(String str, Integer minLength, Integer maxLength) {
        if (maxLength != null && str.length() > maxLength) {
            str = str.substring(0, maxLength);
        }
        if (minLength != null && str.length() < minLength) {
            StringBuilder sb = new StringBuilder(str);
            while (sb.length() < minLength) {
                sb.append("_");
            }
            str = sb.toString();
        }
        return str;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}

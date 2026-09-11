package com.mockapilab.modules.runtime.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of incoming mock request schema validation.
 */
public record ValidationResult(
        boolean valid,
        List<String> errors
) {
    public static ValidationResult ok() {
        return new ValidationResult(true, Collections.emptyList());
    }

    public static ValidationResult fail(List<String> errors) {
        return new ValidationResult(false, errors != null ? errors : Collections.emptyList());
    }

    public static ValidationResult fail(String error) {
        List<String> list = new ArrayList<>();
        list.add(error);
        return new ValidationResult(false, list);
    }
}
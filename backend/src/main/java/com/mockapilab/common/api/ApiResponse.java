package com.mockapilab.common.api;

import java.time.Instant;

/**
 * Standard API response envelope for MockAPILab.
 *
 * @param success   Indicates if the request was successful
 * @param message   Human-readable summary or status message
 * @param data      Payload data
 * @param timestamp ISO timestamp when response was generated
 * @param <T>       Data payload type
 */
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        Instant timestamp
) {
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, Instant.now());
    }

    public static <T> ApiResponse<T> error(String message, T data) {
        return new ApiResponse<>(false, message, data, Instant.now());
    }
}

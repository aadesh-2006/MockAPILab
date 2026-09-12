package com.mockapilab.modules.contract.drift.model;

/**
 * Detailed categories of structural and semantic contract changes.
 */
public enum DriftChangeType {
    // Endpoints & Operations
    ENDPOINT_ADDED,
    ENDPOINT_REMOVED,
    METHOD_REMOVED,

    // Parameters
    PARAMETER_ADDED,
    PARAMETER_REMOVED,
    PARAMETER_TYPE_CHANGED,
    PARAMETER_REQUIRED_CHANGED,
    PARAMETER_LOCATION_CHANGED,
    PARAMETER_ENUM_CHANGED,

    // Request Bodies
    REQUEST_BODY_ADDED,
    REQUEST_BODY_REMOVED,
    REQUEST_SCHEMA_CHANGED,

    // Responses
    RESPONSE_STATUS_ADDED,
    RESPONSE_STATUS_REMOVED,
    RESPONSE_SCHEMA_CHANGED,

    // Properties (Nested Object Schemas)
    PROPERTY_ADDED,
    PROPERTY_REMOVED,
    PROPERTY_TYPE_CHANGED,
    PROPERTY_REQUIRED_CHANGED,

    // Enums & Constraints
    ENUM_VALUE_ADDED,
    ENUM_VALUE_REMOVED,
    CONSTRAINT_CHANGED,

    // Metadata & Descriptions
    METADATA_CHANGED
}

package com.mockapilab.modules.scenario.model;

/**
 * Action type to be performed when a scenario rule matches an incoming mock request.
 */
public enum ScenarioAction {
    FORCE_STATUS,
    DELAY,
    RANDOM_FAILURE
}

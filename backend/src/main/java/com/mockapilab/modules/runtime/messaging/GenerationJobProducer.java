package com.mockapilab.modules.runtime.messaging;

import com.mockapilab.modules.runtime.dto.GenerationJobEvent;

/**
 * Producer interface for publishing generation job requests.
 */
public interface GenerationJobProducer {
    void sendJob(GenerationJobEvent event);
}

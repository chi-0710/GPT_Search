package io.researchrunner.config;

import io.researchrunner.research.InterruptedTaskRecovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/** Enable only after stopping every old writer; initialization precedes request acceptance. */
@Configuration
@ConditionalOnProperty(name={"researchrunner.writes-enabled","JAVA_RECOVER_INTERRUPTED_TASKS"},havingValue="true")
public class RecoveryConfiguration {
    public RecoveryConfiguration(InterruptedTaskRecovery recovery){recovery.recover();}
}

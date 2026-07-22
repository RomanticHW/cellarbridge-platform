package com.rom.cellarbridge.platform.internal;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/** Adds safe repository-level spans without recording SQL text, bind values or result rows. */
@Aspect
@Component
final class DatabaseObservationAspect {

  private final ObservationRegistry observations;

  DatabaseObservationAspect(ObservationRegistry observations) {
    this.observations = observations;
  }

  @Around("@within(org.springframework.stereotype.Repository)")
  Object observeRepositoryCall(ProceedingJoinPoint invocation) throws Throwable {
    Observation observation =
        Observation.start("cellarbridge.database", observations)
            .lowCardinalityKeyValue(
                "repository", ClassUtils.getUserClass(invocation.getTarget()).getSimpleName())
            .lowCardinalityKeyValue("operation", invocation.getSignature().getName());
    try (Observation.Scope ignored = observation.openScope()) {
      return invocation.proceed();
    } catch (Throwable failure) {
      observation.error(failure);
      throw failure;
    } finally {
      observation.stop();
    }
  }
}

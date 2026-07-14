package com.rom.cellarbridge.platform.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(
    prefix = "cellarbridge.platform.local-events",
    name = "enabled",
    havingValue = "true")
class LocalEventSchedulingConfiguration {}

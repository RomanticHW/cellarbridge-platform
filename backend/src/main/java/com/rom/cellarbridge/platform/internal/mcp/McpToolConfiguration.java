package com.rom.cellarbridge.platform.internal.mcp;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class McpToolConfiguration {

  @Bean
  List<SyncToolSpecification> cellarbridgeMcpToolSpecifications(
      ObjectProvider<SyncToolSpecification> specifications) {
    return specifications.orderedStream().toList();
  }
}

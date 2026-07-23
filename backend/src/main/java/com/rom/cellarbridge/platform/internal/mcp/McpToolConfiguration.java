package com.rom.cellarbridge.platform.internal.mcp;

import com.rom.cellarbridge.platform.mcp.McpResponseProperties;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpResponseProperties.class)
class McpToolConfiguration {

  @Bean
  List<SyncToolSpecification> cellarbridgeMcpToolSpecifications(
      ObjectProvider<SyncToolSpecification> specifications) {
    return specifications.orderedStream().toList();
  }
}

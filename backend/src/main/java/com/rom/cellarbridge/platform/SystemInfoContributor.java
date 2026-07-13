package com.rom.cellarbridge.platform;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class SystemInfoContributor implements InfoContributor {

  private final Optional<BuildProperties> buildProperties;
  private final Environment environment;
  private final String commit;

  SystemInfoContributor(
      Optional<BuildProperties> buildProperties,
      Environment environment,
      @Value("${cellarbridge.build.commit:local}") String commit) {
    this.buildProperties = buildProperties;
    this.environment = environment;
    this.commit = commit;
  }

  @Override
  public void contribute(Info.Builder builder) {
    String version = buildProperties.map(BuildProperties::getVersion).orElse("development");
    String profile = Arrays.stream(environment.getActiveProfiles()).findFirst().orElse("default");
    builder.withDetail("cellarbridge", new BuildSummary(version, commit, profile));
  }

  record BuildSummary(String version, String commit, String profile) {}
}

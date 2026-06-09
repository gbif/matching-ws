package life.catalogue.matching.config;

import life.catalogue.matching.model.APIMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties to override APIMetadata values from application.yaml
 * Use keys under 'api.metadata'
 */
@Component
@ConfigurationProperties(prefix = "api.metadata")
public class APIMetadataConfig extends APIMetadata {
}


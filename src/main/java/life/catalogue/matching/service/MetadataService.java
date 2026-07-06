package life.catalogue.matching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import life.catalogue.matching.index.DatasetIndex;
import life.catalogue.matching.model.APIMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.util.Optional;

@Slf4j
@Service
public class MetadataService {

    @Value("${working.dir:/tmp/}")
    protected String metadataFilePath;

    @Autowired(required = false)
    private APIMetadata apiMetadataOverrides;

    DatasetIndex datasetIndex;

    public MetadataService(DatasetIndex datasetIndex) {
        this.datasetIndex = datasetIndex;
    }

    /**
     * Get the metadata for the API and index.
     *
     * @return the metadata or empty if it could not be read or generated
     */
    public Optional<APIMetadata> getAPIMetadata(boolean regenerate) {

        // read JSON from file, if not available generate from datasetIndex
        if (!datasetIndex.getIsInitialised()) {
            return Optional.empty();
        }

        File metadata = new File(metadataFilePath + "/index-metadata.json");
        try {
            if (regenerate || !metadata.exists()) {
                APIMetadata metadata1 = datasetIndex.getAPIMetadata();
                // apply any configured overrides from application.yaml
                applyAPIMetadataOverrides(metadata1);
                // serialise to file
                ObjectMapper mapper = new ObjectMapper();
                FileWriter writer = new FileWriter(metadata);
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                mapper.writeValue(writer, metadata1);
                return Optional.of(metadata1);
            } else {
                // read from file
                ObjectMapper mapper = new ObjectMapper();
                APIMetadata m = mapper.readValue(metadata, APIMetadata.class);
                applyAPIMetadataOverrides(m);
                return Optional.of(m);
            }
        } catch (Exception e) {
            log.error("Failed to read index metadata from {}", metadata, e);
        }
        return Optional.empty();
    }

    /**
     * Applies non-null values from the configured APIMetadata (application.yaml) to the generated one.
     */
    private void applyAPIMetadataOverrides(APIMetadata metadata) {
        if (apiMetadataOverrides == null || metadata == null) return;

        if (apiMetadataOverrides.getCreated() != null) {
            metadata.setCreated(apiMetadataOverrides.getCreated());
        }
        if (apiMetadataOverrides.getBuildInfo() != null) {
            metadata.setBuildInfo(apiMetadataOverrides.getBuildInfo());
        }
        if (apiMetadataOverrides.getMainIndex() != null) {
            metadata.setMainIndex(apiMetadataOverrides.getMainIndex());
        }
        if (apiMetadataOverrides.getIdentifierIndexes() != null && !apiMetadataOverrides.getIdentifierIndexes().isEmpty()) {
            metadata.setIdentifierIndexes(apiMetadataOverrides.getIdentifierIndexes());
        }
        if (apiMetadataOverrides.getAncillaryIndexes() != null && !apiMetadataOverrides.getAncillaryIndexes().isEmpty()) {
            metadata.setAncillaryIndexes(apiMetadataOverrides.getAncillaryIndexes());
        }
    }
}

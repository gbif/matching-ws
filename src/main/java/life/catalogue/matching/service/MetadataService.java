package life.catalogue.matching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import life.catalogue.matching.index.DatasetIndex;
import life.catalogue.matching.model.APIMetadata;
import life.catalogue.matching.model.IndexMetadata;
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

    private void applyAPIMetadataOverrides(APIMetadata metadata) {
        applyOverrides(metadata, apiMetadataOverrides);
    }

    /**
     * Applies the values configured in application.yaml under api.metadata to the metadata generated from the index.
     * The main index is merged field by field, so a config can change single values like the clbDatasetKey,
     * e.g. to point to a copy of the indexed dataset in a non-prod ChecklistBank, and keeps all others from the index.
     */
    static void applyOverrides(APIMetadata metadata, APIMetadata overrides) {
        if (overrides == null || metadata == null) return;

        if (overrides.getCreated() != null) {
            metadata.setCreated(overrides.getCreated());
        }
        if (overrides.getBuildInfo() != null) {
            metadata.setBuildInfo(overrides.getBuildInfo());
        }
        if (overrides.getMainIndex() != null) {
            if (metadata.getMainIndex() == null) {
                metadata.setMainIndex(overrides.getMainIndex());
            } else {
                merge(metadata.getMainIndex(), overrides.getMainIndex());
            }
        }
        if (overrides.getIdentifierIndexes() != null && !overrides.getIdentifierIndexes().isEmpty()) {
            metadata.setIdentifierIndexes(overrides.getIdentifierIndexes());
        }
        if (overrides.getAncillaryIndexes() != null && !overrides.getAncillaryIndexes().isEmpty()) {
            metadata.setAncillaryIndexes(overrides.getAncillaryIndexes());
        }
    }

    /**
     * Copies the configured values of an index onto the generated one.
     * The counts default to 0 and an empty map, so only positive counts and non-empty maps count as configured.
     */
    private static void merge(IndexMetadata index, IndexMetadata override) {
        if (override.getClbDatasetKey() != null) {
            index.setClbDatasetKey(override.getClbDatasetKey());
        }
        if (override.getDatasetKey() != null) {
            index.setDatasetKey(override.getDatasetKey());
        }
        if (override.getDatasetAlias() != null) {
            index.setDatasetAlias(override.getDatasetAlias());
        }
        if (override.getDatasetTitle() != null) {
            index.setDatasetTitle(override.getDatasetTitle());
        }
        if (isPositive(override.getSizeInMB())) {
            index.setSizeInMB(override.getSizeInMB());
        }
        if (isPositive(override.getNameUsageCount())) {
            index.setNameUsageCount(override.getNameUsageCount());
        }
        if (override.getMatchesToMain() != null) {
            index.setMatchesToMain(override.getMatchesToMain());
        }
        if (override.getNameUsageByRankCount() != null && !override.getNameUsageByRankCount().isEmpty()) {
            index.setNameUsageByRankCount(override.getNameUsageByRankCount());
        }
    }

    private static boolean isPositive(Long x) {
        return x != null && x > 0;
    }
}

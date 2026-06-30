package life.catalogue.matching.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.parameters.Parameter;
import life.catalogue.matching.index.DatasetIndex;
import life.catalogue.matching.model.APIMetadata;
import life.catalogue.matching.model.Dataset;
import lombok.extern.slf4j.Slf4j;
import org.gbif.dwc.terms.DwcTerm;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Map;

import static life.catalogue.matching.controller.MatchController.V2_SPECIES_MATCH;

/**
 * Configuration properties to override APIMetadata values from application.yaml
 * Use keys under 'api.metadata'
 */
@Component
@ConfigurationProperties(prefix = "api.metadata")
@Slf4j
public class APIMetadataConfig extends APIMetadata {

    public static final List<String> TAXON_ID_FIELDS =
            List.of(DwcTerm.taxonID.simpleName(),
                    DwcTerm.taxonConceptID.simpleName(),
                    DwcTerm.scientificNameID.simpleName());

    private final String identifiersBlock;

    public APIMetadataConfig(@Value("${working.dir:/tmp/}") String metadataFilePath ) {

        StringBuffer sb = new StringBuffer();
        try {
            File metadata = new File(metadataFilePath + "/index-metadata.json");
            ObjectMapper mapper = new ObjectMapper();
            APIMetadata apiMetadata = mapper.readValue(metadata, APIMetadata.class);

            // load the
            Map<String, Dataset> identifiers = DatasetIndex.loadPrefixMapping();
            sb.append("<ul>");
            apiMetadata.getIdentifierIndexes().forEach(index -> {
                Dataset dataset = identifiers.get(index.getDatasetKey());
                sb.append("<li>");

                // link the dataset title to the GBIF dataset page using the datasetKey
                String dsKey = index.getDatasetKey();
                String title = index.getDatasetTitle() == null ? (dataset == null ? "(unknown)" : dataset.getTitle()) : index.getDatasetTitle();
                if (dsKey != null && !dsKey.isEmpty()) {
                    sb.append("<a href=\"https://www.gbif.org/dataset/").append(dsKey).append("\" target=\"_blank\">");
                    sb.append(title);
                    sb.append("</a>");
                } else {
                    sb.append(title);
                }

                // add a sublist of example usages from the Dataset, if present
                if (dataset != null && dataset.getExamples() != null && !dataset.getExamples().isEmpty()) {
                    sb.append(" - Example identifiers:");
                    sb.append("<ul>");
                    dataset.getExamples().forEach(ex -> sb.append("<li>").append(ex).append("</li>"));
                    sb.append("</ul>");
                }

                sb.append("</li>");
            });
            sb.append("</ul>");
        } catch (Exception e) {
            log.error("Failed to read metadata file from " + metadataFilePath, e);
        }
        this.identifiersBlock = sb.toString();
    }

    @Bean
    public OpenApiCustomiser addIdMatchingDescription() {

        return openApi -> {
            if (openApi.getPaths() == null) return;
            var path = openApi.getPaths().get("/" + V2_SPECIES_MATCH);
            if (path == null) return;
            var getOp = path.getGet();
            if (getOp == null) return;
            var params = getOp.getParameters();
            if (params == null) return;
            for (Parameter p : params) {
                if (p.getName() != null && TAXON_ID_FIELDS.contains(p.getName())) {
                    p.setDescription((p.getDescription() == null ? "" : p.getDescription())
                            + " <br/>Sources of recognised identifiers include: " + identifiersBlock);
                }
            }
        };
    }
}


package life.catalogue.matching.controller;

import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import life.catalogue.matching.model.IndexType;
import life.catalogue.matching.service.ExportService;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Export services, providing downloads of the content of the indexes as zipped CSV files.
 * The archives are streamed to the client as they are generated, see {@link ExportService}.
 */
@RestController
@Tag(name = "Export services", description = "Downloads of the content of the indexes as zipped CSV files")
@ApiResponse(
  responseCode = "200",
  description = "A zip archive of CSV files. The first row of each CSV file is a header.",
  content = @Content(mediaType = ExportController.APPLICATION_ZIP, schema = @Schema(type = "string", format = "binary")))
@ApiResponse(responseCode = "404", description = "There is no index of this type, or none for the requested dataset", content = @Content)
@ApiResponse(responseCode = "503", description = "The indexes are not loaded", content = @Content)
public class ExportController {

  public static final String APPLICATION_ZIP = "application/zip";
  public static final String V2_SPECIES_MATCH_EXPORT = "v2/species/match/export";

  private static final String DATASET_KEY_DESCRIPTION =
    "Restricts the export to the index of a single dataset, identified by its ChecklistBank dataset key " +
      "or its GBIF dataset key. The keys of the available indexes are listed by the metadata service. " +
      "If omitted, all indexes of this type are exported.";

  private final ExportService exportService;

  public ExportController(ExportService exportService) {
    this.exportService = exportService;
  }

  @Operation(
    operationId = "exportMainIndex",
    summary = "Export the main index",
    description =
      "Downloads a zip archive containing a single CSV file, `main.csv`, with one row for each name usage " +
        "in the main taxonomy index. Each row includes the name, rank, taxonomic status, the identifiers of " +
        "the parent and accepted usages, the nested set indexes and the names and identifiers of the " +
        "higher classification (kingdom to species). The archive is generated on request and streamed, " +
        "so may take some time to download for a large checklist.")

  @GetMapping(value = V2_SPECIES_MATCH_EXPORT + "/main", produces = APPLICATION_ZIP)
  public void exportMain(HttpServletResponse response) throws IOException {
    export(IndexType.MAIN, null, response);
  }

  @Operation(
    operationId = "exportIdentifierIndexes",
    summary = "Export the identifier indexes",
    description =
      "Downloads a zip archive containing a CSV file for each identifier index, named " +
        "`identifier-{ChecklistBank dataset key}.csv`. Each row is a name usage from another checklist " +
        "(e.g. WoRMS) whose identifier is recognised by the matching service, with the `mainIndexId` column " +
        "giving the identifier of the name usage in the main index that it has been joined to.")
  @Parameters(
          value = {
                  @Parameter(
                          name = "datasetKey",
                          description = "The checklistbank dataset key of the checklist to match against.",
                          in = ParameterIn.QUERY, schema = @Schema(implementation = String.class))
          }
  )
  @GetMapping(value = V2_SPECIES_MATCH_EXPORT + "/identifiers", produces = APPLICATION_ZIP)
  public void exportIdentifiers(
    @Parameter(description = DATASET_KEY_DESCRIPTION)
    @RequestParam(value = "datasetKey", required = false) String datasetKey,
    HttpServletResponse response) throws IOException {
    export(IndexType.IDENTIFIER, datasetKey, response);
  }

  @Operation(
    operationId = "exportAncillaryIndexes",
    summary = "Export the ancillary indexes",
    description =
      "Downloads a zip archive containing a CSV file for each ancillary index, named " +
        "`ancillary-{ChecklistBank dataset key}.csv`. Each row is a name usage from a checklist providing " +
        "additional information (e.g. the IUCN Red List), with the `category` column giving the status " +
        "assigned by that checklist and the `mainIndexId` column giving the identifier of the name usage " +
        "in the main index that it has been joined to.")
  @Parameters(
          value = {
                  @Parameter(
                          name = "datasetKey",
                          description = "The checklistbank dataset key of the checklist to match against.",
                          in = ParameterIn.QUERY, schema = @Schema(implementation = String.class))
          }
  )
  @GetMapping(value = V2_SPECIES_MATCH_EXPORT + "/ancillary", produces = APPLICATION_ZIP)
  public void exportAncillary(
    @Parameter(description = DATASET_KEY_DESCRIPTION)
    @RequestParam(value = "datasetKey", required = false) String datasetKey,
    HttpServletResponse response) throws IOException {
    export(IndexType.ANCILLARY, datasetKey, response);
  }

  /**
   * Streams the export straight onto the response. The export is resolved before the response is
   * touched, so that failing to find an index still results in a proper error response.
   */
  private void export(IndexType type, String datasetKey, HttpServletResponse response) throws IOException {
    ExportService.ZipExport export = exportService.export(type, datasetKey);
    response.setContentType(APPLICATION_ZIP);
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
      ContentDisposition.attachment().filename(export.fileName()).build().toString());
    export.writeTo(response.getOutputStream());
  }
}

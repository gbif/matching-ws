package life.catalogue.matching.service;

import life.catalogue.api.exception.NotFoundException;
import life.catalogue.api.model.ReleaseAttempt;
import life.catalogue.api.vocab.DatasetOrigin;
import life.catalogue.api.vocab.MatchType;
import life.catalogue.api.vocab.TaxonomicStatus;
import life.catalogue.matching.db.DatasetMapper;
import life.catalogue.matching.index.ScientificNameAnalyzer;
import life.catalogue.matching.model.*;
import life.catalogue.matching.util.IOUtil;
import life.catalogue.matching.util.NameParsers;

import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.ParsedName;
import org.gbif.nameparser.api.Rank;
import org.gbif.nameparser.util.NameFormatter;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import jakarta.validation.constraints.NotNull;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriterBuilder;
import com.opencsv.ICSVWriter;
import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

import static life.catalogue.matching.util.IndexConstants.*;

/**
 * Service to index a dataset from the Checklist Bank.
 * /data/matching-ws/export/             - CSV exports from the Checklist Bank
 * /data/matching-ws/index/main          - Main lucene index
 * /data/matching-ws/index/identifiers   - Lucene indexes for IDs lookups
 * /data/matching-ws/index/ancillary     - Lucene indexes for status values e.g. IUCN
 */
@Service
@Slf4j
public class IndexingService {

  @Value("${index.path:/tmp/matching-index}")
  String indexPath;

  @Value("${export.path:/tmp/matching-export}")
  String exportPath;

  @Value("${clb.url:jdbc:postgresql://localhost:5432/clb}")
  String clbUrl;

  @Value("${clb.user:clb}")
  String clbUser;

  @Value("${clb.password:not-set}")
  String clPassword;

  @Value("${indexing.threads:6}")
  Integer indexingThreads;

  @Value("${indexing.batchsize:10000}")
  Integer indexingBatchSize;

  @Value("${indexing.fetchsize:50000}")
  Integer dbFetchSize;

  protected final IOUtil ioUtil;

  protected final MatchingService matchingService;

  private static final String REL_PATTERN_STR = "(\\d+)(?:LX?RC?|R(\\d+))";
  private static final Pattern REL_PATTERN = Pattern.compile("^" + REL_PATTERN_STR + "$");
  private static final Pattern SLASHES = Pattern.compile("[/\\\\]");

  protected static final ScientificNameAnalyzer scientificNameAnalyzer = new ScientificNameAnalyzer();

  public IndexingService(MatchingService matchingService, IOUtil ioUtil) {
    this.matchingService = matchingService;
    this.ioUtil = ioUtil;
  }

  protected static IndexWriterConfig getIndexWriterConfig() {
    Map<String, Analyzer> analyzerPerField = new HashMap<>();
    analyzerPerField.put(FIELD_SCIENTIFIC_NAME, new StandardAnalyzer());
    analyzerPerField.put(FIELD_CANONICAL_NAME, scientificNameAnalyzer);
    PerFieldAnalyzerWrapper aWrapper = new PerFieldAnalyzerWrapper( new KeywordAnalyzer(), analyzerPerField);
    IndexWriterConfig config = new IndexWriterConfig(aWrapper);
    config.setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    config.setRAMBufferSizeMB(256.0);
    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
    return config;
  }

  /**
   * Looks up a dataset by its key with support for release keys
   * @param factory MyBatis session factory
   * @param datasetKeyInput a dataset key or a release key
   * @return the dataset or empty if not found
   */
  private Optional<Dataset> lookupDataset(SqlSessionFactory factory, String datasetKeyInput, String gbifDatasetKeyInput) {

    // resolve the magic keys...
    Optional<Integer> datasetKey = Optional.empty();
    try {
      datasetKey = Optional.of(Integer.parseInt(datasetKeyInput));
      Optional<Dataset> dataset = lookupDataset(factory, datasetKey.get());
      // override the datasetkey if supplied
      if (dataset.isPresent() && StringUtils.isNotBlank(gbifDatasetKeyInput)) {
        dataset.get().setDatasetKey(gbifDatasetKeyInput);
      }
      return dataset;
    } catch (NumberFormatException ignored) {
    }

    // otherwise, resolve magic key
    if (datasetKey.isEmpty()) {
      Matcher m = REL_PATTERN.matcher(datasetKeyInput);
      if (m.find()){
        Integer releaseDatasetKey = releaseKeyFromMatch(factory, m);

        Optional<Dataset> dataset = lookupDataset(factory,releaseDatasetKey);
        // override the datasetkey if supplied
        if (dataset.isPresent() && StringUtils.isNotBlank(gbifDatasetKeyInput)) {
          dataset.get().setDatasetKey(gbifDatasetKeyInput);
        }
        return dataset;
      }
    }
    return Optional.empty();
  }

  private Integer releaseKeyFromMatch(SqlSessionFactory factory,  Matcher m) {
    // parsing cannot fail, we have a pattern
    int projectKey = Integer.parseInt(m.group(1));
    Integer releaseKey;
    // candidate requested? (\\d+)(?:LX?RC?|R(\\d+))$
    final boolean extended = m.group().contains("X");
    if (m.group().endsWith("RC")) {

      releaseKey = lookupLatest(factory, projectKey, true, extended);
    } else if (m.group().endsWith("R")) {

      releaseKey = lookupLatest(factory, projectKey, false, extended);

    } else {
      // parsing cannot fail, we have a pattern
      int attempt = Integer.parseInt(m.group(2));
      releaseKey = lookupAttempt(factory, new ReleaseAttempt(projectKey, attempt));
    }

    if (releaseKey == null) {
      throw new NotFoundException("Dataset " + projectKey + " was never released");
    }
    return releaseKey;
  }

  /**
   * @param projectKey a dataset key that is known to exist and point to a managed dataset
   * @return dataset key for the latest release of a project or null in case no release exists
   */
  private Integer lookupLatest(SqlSessionFactory factory, int projectKey, boolean candidate, boolean extended) throws NotFoundException {
    try (SqlSession session = factory.openSession()) {
      DatasetMapper dm = session.getMapper(DatasetMapper.class);
      DatasetOrigin origin = extended ? DatasetOrigin.XRELEASE : DatasetOrigin.RELEASE;
      return dm.latestRelease(projectKey, !candidate, origin);
    }
  }

  private Integer lookupAttempt(SqlSessionFactory factory,  ReleaseAttempt release) throws NotFoundException {
    try (SqlSession session = factory.openSession()) {
      DatasetMapper dm = session.getMapper(DatasetMapper.class);
      return dm.releaseAttempt(release.projectKey, release.attempt);
    }
  }

  private Optional<Dataset> lookupDataset(SqlSessionFactory factory, Integer key) throws NotFoundException {
    try (SqlSession session = factory.openSession()) {
      DatasetMapper dm = session.getMapper(DatasetMapper.class);
      return dm.getDataset(key);
    }
  }

  /**
   * Writes an export of the name usages in a checklist bank dataset to a CSV file.
   *
   * @param datasetKeyInput a dataset key or a release key
   * @throws Exception if the dataset key is invalid or the export fails
   */
  @Transactional
  public void writeCLBToFile(@NotNull final String datasetKeyInput) throws Exception {
    writeCLBToFile(datasetKeyInput, null);
  }

  /**
   * Writes an export of the name usages in a checklist bank dataset to a CSV file.
   *
   * @param datasetKeyInput a dataset key or a release key
   * @throws Exception if the dataset key is invalid or the export fails
   */
  @Transactional
  public void writeCLBToFile(@NotNull final String datasetKeyInput, final String gbifDatasetKeyInput) throws Exception {

    final String directory = exportPath + "/" + datasetKeyInput;
    final String fileName = directory + "/" + INDEX_CSV;
    Path path = Paths.get(fileName);

    if (Files.exists(path) && Files.size(path) > 0) {
      log.info("File {} already exists, skipping export", fileName);
      return;
    }

    try (HikariDataSource dataSource = getDataSource()) {
      final SqlSessionFactory factory = getSqlSessionFactory(dataSource);
      // resolve the magic keys...
      Optional<Dataset> dataset = lookupDataset(factory, datasetKeyInput, gbifDatasetKeyInput);
      if (dataset.isEmpty()) {
        throw new IllegalArgumentException("Invalid dataset key: " + datasetKeyInput);
      }

      final AtomicInteger counter = new AtomicInteger(0);

      FileUtils.forceMkdir(new File(directory));

      final String metadata = directory + "/" + METADATA_JSON;
      ObjectMapper mapper = new ObjectMapper();
      mapper.writeValue(new File(metadata), dataset.get());

      log.info("Writing dataset to file {}", fileName);
      final String query = "SELECT " +
        "nu.id as id, " +
        "nu.parent_id as parentId, " +
        "n.scientific_name as scientificName, " +
        "n.authorship as authorship, " +
        "n.rank as rank, " +
        "nu.status as status, " +
        "n.code as code, " +
        "n.type as type, " +
        "n.genus as genericName, " +
        "n.infrageneric_epithet as infragenericEpithet, " +
        "n.specific_epithet as specificEpithet, " +
        "n.infraspecific_epithet as infraspecificEpithet, " +
        "'' as extension, " +
        "'' as category, " +
        "array_to_string(nu.environments, ',') as environments " +
        "FROM name_usage nu " +
        "INNER JOIN " +
        "name n on n.id = nu.name_id AND n.dataset_key = " + dataset.get().getClbKey() +
        " WHERE " +
        "nu.dataset_key = " + dataset.get().getClbKey();

      try (
        final ICSVWriter writer = new CSVWriterBuilder(new FileWriter(fileName)).withSeparator('$').build();
        Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {

        conn.setAutoCommit(false);
        st.setFetchSize(dbFetchSize);

        StatefulBeanToCsv<NameUsage> sbc = new StatefulBeanToCsvBuilder<NameUsage>(writer)
          .withQuotechar('\'')
          .build();

        try (ResultSet rs = st.executeQuery(query)) {
          while (rs.next()) {

            NameUsage name = NameUsage.builder()
              .id(rs.getString("id"))
              .parentId(rs.getString("parentId"))
              .scientificName(rs.getString("scientificName"))
              .authorship(rs.getString("authorship"))
              .status(rs.getString("status"))
              .rank(rs.getString("rank"))
              .code(rs.getString("code"))
              .type(rs.getString("type"))
              .genericName(rs.getString("genericName"))
              .infragenericEpithet(rs.getString("infragenericEpithet"))
              .specificEpithet(rs.getString("specificEpithet"))
              .infraspecificEpithet(rs.getString("infraspecificEpithet"))
              .category(rs.getString("category"))
              .environments(rs.getString("environments"))
              .extension(rs.getString("extension"))
              .build();

            sbc.write(cleanNameUsage(name));
            counter.incrementAndGet();
          }
        }
      }
      // write metadata file in JSON format
      log.info("ChecklistBank export written to file {}: {}", fileName, counter.get());
    }
  }

  private static SqlSessionFactory getSqlSessionFactory(DataSource dataSource) throws Exception {
    final SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
    sessionFactoryBean.setDataSource(dataSource);
    SqlSessionFactory factory = sessionFactoryBean.getObject();
    assert factory != null;
    factory.getConfiguration().addMapper(DatasetMapper.class);
    return factory;
  }

  private HikariDataSource getDataSource() {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(clbUrl);
    hikariConfig.setUsername(clbUser);
    hikariConfig.setPassword(clPassword);
    return new HikariDataSource(hikariConfig);
  }

  private NameUsage cleanNameUsage(NameUsage name) {
    if (name.getRank() == null) {
      name.setRank(Rank.UNRANKED.toString());
    }
    name.setScientificName(SLASHES.matcher(name.getScientificName()).replaceAll("").trim());
    return name;
  }

  @Transactional
  public void indexIdentifiers(@NotNull String datasetKey) throws Exception {
    if (StringUtils.isBlank(datasetKey)) {
      return;
    }
    final String joinIndexPath = indexPath + "/" + IDENTIFIERS_DIR + "/" + datasetKey;
    if (indexExists(joinIndexPath)){
      log.info("Index for dataset {} available", datasetKey);
      return;
    }

    writeCLBToFile(datasetKey);
    // identifiers must resolve to the very usage they denote, including synonyms
    writeJoinIndex(exportPath + "/" + datasetKey, joinIndexPath, false, false);
  }

  @Transactional
  public void indexIUCN(@NotNull String datasetKey) throws Exception {
    final String joinIndexPath = indexPath + "/" + ANCILLARY_DIR + "/" + datasetKey;
    if (indexExists(joinIndexPath)){
      log.info("Index for dataset {} available", datasetKey);
      return;
    }

    if (StringUtils.isBlank(datasetKey)) {
      return;
    }
    writeCLBIUCNToFile(datasetKey);
    // ancillary status is looked up per accepted taxon, so collapse synonym matches to the accepted usage
    writeJoinIndex(exportPath + "/" + datasetKey, joinIndexPath, true, true);
  }

  private static boolean indexExists(String joinIndexPath) throws IOException {
    Path path = Paths.get(joinIndexPath);
    if (Files.exists(path) && Files.isDirectory(path)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
        // Check if the directory is non-empty by checking if there's at least one entry
        boolean isEmpty = !stream.iterator().hasNext();
        if (isEmpty) {
          return false;
        }
      }
    }
    Path metadataPath = Paths.get(joinIndexPath + "/" + METADATA_JSON);
    return Files.exists(metadataPath) && Files.size(metadataPath) != 0;
  }

  @Transactional
  public void writeCLBIUCNToFile(@NotNull final String datasetKeyInput) throws Exception {

    final String fileName = exportPath + "/" + datasetKeyInput + "/" + INDEX_CSV;
    if (Files.exists(Paths.get(fileName))) {
      log.info("File {} already exists, skipping export", fileName);
      return;
    }

    try (HikariDataSource dataSource = getDataSource()) {
      // Create a session factory
      final SqlSessionFactory factory = getSqlSessionFactory(dataSource);

      // resolve the magic keys...
      Optional<Dataset> dataset = lookupDataset(factory, datasetKeyInput, null);
      if (dataset.isEmpty()) {
        throw new IllegalArgumentException("Invalid dataset key: " + datasetKeyInput);
      }

      final AtomicInteger counter = new AtomicInteger(0);

      final String metadata = exportPath + "/" + datasetKeyInput + "/" + METADATA_JSON;

      FileUtils.forceMkdir(new File(exportPath + "/" + datasetKeyInput));

      log.info("Writing dataset to file {}", fileName);

      // write metadata to file
      ObjectMapper mapper = new ObjectMapper();
      mapper.writeValue(new File(metadata), dataset.get());

      final String query = "SELECT " +
        "nu.id as id, " +
        "nu.parent_id as parentId, " +
        "n.scientific_name as scientificName, " +
        "n.authorship as authorship, " +
        "n.rank as rank, " +
        "nu.status as status, " +
        "n.code as code, " +
        "v.terms as extension, " +
        "'' as category " +
        "FROM " +
        "name_usage nu " +
        " INNER JOIN " +
        "name n on n.id = nu.name_id AND n.dataset_key = " + dataset.get().getClbKey() +
        " LEFT JOIN " +
        "distribution d on d.taxon_id = nu.id AND d.dataset_key = " + dataset.get().getClbKey() +
        " LEFT JOIN " +
        "verbatim v on v.id = d.verbatim_key AND v.dataset_key = " + dataset.get().getClbKey() +
        " WHERE " +
        "nu.dataset_key = " + dataset.get().getClbKey();

      try (
        final ICSVWriter writer = new CSVWriterBuilder(new FileWriter(fileName)).withSeparator('$').build();
        Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {

        conn.setAutoCommit(false);
        st.setFetchSize(dbFetchSize);
        final ObjectMapper objectMapper = new ObjectMapper();

        StatefulBeanToCsv<NameUsage> sbc = new StatefulBeanToCsvBuilder<NameUsage>(writer)
          .withQuotechar('\'')
          .build();

        try (ResultSet rs = st.executeQuery(query)) {
          while (rs.next()) {
            NameUsage nameUsage = NameUsage.builder()
              .id(rs.getString("id"))
              .parentId(rs.getString("parentId"))
              .scientificName(rs.getString("scientificName"))
              .authorship(rs.getString("authorship"))
              .status(rs.getString("status"))
              .rank(rs.getString("rank"))
              .code(rs.getString("code"))
              .category(rs.getString("category"))
              .extension(rs.getString("extension"))
              .build();
            try {
              if (StringUtils.isNotBlank(nameUsage.getExtension())) {
                // parse json to retrieve IUCN threat status
                JsonNode node = objectMapper.readTree(nameUsage.getExtension());
                nameUsage.setCategory(node.path(IUCN_THREAT_STATUS).asText());
              }
              sbc.write(nameUsage);
              counter.incrementAndGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        }
      }
      // write metadata file in JSON format
      log.info("ChecklistBank IUCN export written to file {}: {}", fileName, counter.get());
    }
  }

  /** Reads name usages from a source, in the same order every time it is called. */
  @FunctionalInterface
  interface UsageSource {
    void forEach(Consumer<NameUsage> consumer) throws IOException;
  }

  @FunctionalInterface
  interface BatchTask<R> {
    R run(int offset, List<NameUsage> batch) throws Exception;
  }

  static UsageSource csvSource(String filePath) {
    return consumer -> {
      try (Reader reader = new BufferedReader(new FileReader(filePath), 1 << 16)) {
        @SuppressWarnings("unchecked")
        CsvToBean<NameUsage> csvReader = new CsvToBeanBuilder<NameUsage>(reader)
          .withType(NameUsage.class)
          .withSeparator('$')
          .withMultilineLimit(1)
          .withIgnoreLeadingWhiteSpace(true)
          .withIgnoreEmptyLine(true)
          .build();
        csvReader.iterator().forEachRemaining(consumer);
      }
    };
  }

  @SafeVarargs
  static UsageSource iterableSource(Iterable<NameUsage>... usages) {
    return consumer -> {
      for (var iter : usages) {
        for (NameUsage u : iter) {
          if (u != null && u.getId() != null) {
            consumer.accept(u);
          }
        }
      }
    };
  }

  /**
   * Reads the source on the calling thread and runs the task on batches of it in parallel.
   * Any failing batch fails the whole run.
   *
   * @return the task results in source order
   */
  static <R> List<R> runBatches(UsageSource source, int threads, int batchSize, String label, BatchTask<R> task)
    throws IOException {
    ThreadPoolExecutor exec = new ThreadPoolExecutor(threads, threads,
      0L, TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(threads * 2),
      new ThreadPoolExecutor.CallerRunsPolicy());
    List<Future<R>> futures = new ArrayList<>();
    try {
      final List<NameUsage>[] batch = new List[]{new ArrayList<>(batchSize)};
      final int[] offset = {0};
      source.forEach(u -> {
        batch[0].add(u);
        if (batch[0].size() >= batchSize) {
          futures.add(submit(exec, task, offset[0], batch[0]));
          offset[0] += batch[0].size();
          batch[0] = new ArrayList<>(batchSize);
          if (futures.size() % 10 == 0) {
            log.info("{} - read {} usages", label, offset[0]);
          }
        }
      });
      if (!batch[0].isEmpty()) {
        futures.add(submit(exec, task, offset[0], batch[0]));
      }
      List<R> results = new ArrayList<>(futures.size());
      for (Future<R> f : futures) {
        results.add(f.get());
      }
      return results;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(label + " interrupted", e);
    } catch (ExecutionException e) {
      throw new IOException(label + " failed: " + e.getCause().getMessage(), e.getCause());
    } finally {
      exec.shutdownNow();
    }
  }

  private static <R> Future<R> submit(ExecutorService exec, BatchTask<R> task, int offset, List<NameUsage> batch) {
    return exec.submit(() -> task.run(offset, batch));
  }

  /**
   * First pass over a source: parses every name and keeps what is needed to resolve the tree in memory.
   * Rows that cannot be converted to a document are logged and left out.
   */
  static IndexTaxonomy readTaxonomy(UsageSource source, int threads, int batchSize) throws IOException {
    StopWatch watch = StopWatch.createStarted();
    List<List<IndexTaxonomy.Row>> batches = runBatches(source, threads, batchSize, "Reading taxonomy", (offset, usages) -> {
      List<IndexTaxonomy.Row> rows = new ArrayList<>(usages.size());
      for (NameUsage u : usages) {
        IndexTaxonomy.Row row = null;
        try {
          Document doc = toDoc(u);
          row = new IndexTaxonomy.Row(
            doc.get(FIELD_ID),
            doc.get(FIELD_PARENT_ID),
            doc.get(FIELD_ACCEPTED_ID),
            doc.get(FIELD_RANK),
            doc.get(FIELD_STATUS),
            doc.get(FIELD_CANONICAL_NAME),
            doc.get(FIELD_FORMATTED));
        } catch (Exception e) {
          log.error("Skipping usage {} {}: {}", u.getId(), u.getScientificName(), e.toString());
        }
        rows.add(row);
      }
      return rows;
    });
    List<IndexTaxonomy.Row> rows = new ArrayList<>();
    batches.forEach(rows::addAll);
    batches.clear();
    IndexTaxonomy taxonomy = new IndexTaxonomy(rows);
    log.info("Taxonomy of {} usages loaded in {} s", taxonomy.size, watch.getTime(TimeUnit.SECONDS));
    return taxonomy;
  }

  /**
   * Converts a usage in the second pass, checking that the source is still aligned with the taxonomy.
   *
   * @return the document or null if the row was left out in the first pass
   */
  private static Document toDocAligned(IndexTaxonomy taxonomy, int i, NameUsage u) {
    if (i >= taxonomy.size) {
      throw new IllegalStateException("Source has more usages than on the first read: " + i);
    }
    if (taxonomy.ids[i] == null) {
      return null;
    }
    if (!taxonomy.ids[i].equals(u.getId())) {
      throw new IllegalStateException("Source changed between reads at row " + i + ": " + u.getId() + " != " + taxonomy.ids[i]);
    }
    return toDoc(u, taxonomy.canonicals[i], taxonomy.formatted[i]);
  }

  /**
   * Indexes a set of name usages into a new Lucene index in memory.
   * Only used for testing purposes.
   *
   * @param usages the name usages to index
   * @throws IOException if an error occurs during indexing
   */
  @SafeVarargs
  public static Directory newMemoryIndex(Iterable<NameUsage>... usages) throws IOException {
    log.info("Start building a new RAM index");
    Directory dir = new ByteBuffersDirectory();
    buildMainIndex(iterableSource(usages), dir, new IOUtil(), 1, 10_000);
    return dir;
  }

  /**
   * Builds the main index in a single write: the tree is read into memory first, then every usage is
   * written once with its stored classification and nested set range.
   *
   * @return the number of documents written
   */
  static long buildMainIndex(UsageSource source, Directory directory, IOUtil ioUtil, int threads, int batchSize)
    throws IOException {
    IndexTaxonomy taxonomy = readTaxonomy(source, threads, batchSize);

    StopWatch watch = StopWatch.createStarted();
    taxonomy.computeNestedSets();
    log.info("Nested sets computed in {} s", watch.getTime(TimeUnit.SECONDS));

    watch = StopWatch.createStarted();
    AtomicLong written = new AtomicLong();
    try (IndexWriter writer = new IndexWriter(directory, getIndexWriterConfig())) {
      runBatches(source, threads, batchSize, "Writing main index", (offset, usages) -> {
        for (int k = 0; k < usages.size(); k++) {
          int i = offset + k;
          Document doc = toDocAligned(taxonomy, i, usages.get(k));
          if (doc == null) continue;
          long[] nested = taxonomy.nestedSetFor(i);
          if (nested != null) {
            doc.add(new LongField(FIELD_LEFT_NESTED_SET_ID, nested[0], Field.Store.YES));
            doc.add(new LongField(FIELD_RIGHT_NESTED_SET_ID, nested[1], Field.Store.YES));
          }
          ioUtil.serialiseField(doc, FIELD_CLASSIFICATION, taxonomy.classification(i));
          writer.addDocument(doc);
          written.incrementAndGet();
        }
        return null;
      });
      log.info("Main index documents written in {} s", watch.getTime(TimeUnit.SECONDS));
      finishIndex(writer);
    }
    log.info("Main index complete: {} documents, {} s", written.get(), watch.getTime(TimeUnit.SECONDS));
    return written.get();
  }

  /**
   * Creates a join index from a set of name usages, matching each one to the main index.
   * Only used for testing purposes.
   *
   * @see #createJoinIndex(MatchingService, UsageSource, Directory, boolean, boolean, int, int)
   */
  public static Long[] createJoinIndex(MatchingService matchingService,
                                       Iterable<NameUsage> usages,
                                       Directory outputDirectory,
                                       boolean acceptedOnly,
                                       boolean joinToAccepted,
                                       int indexingThreads,
                                       int indexingBatchSize) throws IOException {
    return createJoinIndex(matchingService, iterableSource(usages), outputDirectory, acceptedOnly, joinToAccepted,
      indexingThreads, indexingBatchSize);
  }

  /**
   * Creates a join index: the usages of a source checklist that match the main index, each with an
   * additional field that links to the matched main index usage.
   *
   * @param matchingService the matching service to use
   * @param source the usages of the source checklist
   * @param outputDirectory the directory to write the join index to
   * @param acceptedOnly if true only accepted taxa are indexed
   * @param joinToAccepted if true a source name matching a synonym in the main index is joined to
   *                       the accepted usage instead of the synonym itself
   * @param indexingThreads the number of threads to use for matching
   * @return an array containing the number of documents read and the number of documents matched to the main index
   * @throws IOException if the index cannot be created
   */
  static Long[] createJoinIndex(MatchingService matchingService,
                                UsageSource source,
                                Directory outputDirectory,
                                boolean acceptedOnly,
                                boolean joinToAccepted,
                                int indexingThreads,
                                int indexingBatchSize) throws IOException {

    IndexTaxonomy taxonomy = readTaxonomy(source, indexingThreads, indexingBatchSize);

    StopWatch watch = StopWatch.createStarted();
    AtomicLong counter = new AtomicLong(0);
    AtomicLong matchedCounter = new AtomicLong(0);
    try (IndexWriter writer = new IndexWriter(outputDirectory, getIndexWriterConfig())) {
      runBatches(source, indexingThreads, indexingBatchSize, "Creating join index", (offset, usages) -> {
        for (int k = 0; k < usages.size(); k++) {
          int i = offset + k;
          Document doc = toDocAligned(taxonomy, i, usages.get(k));
          if (doc == null) continue;
          counter.incrementAndGet();
          if (joinDoc(matchingService, taxonomy, i, doc, acceptedOnly, joinToAccepted)) {
            writer.addDocument(doc);
            matchedCounter.incrementAndGet();
          }
        }
        return null;
      });
      finishIndex(writer);
    }
    log.info("Join index complete: {} documents, {} matched, {} s", counter.get(), matchedCounter.get(),
      watch.getTime(TimeUnit.SECONDS));
    return new Long[]{counter.get(), matchedCounter.get()};
  }

  /**
   * Matches a source usage to the main index and adds the join field to its document.
   *
   * @return true if the document matched and should be indexed
   */
  private static boolean joinDoc(MatchingService matchingService, IndexTaxonomy taxonomy, int i, Document doc,
                                 boolean acceptedOnly, boolean joinToAccepted) {
    String id = doc.get(FIELD_ID);
    String status = doc.get(FIELD_STATUS);
    if (acceptedOnly && !TaxonomicStatus.ACCEPTED.name().equals(status)) {
      // skip synonyms, otherwise we would index them twice
      return false;
    }
    String scientificName = doc.get(FIELD_SCIENTIFIC_NAME);
    String canonical = doc.get(FIELD_CANONICAL_NAME);
    try {
      Rank rank = Rank.valueOf(doc.get(FIELD_RANK).toUpperCase());
      Map<String, String> hierarchy = taxonomy.rankedHierarchy(i);
      ClassificationQuery classification = new ClassificationQuery();
      classification.setKingdom(hierarchy.getOrDefault(Rank.KINGDOM.name(), ""));
      classification.setPhylum(hierarchy.getOrDefault(Rank.PHYLUM.name(), ""));
      classification.setClazz(hierarchy.getOrDefault(Rank.CLASS.name(), ""));
      classification.setOrder(hierarchy.getOrDefault(Rank.ORDER.name(), ""));
      classification.setFamily(hierarchy.getOrDefault(Rank.FAMILY.name(), ""));
      classification.setGenus(hierarchy.getOrDefault(Rank.GENUS.name(), ""));
      classification.setSpecies(hierarchy.getOrDefault(Rank.SPECIES.name(), ""));

      // use strict matching for classification to classification matching
      NameUsageMatch nameUsageMatch = matchingService.match(scientificName, rank, classification, true);
      if (nameUsageMatch.getUsage() == null) {
        log.info("No match for {} {} # {}", rank, scientificName, id);
        return false;
      }
      if (nameUsageMatch.getDiagnostics().getMatchType() == MatchType.HIGHERRANK) {
        log.info("Ignoring higher match for {} {} # {}", rank, scientificName, id);
        return false;
      }

      var acceptedUsage = nameUsageMatch.getAcceptedUsage();
      String matchedCanonical = acceptedUsage != null && acceptedUsage.getCanonicalName() != null ?
        acceptedUsage.getCanonicalName() : nameUsageMatch.getUsage().getCanonicalName();
      String matchedRank = acceptedUsage != null && acceptedUsage.getRank() != null ?
        acceptedUsage.getRank().name() : nameUsageMatch.getUsage().getRank().name();
      if (matchedCanonical == null) {
        return false;
      }
      if (acceptedOnly && (!matchedCanonical.equals(canonical) || !matchedRank.equals(rank.name()))) {
        log.info("Ignoring match for {} {} # {} because canonicals do not match {} != {} and ranks do not match {} != {} and acceptedOnly is true",
          rank, scientificName, id, matchedCanonical, canonical, matchedRank, rank.name());
        return false;
      }
      doc.add(new StringField(FIELD_JOIN_ID,
        joinToAccepted && acceptedUsage != null ? acceptedUsage.getKey() : nameUsageMatch.getUsage().getKey(),
        Field.Store.YES));
      return true;
    } catch (Exception e) {
      log.error("Problem matching name from " + ANCILLARY_DIR + " index {}: {}", scientificName, e.getMessage(), e);
      return false;
    }
  }

  /**
   * Writes a join index for an exported checklist. Failures are logged and do not stop the service.
   *
   * @param exportDir the directory of the exported CSV and its metadata
   * @param joinIndexPath the directory to write the join index to
   * @param acceptedOnly if true only accepted taxa are indexed
   * @param joinToAccepted if true a source name matching a synonym in the main index is joined to
   *                       the accepted usage instead of the synonym itself
   */
  private void writeJoinIndex(String exportDir, String joinIndexPath, boolean acceptedOnly, boolean joinToAccepted) {
    try {
      Path indexDirectory = initialiseIndexDirectory(joinIndexPath);
      Long[] counters;
      try (Directory directory = FSDirectory.open(indexDirectory)) {
        counters = createJoinIndex(matchingService, csvSource(exportDir + "/" + INDEX_CSV), directory,
          acceptedOnly, joinToAccepted, indexingThreads, indexingBatchSize);
      }

      ObjectMapper mapper = new ObjectMapper();
      Dataset metadata = mapper.readValue(new File(exportDir + "/" + METADATA_JSON), Dataset.class);
      metadata.setTaxonCount(counters[0]);
      metadata.setMatchesToMainIndex(counters[1]);
      mapper.writeValue(new File(joinIndexPath + "/" + METADATA_JSON), metadata);

      log.info("Join index written: {} documents, {} matched to " + MAIN_INDEX_DIR + " index", counters[0], counters[1]);
    } catch (Exception e) {
      log.error("Error writing join index {}: {}", joinIndexPath, e.getMessage(), e);
    }
  }

  @Transactional
  public void createMainIndex(String datasetKey, String gbifDatasetKey) throws Exception {
    final String mainIndexPath = indexPath + "/" + MAIN_INDEX_DIR;
    if (indexExists(mainIndexPath)){
      log.info("Main index already exists at path {}", mainIndexPath);
      return;
    }
    log.info("Generating index for path {}", mainIndexPath);
    writeCLBToFile(datasetKey, gbifDatasetKey);

    final String exportDir = exportPath + "/" + datasetKey;
    // build next to the final location and move into place once complete
    final String buildPath = indexPath + "/" + MAIN_INDEX_DIR + "-build";
    StopWatch watch = StopWatch.createStarted();
    long count;
    try (Directory directory = FSDirectory.open(initialiseIndexDirectory(buildPath))) {
      count = buildMainIndex(csvSource(exportDir + "/" + INDEX_CSV), directory, ioUtil, indexingThreads, indexingBatchSize);
    }
    log.info("Index size on disk: {}", sizeOfDirectoryQuietly(new File(buildPath)));

    ObjectMapper mapper = new ObjectMapper();
    Dataset metadata = mapper.readValue(new File(exportDir + "/" + METADATA_JSON), Dataset.class);
    metadata.setTaxonCount(count);
    metadata.setMatchesToMainIndex(count);
    mapper.writeValue(new File(buildPath + "/" + METADATA_JSON), metadata);

    initialiseIndexDirectory(mainIndexPath);
    FileUtils.moveDirectory(new File(buildPath), new File(mainIndexPath));
    log.info("Main index built: {} documents, time taken {} mins", count, watch.getTime(TimeUnit.MINUTES));
  }

  /**
   * Best-effort directory size for diagnostic logging only.
   *
   * @param dir the directory to size
   * @return the directory size in bytes, or -1 if it could not be determined
   */
  private static long sizeOfDirectoryQuietly(File dir) {
    try {
      return FileUtils.sizeOfDirectory(dir);
    } catch (RuntimeException e) { // UncheckedIOException (file removed mid-walk) / IllegalArgumentException
      log.warn("Could not determine size of {}: {}", dir, e.toString());
      return -1L;
    }
  }

  private static void finishIndex(IndexWriter indexWriter) throws IOException {
    log.info("Final index commit");
    indexWriter.commit();
    log.info("Optimising index....");
    indexWriter.forceMerge(1);
    indexWriter.commit();
    log.info("Optimisation complete.");
  }

  private @NotNull Path initialiseIndexDirectory(String indexPath) throws IOException {
    if (new File(indexPath).exists()) {
      FileUtils.forceDelete(new File(indexPath));
    }
    return Paths.get(indexPath);
  }

  /**
   * Generate the lucene document for a name usage
   * @param nameUsage to convert to lucene document
   * @return lucene document
   */
  protected static Document toDoc(NameUsage nameUsage) {

    /*
     Porting notes: The canonical name *sensu strictu* with nothing else but three name parts at
     most (genus, species, infraspecific). No rank or hybrid markers and no authorship,
     cultivar or strain information. Infrageneric names are represented without a
     leading genus. Unicode characters are replaced by their matching ASCII characters.
    */
    Rank rank = Rank.valueOf(nameUsage.getRank().toUpperCase());

    Optional<String> optCanonical = Optional.empty();
    ParsedName pn = null;
    NomCode nomCode = null;
    if (!StringUtils.isEmpty(nameUsage.getCode())) {
      nomCode = NomCode.valueOf(nameUsage.getCode());
    }
    pn = NameParsers.parseOrNull(nameUsage.getScientificName(), rank, nomCode);
    if (pn != null) {
      // canonicalMinimal will construct the name without the hybrid marker and authorship
      String canonical = NameFormatter.canonicalMinimal(pn);
      optCanonical = Optional.ofNullable(canonical);
    } else {
      log.debug("Unable to parse name to create canonical: {}", nameUsage.getScientificName());
    }

    String formattedName = null;
    if (pn != null){
      try {
        formattedName = NameFormatter.canonicalCompleteHtml(pn);
        if (StringUtils.isNotBlank(nameUsage.getAuthorship())) {
          formattedName += " " + nameUsage.getAuthorship();
        }
      } catch (Exception e) {
        // do nothing
        log.debug("Unable to parse name to create canonical: {}", nameUsage.getScientificName());
      }
    }

    final String canonical = optCanonical.orElse(nameUsage.getScientificName());
    return toDoc(nameUsage, canonical, formattedName);
  }

  private static Document toDoc(NameUsage nameUsage, String canonical, String formattedName) {
    Document doc = new Document();
    if (formattedName != null) {
      doc.add(new StringField(FIELD_FORMATTED, formattedName, Field.Store.YES));
    }

    // use custom precision step as we do not need range queries and prefer to save memory usage
    // instead
    doc.add(new StringField(FIELD_ID, nameUsage.getId(), Field.Store.YES));

    // we only store accepted key, no need to index it
    // If the name is a synonym, then parentId name usage points
    // to the accepted name
    if (StringUtils.isNotBlank(nameUsage.getStatus())
        && (
                nameUsage.getStatus().equals(TaxonomicStatus.SYNONYM.name())
                || nameUsage.getStatus().equals(TaxonomicStatus.AMBIGUOUS_SYNONYM.name())
                || nameUsage.getStatus().equals(TaxonomicStatus.MISAPPLIED.name())
        )
        && nameUsage.getParentId() != null) {
      doc.add(new StringField(FIELD_ACCEPTED_ID, nameUsage.getParentId(), Field.Store.YES));
    }

    // analyzed name field - this is what we search upon
    doc.add(new TextField(FIELD_CANONICAL_NAME, canonical, Field.Store.YES));

    // store full name and classification only to return a full match object for hits
    String nameComplete = nameUsage.getScientificName();
    if (StringUtils.isNotBlank(nameUsage.getAuthorship())) {
      nameComplete += " " + nameUsage.getAuthorship();
      doc.add(new TextField(FIELD_AUTHORSHIP, nameUsage.getAuthorship(), Field.Store.YES));
    }
    doc.add(new TextField(FIELD_SCIENTIFIC_NAME, nameComplete, Field.Store.YES));

    // this lucene index is not persistent, so not risk in changing ordinal numbers
    doc.add(new StringField(FIELD_RANK, nameUsage.getRank(), Field.Store.YES));

    if (StringUtils.isNotBlank(nameUsage.getParentId()) && !nameUsage.getParentId().equals(nameUsage.getId())) {
      doc.add(new StringField(FIELD_PARENT_ID, nameUsage.getParentId(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getCode())) {
      doc.add(new StringField(FIELD_NOMENCLATURAL_CODE, nameUsage.getCode(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getType())) {
      doc.add(new StringField(FIELD_TYPE, nameUsage.getType(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getGenericName())) {
      doc.add(new StringField(FIELD_GENERICNAME, nameUsage.getGenericName(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getInfragenericEpithet())) {
      doc.add(new StringField(FIELD_INFRAGENERIC_EPITHET, nameUsage.getInfragenericEpithet(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getSpecificEpithet())) {
      doc.add(new StringField(FIELD_SPECIFIC_EPITHET, nameUsage.getSpecificEpithet(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getInfraspecificEpithet())) {
      doc.add(new StringField(FIELD_INFRASPECIFIC_EPITHET, nameUsage.getInfraspecificEpithet(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getStatus())) {
      doc.add(new StringField(FIELD_STATUS, nameUsage.getStatus(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getCategory())) {
      doc.add(new StringField(FIELD_CATEGORY, nameUsage.getCategory(), Field.Store.YES));
    }

    if (StringUtils.isNotBlank(nameUsage.getEnvironments())) {
      // multi-valued: one stored field per environment, e.g. MARINE, BRACKISH
      for (String env : nameUsage.getEnvironments().split(",")) {
        if (StringUtils.isNotBlank(env)) {
          doc.add(new StringField(FIELD_ENVIRONMENTS, env.trim().toUpperCase(), Field.Store.YES));
        }
      }
    }

    return doc;
  }
}

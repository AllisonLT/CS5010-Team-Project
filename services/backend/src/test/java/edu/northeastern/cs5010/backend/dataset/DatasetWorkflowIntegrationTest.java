package edu.northeastern.cs5010.backend.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditRecord;
import edu.northeastern.cs5010.backend.audit.AuditRepository;
import edu.northeastern.cs5010.backend.inference.MlClient;
import edu.northeastern.cs5010.backend.inference.MlDtos;
import edu.northeastern.cs5010.backend.prediction.PredictionDocument;
import edu.northeastern.cs5010.backend.product.ProductDocument;
import edu.northeastern.cs5010.backend.product.ProductRepository;
import edu.northeastern.cs5010.backend.product.ProductStatus;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upload-to-selection workflow against a real MongoDB (Testcontainers). Skipped when Docker is not running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DatasetWorkflowIntegrationTest {
    /**
     * Started once and left running until the JVM exits (Testcontainers removes it then), so the backend can
     * still write its shutdown audit record when the Spring context closes.
     */
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            mongo.start();
        }
    }

    @TempDir
    static Path uploads;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.upload-directory", uploads::toString);
    }

    private static final String VALID = """
            Date;Time;Temperature;Humidity
            02.09.2024;09:35:05;6.0;70
            02.09.2024;09:36:05;nan;70
            02.09.2024;09:37:05;6.4;71
            02.09.2024;09:39:05;7.0;71
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MongoTemplate mongoTemplate;
    @Autowired
    AuditRepository audit;
    @Autowired
    DatasetService service;
    @Autowired
    DatasetStorage storage;
    @Autowired
    ProductRepository products;
    @MockitoBean
    MlClient ml;

    @BeforeEach
    void clean() throws IOException {
        mongoTemplate.dropCollection(DatasetDocument.class);
        mongoTemplate.dropCollection(PredictionDocument.class);
        mongoTemplate.dropCollection(ProductDocument.class);
        mongoTemplate.getCollectionNames().stream().filter(name -> name.startsWith("audit_"))
                .forEach(mongoTemplate::dropCollection);
        try (Stream<Path> files = Files.walk(uploads)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(uploads) && !p.endsWith("tmp") && !p.endsWith("datasets"))
                    .forEach(DatasetStorage::deleteQuietly);
        }
    }

    @Test
    void savedHistoryReturnsTheOriginalResultAndIncludesEarlierRecords() throws Exception {
        var start = LocalDateTime.of(2024, 9, 9, 13, 0);
        var format = DateTimeFormatter.ofPattern("dd.MM.uuuu;HH:mm:ss");
        String csv = "Date;Time;Temperature;Humidity\n" + String.join("\n", IntStream.rangeClosed(0, 60)
                .mapToObj(minute -> start.plusMinutes(minute).format(format) + ";7.0;70.0").toList());
        var uploaded = upload("SENSOR02_preprocessed.csv", csv, "displayName", "Cold room 2");
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
        String datasetId = json.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/v1/predictions/temperature").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/predictions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        when(ml.forecastTemperature(any())).thenReturn(new MlDtos.ForecastResponse("SENSOR02",
                "2024-09-09T14:00:00", 7.0, 10.0, false,
                List.of(new MlDtos.HorizonForecast(15, "2024-09-09T14:15:00", 7.5, 8.5, false)),
                7.5, 7.5, "celsius", "test-model"));
        var created = mvc.perform(post("/api/v1/predictions/temperature").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("datasetId", datasetId, "sensorId", "SENSOR02",
                                "horizonsMinutes", List.of(15), "thresholdC", 10.0))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode original = json.readTree(created.getResponse().getContentAsString());
        String predictionId = original.get("id").asText();
        Document saved = mongoTemplate.getCollection("predictions").find(new Document("_id", predictionId)).first();
        assertThat(saved).isNotNull();
        assertThat(saved.getString("datasetName")).isEqualTo("Cold room 2");

        // An earlier MongoDB record has no stored dataset name.
        Document earlier = new Document(saved);
        earlier.remove("datasetName");
        earlier.put("_id", "earlier-forecast");
        Instant earlierTime = saved.getDate("createdAt").toInstant().minusSeconds(60);
        earlier.put("createdAt", Date.from(earlierTime));
        mongoTemplate.getCollection("predictions").insertOne(earlier);
        mvc.perform(delete("/api/v1/datasets/{id}", datasetId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/predictions").param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].id").value(predictionId))
                .andExpect(jsonPath("$.items[0].modelVersion").value("test-model"))
                .andExpect(jsonPath("$.items[0].datasetName").value("Cold room 2"))
                .andExpect(jsonPath("$.items[0].result").doesNotExist());
        mvc.perform(get("/api/v1/predictions").param("size", "1").param("page", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("earlier-forecast"))
                .andExpect(jsonPath("$.items[0].datasetName").value("Cold room 2"));
        mvc.perform(get("/api/v1/predictions").param("datasetId", datasetId).param("type", "TEMPERATURE_FORECAST")
                        .param("from", earlierTime.toString()).param("to", earlierTime.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value("earlier-forecast"));

        var reopened = mvc.perform(get(created.getResponse().getHeader("Location")))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(reopened.getResponse().getContentAsString())).isEqualTo(original);
        mvc.perform(get("/api/v1/predictions/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PREDICTION_NOT_FOUND"));
        verify(ml).forecastTemperature(any());
    }

    @Test
    void shelfLifeEstimateIsSavedAuditedAndListedWithTheProductName() throws Exception {
        var start = LocalDateTime.of(2024, 9, 8, 18, 5);
        var format = DateTimeFormatter.ofPattern("dd.MM.uuuu;HH:mm:ss");
        String csv = "Date;Time;Temperature;Humidity\n" + String.join("\n", IntStream.range(0, 90)
                .mapToObj(minute -> start.plusMinutes(minute).format(format) + ";" + (minute < 60 ? "4.0" : "9.0")
                        + ";70.0").toList());
        var uploaded = upload("SENSOR02_preprocessed.csv", csv, "displayName", "Cold room 2");
        String datasetId = json.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();
        String productId = "0b9a7c2e-3e1f-4c55-9d8e-2a7d5b8f9e40";
        products.insert(new ProductDocument(productId, 1, "Chicken breast batch 14", "FRESH_POULTRY_AEROBIC", 2.0,
                1.0, new ProductDocument.TemperatureSource(datasetId, "SENSOR02", "2024-09-08T18:05:00",
                "2024-09-08T19:34:00"), null, ProductStatus.ACTIVE, null, Instant.EPOCH, Instant.EPOCH));

        when(ml.estimateShelfLife(any())).thenReturn(new MlDtos.ShelfLifeResponse("FRESH_POULTRY_AEROBIC",
                "2024-09-08T18:05:00", "2024-09-08T19:34:00", 9.0, 30.5, 0.86, "high", 6.4, false,
                new MlDtos.ShelfLifeFactors(30, 9.0, 5.7, 0.62), 0, List.of(),
                List.of("Modeled estimate for fresh poultry in aerobic storage. Not a food safety determination."),
                Map.of("name", "pseudomonads_square_root", "version", "tsaloumi2022-v1",
                        "citation_doi", "10.1016/j.psj.2022.101985"), "celsius"));
        var created = mvc.perform(post("/api/v1/predictions/shelf-life").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("productId", productId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SHELF_LIFE"))
                .andExpect(jsonPath("$.spoilageRisk").value("HIGH"))
                .andExpect(jsonPath("$.factors.minutesAbove4C").value(30))
                .andExpect(jsonPath("$.temperatureSource.end").value("2024-09-08T19:34:00"))
                .andReturn();
        JsonNode original = json.readTree(created.getResponse().getContentAsString());
        String predictionId = original.get("id").asText();

        // The model received every minute of the linked window, read from the stored minute.csv.
        ArgumentCaptor<MlDtos.ShelfLifeRequest> sent = ArgumentCaptor.forClass(MlDtos.ShelfLifeRequest.class);
        verify(ml).estimateShelfLife(sent.capture());
        assertThat(sent.getValue().history()).hasSize(90);
        assertThat(sent.getValue().history().getLast().temperatureC()).isEqualTo(9.0);

        mvc.perform(get("/api/v1/predictions").param("type", "SHELF_LIFE").param("productId", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(predictionId))
                .andExpect(jsonPath("$.items[0].productName").value("Chicken breast batch 14"))
                .andExpect(jsonPath("$.items[0].datasetName").value("Cold room 2"))
                .andExpect(jsonPath("$.items[0].modelVersion").value("tsaloumi2022-v1"));
        var reopened = mvc.perform(get(created.getResponse().getHeader("Location")))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(reopened.getResponse().getContentAsString())).isEqualTo(original);

        AuditRecord createdAudit = auditOf(AuditEventType.PREDICTION_CREATED).getFirst();
        assertThat(createdAudit.refs().productId()).isEqualTo(productId);
        assertThat(createdAudit.refs().predictionId()).isEqualTo(predictionId);
        assertThat(createdAudit.details()).containsEntry("predictionType", "SHELF_LIFE");
        assertThat(auditOf(AuditEventType.SPOILAGE_RISK_HIGH)).singleElement()
                .satisfies(record -> assertThat(record.refs().predictionId()).isEqualTo(predictionId));

        // A product without a temperature link is refused, audited, and leaves no result behind.
        products.insert(new ProductDocument("unlinked", 1, "Unlinked batch", "FRESH_POULTRY_AEROBIC", null, null,
                null, null, ProductStatus.ACTIVE, null, Instant.EPOCH, Instant.EPOCH));
        mvc.perform(post("/api/v1/predictions/shelf-life").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"unlinked\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_LINKED"))
                .andExpect(jsonPath("$.auditId").isNotEmpty());
        assertThat(auditOf(AuditEventType.PREDICTION_FAILED)).singleElement()
                .satisfies(record -> assertThat(record.refs().productId()).isEqualTo("unlinked"));
        mvc.perform(get("/api/v1/predictions").param("type", "SHELF_LIFE"))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    private MvcResult upload(String filename, String content, String... params) throws Exception {
        var request = multipart("/api/v1/datasets")
                .file(new MockMultipartFile("file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andReturn();
    }

    private List<AuditRecord> auditOf(AuditEventType type) {
        return audit.findNewestFirst(Criteria.where("eventType").is(type), 0, 50);
    }

    @Test
    void auditApiCombinesCategorizedLegacyAndMlRecordsAndRecordsRejectedWrites() throws Exception {
        audit.insert(new AuditRecord("dataset-record", 1, Instant.parse("2026-11-07T20:00:00Z"),
                AuditEventType.DATASET_UPLOADED, "backend.DatasetService", "host", "local-user:test",
                AuditRecord.Outcome.SUCCESS, AuditRecord.Refs.dataset("dataset-1"), Map.of(), null));
        assertThat(mongoTemplate.getCollection("audit_dataset").countDocuments()).isEqualTo(1);
        for (String collection : List.of("audit_logs", "audit_ml")) {
            boolean ml = collection.equals("audit_ml");
            mongoTemplate.getCollection(collection).insertOne(new Document("_id", collection + "-record")
                    .append("schemaVersion", 1)
                    .append("timestamp", Date.from(Instant.parse(ml ? "2026-11-07T20:02:00Z" : "2026-11-07T20:01:00Z")))
                    .append("source", ml ? "ml.predict_temperature" : "backend.DatasetService")
                    .append("actor", ml ? "service:ml" : "local-user:test").append("host", "host")
                    .append("eventType", ml ? "ML_INFERENCE_FAILED" : "DATASET_UPLOAD_REJECTED")
                    .append("outcome", "FAILURE").append("refs", new Document()).append("details", new Document())
                    .append("error", new Document("code", "REJECTED").append("message", "Invalid input")));
        }
        mvc.perform(get("/api/v1/audit-logs").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].category").value("ML"))
                .andExpect(jsonPath("$.items[0].error.code").value("REJECTED"))
                .andExpect(jsonPath("$.items[1].id").value("audit_logs-record"));
        mvc.perform(get("/api/v1/audit-logs").param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("dataset-record"));
        mvc.perform(get("/api/v1/audit-logs").param("category", "DATASET").param("outcome", "FAILURE")
                        .param("eventType", "DATASET_UPLOADED", "DATASET_UPLOAD_REJECTED")
                        .param("from", "2026-11-07T20:01:00Z").param("to", "2026-11-07T20:01:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("audit_logs-record"));
        mvc.perform(put("/api/v1/audit-logs/{id}", "dataset-record"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.auditId").isNotEmpty());
        assertThat(mongoTemplate.getCollection("audit_dataset").countDocuments()).isEqualTo(1);
        assertThat(mongoTemplate.getCollection("audit_access").countDocuments()).isEqualTo(1);
    }

    @Test
    void validUploadIsStoredAndSelectable() throws Exception {
        MvcResult result = upload("SENSOR02_preprocessed.csv", VALID, "displayName", "Cold room 2");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String id = body.get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/datasets/" + id);
        assertThat(body.get("displayName").asText()).isEqualTo("Cold room 2");
        assertThat(body.get("status").asText()).isEqualTo("READY");
        assertThat(body.get("sensorIds").get(0).asText()).isEqualTo("SENSOR02");
        assertThat(body.get("recordCount").asInt()).isEqualTo(4);
        assertThat(body.get("acceptedRecordCount").asInt()).isEqualTo(3);
        assertThat(body.get("timeRange").get("start").asText()).isEqualTo("2024-09-02T09:35:05");
        assertThat(body.get("minuteSummary").get("minuteCount").asInt()).isEqualTo(5);
        assertThat(body.get("minuteSummary").get("missingMinuteCount").asInt()).isEqualTo(2);
        assertThat(body.get("warnings").get(0).get("code").asText()).isEqualTo("MISSING_TEMPERATURE");

        DatasetDocument stored = mongoTemplate.findById(id, DatasetDocument.class);
        assertThat(stored).isNotNull();
        Path source = storage.resolve(stored.storage().sourcePath());
        assertThat(Files.readString(source)).isEqualTo(VALID);
        assertThat(Files.exists(storage.resolve(stored.storage().processedPath()))).isTrue();
        assertThat(stored.storage().sha256()).hasSize(64);

        mvc.perform(get("/api/v1/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/v1/datasets/" + id + "/readings").param("sensorId", "SENSOR02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readings.length()").value(5))
                .andExpect(jsonPath("$.readings[0].timestamp").value("2024-09-02T09:36:00"))
                .andExpect(jsonPath("$.readings[0].temperatureC").value(6.0))
                .andExpect(jsonPath("$.readings[1].temperatureC").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.readings[2].temperatureC").value(6.4));

        List<AuditRecord> uploaded = auditOf(AuditEventType.DATASET_UPLOADED);
        assertThat(uploaded).hasSize(1);
        AuditRecord record = uploaded.get(0);
        assertThat(record.outcome()).isEqualTo(AuditRecord.Outcome.SUCCESS);
        assertThat(record.refs().datasetId()).isEqualTo(id);
        assertThat(record.timestamp()).isNotNull();
        assertThat(record.source()).isEqualTo("backend.DatasetService");
        assertThat(record.actor()).startsWith("local-user:");
        assertThat(record.details()).containsEntry("filename", "SENSOR02_preprocessed.csv")
                .containsEntry("recordCount", 4);
    }

    @Test
    void selectionSurvivesRestartBecauseEverythingIsPersisted() throws Exception {
        String id = json.readTree(upload("SENSOR04.csv", VALID).getResponse().getContentAsString())
                .get("id").asText();

        // A restart keeps only MongoDB and the files; read both back without any in-memory state.
        DatasetDocument reloaded = service.getReady(id);
        assertThat(service.minutes(reloaded, "SENSOR04", null, null)).hasSize(5);
    }

    @Test
    void malformedFileIsRejectedWithRowsAndLeavesNothingBehind() throws Exception {
        MvcResult result = upload("SENSOR01.csv", """
                Date;Time;Temperature
                2024-09-02;09:35:25;5
                02.09.2024;09:35:30;5
                """);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("CSV_INVALID");
        assertThat(body.get("errors").get(0).get("row").asInt()).isEqualTo(2);
        assertThat(body.get("auditId").asText()).isNotBlank();

        assertNothingStored();
        List<AuditRecord> rejected = auditOf(AuditEventType.DATASET_UPLOAD_REJECTED);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).outcome()).isEqualTo(AuditRecord.Outcome.FAILURE);
        assertThat(rejected.get(0).error().code()).isEqualTo("CSV_INVALID");
        assertThat(rejected.get(0).id()).isEqualTo(body.get("auditId").asText());
    }

    @Test
    void missingColumnEmptyFileAndWrongTypeAreRejected() throws Exception {
        assertThat(upload("SENSOR01.csv", "Date;Time;Humidity\n02.09.2024;09:35:25;60\n").getResponse().getStatus())
                .isEqualTo(422);
        assertThat(upload("SENSOR01.csv", "").getResponse().getStatus()).isEqualTo(422);
        MvcResult wrongType = upload("SENSOR01.txt", VALID);
        assertThat(wrongType.getResponse().getStatus()).isEqualTo(415);
        assertThat(json.readTree(wrongType.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        assertNothingStored();
        assertThat(auditOf(AuditEventType.DATASET_UPLOAD_REJECTED)).hasSize(3);
    }

    @Test
    void missingSensorIdIsRequestedFromTheUser() throws Exception {
        assertThat(upload("readings.csv", VALID).getResponse().getStatus()).isEqualTo(422);
        assertThat(upload("readings.csv", VALID, "sensorId", "ROOM_A").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void deleteHidesTheDatasetRemovesFilesAndIsAudited() throws Exception {
        String id = json.readTree(upload("SENSOR02.csv", VALID).getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(delete("/api/v1/datasets/" + id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/datasets")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/datasets").param("status", "DELETED")).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/v1/datasets/" + id + "/readings").param("sensorId", "SENSOR02"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATASET_NOT_READY"));
        assertThat(Files.exists(storage.folder(id))).isFalse();
        assertThat(auditOf(AuditEventType.DATASET_DELETED)).hasSize(1);
    }

    @Test
    void datasetWhoseFileDisappearedIsMarkedMissingOnStartupCheck() throws Exception {
        String id = json.readTree(upload("SENSOR02.csv", VALID).getResponse().getContentAsString())
                .get("id").asText();
        storage.deleteFolder(id);

        service.checkStorageOnStartup();

        assertThat(service.get(id).status()).isEqualTo(DatasetStatus.MISSING_FILE);
        assertThat(auditOf(AuditEventType.DATASET_FILE_MISSING)).hasSize(1);
    }

    @Test
    void unknownDatasetIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/datasets/00000000-0000-4000-8000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DATASET_NOT_FOUND"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("json")));
    }

    private void assertNothingStored() throws IOException {
        assertThat(mongoTemplate.findAll(DatasetDocument.class)).isEmpty();
        try (Stream<Path> datasets = Files.list(uploads.resolve("datasets"));
             Stream<Path> parts = Files.list(uploads.resolve("tmp"))) {
            assertThat(datasets).isEmpty();
            assertThat(parts).isEmpty();
        }
    }
}

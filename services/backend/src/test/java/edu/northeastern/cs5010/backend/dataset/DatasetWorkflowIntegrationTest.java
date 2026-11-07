package edu.northeastern.cs5010.backend.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditRecord;
import edu.northeastern.cs5010.backend.audit.AuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @BeforeEach
    void clean() throws IOException {
        mongoTemplate.dropCollection(DatasetDocument.class);
        mongoTemplate.dropCollection(AuditRecord.class);
        try (Stream<Path> files = Files.walk(uploads)) {
            files.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(uploads) && !p.endsWith("tmp") && !p.endsWith("datasets"))
                    .forEach(DatasetStorage::deleteQuietly);
        }
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

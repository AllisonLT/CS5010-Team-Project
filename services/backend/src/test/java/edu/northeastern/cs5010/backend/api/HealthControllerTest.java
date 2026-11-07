package edu.northeastern.cs5010.backend.api;

import edu.northeastern.cs5010.backend.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuditService audit;

    @Test
    void healthEndpointReturnsServiceIdentity() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("backend"));
    }

    @Test
    void unimplementedPredictionsAreNotSuccessful() throws Exception {
        mvc.perform(get("/api/v1/predictions"))
                .andExpect(status().isNotFound());
    }
}

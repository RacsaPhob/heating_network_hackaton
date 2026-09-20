package ru.heatnet.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:metadata;DB_CLOSE_DELAY=-1", "heatnet.storage=target/test-storage"})
@AutoConfigureMockMvc
class MetadataControllerTest {
    @Autowired private MockMvc mvc;

    @Test
    void rulesArePackagedAndMvpAdvertisesItsCapabilities() throws Exception {
        mvc.perform(get("/api/v1/meta"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routing_available").value(true))
            .andExpect(jsonPath("$.competition_ready").value(false));
        mvc.perform(get("/api/v1/rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diameters.length()").value(18))
            .andExpect(jsonPath("$.diameters[0].diameter_mm").value(50))
            .andExpect(jsonPath("$.diameters[0].capacity_tph").value(3.5));
    }
}

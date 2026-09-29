package ru.heatnet.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:metadata;DB_CLOSE_DELAY=-1", "heatnet.storage=target/test-storage"})
@AutoConfigureMockMvc
class WorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Test void strictSampleAcceptsTheCurrentInputSchema() throws Exception {
        mvc.perform(post("/api/v1/datasets/sample?mode=strict"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.connection_count").value(17));
    }
    @Test void sampleUploadRouteAndExportWorksEndToEnd() throws Exception {
        String text=mvc.perform(post("/api/v1/datasets/sample?mode=demo")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode dataset=mapper.readTree(text);
        assertEquals("READY",dataset.path("status").asText(),text);
        assertEquals(17,dataset.path("connection_count").asInt());
        String submitted=mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"datasetId\":\""+dataset.path("id").asText()+"\",\"maxVariants\":1}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(submitted).path("id").asText();JsonNode job=null;
        for(int i=0;i<180;i++) {
            job=mapper.readTree(mvc.perform(get("/api/v1/jobs/"+id)).andReturn().getResponse().getContentAsString());
            if(!job.path("status").asText().matches("QUEUED|RUNNING")) break;
            Thread.sleep(250);
        }
        assertNotNull(job);assertTrue(job.path("status").asText().matches("SUCCEEDED|PARTIAL|REVIEW_REQUIRED"),job.toString());
        assertTrue(job.path("variants").get(0).path("connected_count").asInt()>=12,job.toString());
        java.nio.file.Files.writeString(java.nio.file.Paths.get("target/workflow-job.json"),job.toPrettyString());
        JsonNode history=mapper.readTree(mvc.perform(get("/api/v1/jobs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(java.util.stream.StreamSupport.stream(history.spliterator(),false).anyMatch(row->row.path("id").asText().equals(id)));
        String csv=mvc.perform(get("/api/v1/jobs/"+id+"/report.csv")).andExpect(status().isOk())
            .andExpect(header().string("Content-Type","text/csv;charset=UTF-8")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFFМесто;"));assertTrue(csv.contains("Строительство, руб."));
        String output=mvc.perform(get("/api/v1/jobs/"+id+"/result")).andExpect(status().isOk())
            .andExpect(header().exists("Content-Disposition")).andReturn().getResponse().getContentAsString();
        JsonNode geojson=mapper.readTree(output);
        assertEquals("FeatureCollection",geojson.path("type").asText());
        assertTrue(geojson.path("heatnet_metadata").path("reconstruction_known").asBoolean());
        int summaryCount=0,edgeCount=0;
        for(JsonNode feature:geojson.path("features")) {
            JsonNode p=feature.path("properties");
            if(p.path("object_type").asText().equals("variant_summary")) {summaryCount++;assertTrue(feature.path("geometry").isNull());}
            if(p.path("object_type").asText().equals("heat_network")) {edgeCount++;assertTrue(p.path("depth_start").isNull());assertTrue(p.path("length").asDouble()>0);}
        }
        assertEquals(1,summaryCount);assertTrue(edgeCount>0);
    }
}

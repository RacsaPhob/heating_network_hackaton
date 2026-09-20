package ru.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MetadataController {
    private final JsonNode rules;

    public MetadataController(ObjectMapper mapper) throws IOException {
        // This is a small trusted rules catalog, not an uploaded dataset.
        try (InputStream stream = new ClassPathResource("contracts/rules.json").getInputStream()) {
            rules = mapper.readTree(stream);
        }
    }

    @GetMapping("/meta")
    public Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "heatnet");
        result.put("stage", "mvp");
        result.put("contract_version", "0.2");
        result.put("input_crs", "EPSG:4326");
        result.put("calculation_crs", "EPSG:32637");
        result.put("implemented", Arrays.asList("metadata", "rules_catalog", "upload", "validation", "routing", "engineering", "export"));
        result.put("routing_available", true);
        result.put("competition_ready", false);
        result.put("max_upload_mb", 20);
        return result;
    }

    @GetMapping("/rules")
    public JsonNode rules() {
        return rules;
    }
}

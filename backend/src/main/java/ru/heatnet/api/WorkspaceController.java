package ru.heatnet.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.heatnet.core.*;
import ru.heatnet.storage.RecordStore;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private final DatasetService datasets;private final RecordStore store;private final JobService jobs;
    public WorkspaceController(DatasetService datasets,RecordStore store,JobService jobs) {this.datasets=datasets;this.store=store;this.jobs=jobs;}
    private boolean isDemo(String mode) {
        if(!Arrays.asList("demo","strict").contains(mode)) throw new IllegalArgumentException("Режим должен быть demo или strict");
        return mode.equals("demo");
    }
    @GetMapping("/datasets") public List<JsonNode> list() {return store.list("dataset");}
    @PostMapping(value="/datasets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file,@RequestParam(defaultValue="strict") String mode) throws IOException {
        String name=file.getOriginalFilename()==null?"dataset.geojson":file.getOriginalFilename();
        if(name.length()>200) name=name.substring(name.length()-200);
        try(InputStream input=file.getInputStream()) {return datasets.ingest(input,name,isDemo(mode));}
    }
    @PostMapping("/datasets/sample") public Map<String,Object> sample(@RequestParam(defaultValue="demo") String mode) throws IOException {
        try(InputStream input=new ClassPathResource("samples/!!!_Датасет.geojson").getInputStream()) {
            return datasets.ingest(input,"Конкурсный набор · ЗИЛ",isDemo(mode));
        }
    }
    @GetMapping("/datasets/{id}") public JsonNode dataset(@PathVariable String id) {return store.get(id,"dataset");}
    @GetMapping("/datasets/{id}/features") public ResponseEntity<Resource> features(@PathVariable String id) throws IOException {
        store.get(id,"dataset");return resource(datasets.file(id,".geojson"),null);
    }
    @PostMapping("/jobs") public ResponseEntity<Map<String,Object>> start(@RequestBody JsonNode request) {
        return ResponseEntity.accepted().body(jobs.submit(request.path("datasetId").asText(),request.path("maxVariants").asInt(3)));
    }
    @GetMapping("/jobs/{id}") public JsonNode job(@PathVariable String id) {return store.get(id,"job");}
    @GetMapping("/jobs") public List<Map<String,Object>> history() {
        List<JsonNode> records=store.list("job");
        records.sort(Comparator.comparing((JsonNode node)->node.path("created_at").asText()).reversed());
        List<Map<String,Object>> result=new ArrayList<>();
        for(JsonNode record:records.subList(0,Math.min(50,records.size()))) {
            JsonNode best=record.path("variants").path(0);
            result.add(Geo.map("id",record.path("id").asText(),"dataset_id",record.path("dataset_id").asText(),
                "dataset_name",record.path("dataset_name").asText("Набор данных"),"created_at",record.path("created_at").asText(),
                "status",record.path("status").asText(),"mode",record.path("mode").asText(),"engine_version",record.path("engine_version").asText("0.1"),
                "variant_count",record.path("variants").size(),"best_summary",best.path("summary"),
                "connected_count",best.path("connected_count").asInt(),"total_count",best.path("total_count").asInt()));
        }
        return result;
    }
    @GetMapping("/jobs/{id}/report.csv") public ResponseEntity<byte[]> report(@PathVariable String id) {
        JsonNode job=store.get(id,"job");
        if(!Arrays.asList("SUCCEEDED","PARTIAL","REVIEW_REQUIRED").contains(job.path("status").asText())) throw new IllegalArgumentException("Результат ещё не готов");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"heatnet-comparison-"+id+".csv\"")
            .body(ComparisonReport.csv(job).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @GetMapping("/jobs/{id}/result") public ResponseEntity<Resource> result(@PathVariable String id) throws IOException {
        JsonNode job=store.get(id,"job");
        if(!Arrays.asList("SUCCEEDED","PARTIAL","REVIEW_REQUIRED").contains(job.path("status").asText())) throw new IllegalArgumentException("Результат ещё не готов");
        return resource(datasets.file(id,"-result.geojson"),"heatnet-"+job.path("mode").asText()+"-"+id+".geojson");
    }
    private ResponseEntity<Resource> resource(Path path,String filename) throws IOException {
        if(!Files.exists(path)) throw new NoSuchElementException("Файл не найден");
        ResponseEntity.BodyBuilder response=ResponseEntity.ok().contentType(MediaType.parseMediaType("application/geo+json")).contentLength(Files.size(path));
        if(filename!=null) response.header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"");
        return response.body(new FileSystemResource(path));
    }
}

package ru.heatnet.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Service;
import ru.heatnet.storage.RecordStore;

@Service
public class JobService {
    private final DatasetService datasets; private final RecordStore store; private final Router router;
    private final Engineering engineering; private final ObjectMapper mapper;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8));
    public JobService(DatasetService datasets,RecordStore store,Router router,Engineering engineering,ObjectMapper mapper) {
        this.datasets=datasets;this.store=store;this.router=router;this.engineering=engineering;this.mapper=mapper;
    }
    @PostConstruct public void recover() {
        for(JsonNode value:store.list("job")) if(Arrays.asList("QUEUED","RUNNING").contains(value.path("status").asText())) {
            ObjectNode record=(ObjectNode)value;record.put("status","FAILED");record.put("message","Сервер перезапущен. Запустите расчёт повторно.");
            store.save(record.path("id").asText(),"job",record);
        }
    }
    @PreDestroy public void shutdown() {executor.shutdownNow();}
    public Map<String,Object> submit(String datasetId,int variants) {
        JsonNode dataset=store.get(datasetId,"dataset");
        if(!dataset.path("status").asText().equals("READY")) throw new IllegalArgumentException("Исправьте ошибки набора перед расчётом");
        if(variants<1||variants>3) throw new IllegalArgumentException("Допустимо от 1 до 3 вариантов");
        String id=UUID.randomUUID().toString();
        Map<String,Object> state=Geo.map("id",id,"dataset_id",datasetId,"status","QUEUED","stage","В очереди", "progress",0,
            "created_at",Instant.now().toString(),"mode",dataset.path("mode").asText(),"dataset_name",dataset.path("name").asText(),"engine_version","0.2");
        store.save(id,"job",state);
        try {executor.execute(()->run(id,datasetId,variants,state));}
        catch(RejectedExecutionException ex) {state.put("status","FAILED");state.put("message","Очередь заполнена, повторите позже");store.save(id,"job",state);throw ex;}
        return Geo.map("id",id,"status","QUEUED");
    }
    private void run(String id,String datasetId,int count,Map<String,Object> state) {
        long started=System.nanoTime();
        try {
            Dataset data=datasets.load(datasetId);
            if(!data.errors.isEmpty()) throw new IllegalArgumentException(String.join(" ",data.errors));
            List<Engineering.Evaluated> variants=new ArrayList<>();Set<String> signatures=new HashSet<>();
            state.put("status","RUNNING");
            for(int variant=0;variant<count;variant++) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                final int current=variant;
                state.put("progress",10+variant*25);state.put("stage","Построение варианта "+(variant+1));store.save(id,"job",state);
                Router.Candidate candidate=router.route(data,variant,stage->{state.put("stage",stage+" · "+(current+1)+"/"+count);store.save(id,"job",state);});
                Engineering.Evaluated evaluated=engineering.evaluate(data,candidate,Integer.toString(variant+1));
                if(evaluated.violations.stream().anyMatch(v->v.startsWith("Пересечение новых"))) {
                    // Smoothing must not create new crossings. Fall back to the original grid paths.
                    for(Router.Edge edge:candidate.edges) {
                        edge.coordinates=edge.originalCoordinates;
                        edge.length=Geo.GF.createLineString(edge.coordinates.toArray(new Coordinate[0])).getLength();
                    }
                    candidate.notes.add("Сглаживание отменено, чтобы сохранить топологию ветвей; геометрия требует дальнейшей оптимизации.");
                    evaluated=engineering.evaluate(data,candidate,Integer.toString(variant+1));
                }
                List<String> signatureParts=new ArrayList<>();
                for(Router.Edge edge:candidate.edges) {
                    StringBuilder shape=new StringBuilder();
                    for(Coordinate coordinate:edge.coordinates) shape.append(Math.round(coordinate.x*100)).append(',').append(Math.round(coordinate.y*100)).append(';');
                    signatureParts.add(shape.toString()+edge.pipe.diameter);
                }
                Collections.sort(signatureParts);String signature=String.join("|",signatureParts);
                if(signatures.add(signature)) variants.add(evaluated);
            }
            variants.sort(Comparator.comparingDouble(v->((Number)v.summary.get("score")).doubleValue()));
            List<Map<String,Object>> features=new ArrayList<>(),display=new ArrayList<>();
            int rank=0;boolean review=false;boolean partial=false;
            for(Engineering.Evaluated evaluated:variants) {
                evaluated.summary.put("rank",++rank);features.addAll(evaluated.features);display.add(evaluated.display);
                review|=!evaluated.violations.isEmpty();
                partial|=!((List<?>)evaluated.summary.get("unconnected_oks_ids")).isEmpty();
            }
            Map<String,Object> metadata=Geo.map("mode",data.demo?"demo":"strict","competition_ready",false,
                "reconstruction_known",data.reconstructionKnown,"assumptions",data.warnings,
                "limitations",Arrays.asList("Эскизный MVP; требуется инженерная проверка.","2D, без оптимизации специальных проходов через дороги и коммуникации.","Поиск на сетке с консервативными отступами; не гарантирует глобальный оптимум."));
            Map<String,Object> result=Geo.map("type","FeatureCollection","heatnet_metadata",metadata,"features",features);
            Path target=datasets.file(id,"-result.geojson"),temp=datasets.file(id,"-result.tmp");
            mapper.writeValue(temp.toFile(),result);
            Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
            state.put("status",review?"REVIEW_REQUIRED":partial?"PARTIAL":"SUCCEEDED");
            state.put("stage","Расчёт завершён");state.put("progress",100);state.put("variants",display);
            state.put("metadata",metadata);state.put("elapsed_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));state.put("completed_at",Instant.now().toString());store.save(id,"job",state);
        } catch(Exception ex) {
            state.put("status","FAILED");state.put("message",ex.getMessage()==null?"Не удалось выполнить расчёт":ex.getMessage());
            state.put("stage","Ошибка расчёта");store.save(id,"job",state);
        }
    }
}

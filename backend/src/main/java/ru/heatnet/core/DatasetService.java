package ru.heatnet.core;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.heatnet.storage.RecordStore;

@Service
public class DatasetService {
    public final Path directory;
    private final ObjectMapper mapper;
    private final Catalog catalog;
    private final RecordStore store;
    public DatasetService(ObjectMapper mapper,Catalog catalog,RecordStore store,@Value("${heatnet.storage}") String directory) throws IOException {
        this.mapper=mapper;this.catalog=catalog;this.store=store;
        this.directory=Paths.get(directory).toAbsolutePath().normalize();Files.createDirectories(this.directory);
    }
    public Path file(String id,String suffix) {
        if(!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Некорректный ID");
        return directory.resolve(id+suffix);
    }
    public Map<String,Object> ingest(InputStream input,String name,boolean demo) throws IOException {
        String id=UUID.randomUUID().toString(); Path path=file(id,".geojson");
        try(OutputStream output=Files.newOutputStream(path)) {
            byte[] buffer=new byte[65536];long total=0;int read;
            while((read=input.read(buffer))!=-1) {
                total+=read;if(total>20L*1024*1024) throw new IllegalArgumentException("MVP принимает файлы до 20 МБ");
                output.write(buffer,0,read);
            }
        } catch(Exception ex) {Files.deleteIfExists(path);throw ex;}
        Dataset dataset;
        try {dataset=parse(path,demo);} catch(Exception ex) {Files.deleteIfExists(path);throw ex;}
        Map<String,Integer> counts=new TreeMap<>();
        for(Dataset.Item item:dataset.items) counts.merge(item.type,1,Integer::sum);
        Map<String,Object> report=Geo.map("id",id,"name",name,"mode",demo?"demo":"strict",
            "created_at",Instant.now().toString(),"status",dataset.errors.isEmpty()?"READY":"INVALID",
            "counts",counts,"feature_count",dataset.items.size(),"connection_count",dataset.connections.size(),
            "total_flow_tph",dataset.connections.stream().mapToDouble(c->c.flow).sum(),
            "errors",dataset.errors,"warnings",dataset.warnings,"reconstruction_known",dataset.reconstructionKnown,
            "size_bytes",Files.size(path),"bounds",bounds(dataset));
        store.save(id,"dataset",report);return report;
    }
    private List<List<Double>> bounds(Dataset data) {
        if(data.bounds.isNull()) return Collections.emptyList();
        return Arrays.asList(Geo.unproject(new Coordinate(data.bounds.getMinX(),data.bounds.getMinY())),Geo.unproject(new Coordinate(data.bounds.getMaxX(),data.bounds.getMaxY())));
    }
    public Dataset load(String id) throws IOException {
        JsonNode meta=store.get(id,"dataset");
        return parse(file(id,".geojson"),meta.path("mode").asText().equals("demo"));
    }
    private void problem(Dataset data,String message) {
        List<String> messages=data.demo?data.warnings:data.errors;
        if(!messages.contains(message)) messages.add(message);
    }
    public Dataset parse(Path path,boolean demo) throws IOException {
        Dataset data=new Dataset();data.demo=demo;
        boolean featuresFound=false; String rootType=""; int coordinateCount=0;
        try(JsonParser parser=mapper.getFactory().createParser(path.toFile())) {
            if(parser.nextToken()!=JsonToken.START_OBJECT) throw new IllegalArgumentException("Ожидается GeoJSON FeatureCollection");
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                if(parser.currentToken()==null) throw new IllegalArgumentException("Оборванный JSON");
                String field=parser.currentName();parser.nextToken();
                if("type".equals(field)) rootType=parser.getValueAsString();
                else if("features".equals(field)) {
                    if(featuresFound||parser.currentToken()!=JsonToken.START_ARRAY) throw new IllegalArgumentException("Некорректный массив features");
                    featuresFound=true;
                    while(parser.nextToken()!=JsonToken.END_ARRAY) {
                        if(parser.currentToken()==null) throw new IllegalArgumentException("Оборванный массив features");
                        JsonNode raw=mapper.readTree(parser);
                        if(data.items.size()>=4000) throw new IllegalArgumentException("MVP поддерживает до 4000 объектов на набор");
                        JsonNode properties=raw.path("properties");
                        if(!raw.path("type").asText().equals("Feature")||!properties.isObject()||properties.path("id").isMissingNode())
                            throw new IllegalArgumentException("У каждого Feature должны быть properties и id");
                        Dataset.Item item=new Dataset.Item();
                        item.id=properties.path("id").asText();item.type=properties.path("object_type").asText();
                        if(item.id.isEmpty()||data.byId.containsKey(item.id)) throw new IllegalArgumentException("Пустой или повторяющийся ID: "+item.id);
                        item.properties=properties;item.raw=raw;
                        try {item.geometry=Geo.read(raw.path("geometry"));}
                        catch(IllegalArgumentException ex) {throw new IllegalArgumentException("Объект "+item.id+": "+ex.getMessage());}
                        coordinateCount+=item.geometry.getNumPoints();
                        if(coordinateCount>200000) throw new IllegalArgumentException("Слишком сложная геометрия для MVP (более 200 000 координат)");
                        data.bounds.expandToInclude(item.geometry.getEnvelopeInternal());
                        item.restriction=properties.path("restriction_type").asText();
                        if(!Arrays.asList("source","heat_network","heat_chamber","oks_future","oks_existing","oks_connection_point","restriction").contains(item.type))
                            throw new IllegalArgumentException("Неизвестный object_type: "+item.type);
                        boolean pointType=Arrays.asList("source","heat_chamber","oks_connection_point").contains(item.type);
                        if(pointType&&!(item.geometry instanceof Point)) throw new IllegalArgumentException(item.id+": ожидается Point");
                        if(item.type.equals("heat_network")&&!(item.geometry instanceof LineString)) throw new IllegalArgumentException(item.id+": ожидается LineString");
                        if((item.type.equals("oks_existing")||item.type.equals("oks_future"))&&!(item.geometry instanceof Polygon||item.geometry instanceof MultiPolygon)) throw new IllegalArgumentException(item.id+": ожидается полигон");
                        data.items.add(item);data.byId.put(item.id,item);
                        if(item.type.equals("heat_network")) {
                            data.networks.add(item);catalog.forDiameter(item.diameter());
                        }
                        if(item.type.equals("heat_chamber")) {
                            data.chambers.add(item);
                        }
                        if(item.type.equals("restriction")) {
                            if(!catalog.raw.path("restrictions").has(item.restriction))
                                data.warnings.add("Для "+item.restriction+" нет правила: принят консервативный запрет пересечения с отступом 1 м.");
                        }
                    }
                } else parser.skipChildren();
            }
            if(parser.nextToken()!=null) throw new IllegalArgumentException("Лишние данные после FeatureCollection");
        }
        if(!featuresFound||!"FeatureCollection".equals(rootType)) throw new IllegalArgumentException("Ожидается GeoJSON FeatureCollection");
        if(data.bounds.getWidth()>20000||data.bounds.getHeight()>20000) data.errors.add("MVP работает с территориями до 20 × 20 км.");
        long sources=data.items.stream().filter(i->i.type.equals("source")).count();
        if(sources!=1) problem(data,"В наборе должен быть ровно один источник.");
        for(Dataset.Item item:data.items) if(item.type.equals("oks_connection_point")) {
            if(!validFlow(item.properties.path("flow_tph"))) {data.errors.add("Нет расхода точки "+item.id);continue;}
            double flow=item.flow();catalog.forFlow(flow);
            data.connections.add(new Dataset.Connection(item.id,item.id,item.geometry.getCoordinate(),flow));
        }
        if(data.networks.isEmpty()) data.errors.add("Нет существующей тепловой сети.");
        if(data.connections.isEmpty()) data.errors.add("Нет точек подключения с известным расходом.");
        if(data.connections.size()>100) data.errors.add("MVP рассчитывает до 100 потребителей.");
        return data;
    }
    private boolean validFlow(JsonNode value) {return value.isNumber()&&Double.isFinite(value.asDouble())&&value.asDouble()>=0;}
}

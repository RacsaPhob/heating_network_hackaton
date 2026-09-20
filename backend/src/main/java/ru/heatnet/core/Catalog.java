package ru.heatnet.core;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class Catalog {
    public final JsonNode raw;
    public final List<Pipe> pipes=new ArrayList<>();
    public static class Pipe {
        public final int diameter;
        public final double capacity,maxLength,cost,reconstruction,width;
        Pipe(JsonNode node) {
            diameter=node.path("diameter_mm").asInt(); capacity=node.path("capacity_tph").asDouble();
            maxLength=node.path("max_continuous_length_m").asDouble(); cost=node.path("construction_rub_per_m").asDouble();
            reconstruction=node.path("reconstruction_rub_per_m").asDouble(); width=node.path("pair_width_m").asDouble();
        }
    }
    public Catalog(ObjectMapper mapper) throws IOException {
        try(InputStream input=new ClassPathResource("contracts/rules.json").getInputStream()) {raw=mapper.readTree(input);}
        for(JsonNode node:raw.path("diameters")) pipes.add(new Pipe(node));
    }
    public Pipe forFlow(double flow) {
        if(!Double.isFinite(flow)||flow<0) throw new IllegalArgumentException("Расход должен быть конечным и неотрицательным");
        for(Pipe pipe:pipes) if(pipe.capacity>=flow) return pipe;
        throw new IllegalArgumentException("Расход превышает таблицу диаметров");
    }
    public Pipe forDiameter(int diameter) {
        return pipes.stream().filter(p->p.diameter==diameter).findFirst().orElseThrow(()->new IllegalArgumentException("Неизвестный ДУ "+diameter));
    }
    public double chamber(int diameter) {
        for(JsonNode band:raw.path("chambers")) if(diameter>=band.path("min_diameter_mm").asInt() && diameter<=band.path("max_diameter_mm").asInt()) return band.path("cost_rub").asDouble();
        throw new IllegalArgumentException("Неизвестный ДУ камеры");
    }
    public double penalty(double flow) {return 100000000+500000*flow;}
    public double score(double cost,double length) {return .7*(cost/25000000)+.3*(length/100);}
}

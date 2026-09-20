package ru.heatnet.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.locationtech.jts.geom.*;

public class Dataset {
    public final List<Item> items=new ArrayList<>();
    public final List<Connection> connections=new ArrayList<>();
    public final List<Item> networks=new ArrayList<>();
    public final List<Item> chambers=new ArrayList<>();
    public final Map<String,Item> byId=new LinkedHashMap<>();
    public final List<String> errors=new ArrayList<>();
    public final List<String> warnings=new ArrayList<>();
    public boolean demo;
    public boolean reconstructionKnown=true;
    public Envelope bounds=new Envelope();
    public static class Item {
        public String id,type,restriction;
        public Geometry geometry;
        public JsonNode properties,raw;
        public double flow() {return properties.path("flow_tph").asDouble();}
        public int diameter() {return properties.path("diameter").asInt();}
    }
    public static class Connection {
        public String id,oksId;
        public Coordinate point;
        public double flow;
        public Connection(String id,String oksId,Coordinate point,double flow) {
            this.id=id;this.oksId=oksId;this.point=point;this.flow=flow;
        }
    }
}

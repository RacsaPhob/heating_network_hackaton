package ru.heatnet.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.proj4j.*;

/** All engine geometry is in EPSG:32637. GeoJSON is longitude/latitude. */
public final class Geo {
    public static final GeometryFactory GF = new GeometryFactory();
    private static final CRSFactory CRS = new CRSFactory();
    private static final CoordinateReferenceSystem WGS = CRS.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs");
    private static final CoordinateReferenceSystem UTM = CRS.createFromParameters("UTM37", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs");
    private static final CoordinateTransform FORWARD = new CoordinateTransformFactory().createTransform(WGS, UTM);
    private static final CoordinateTransform INVERSE = new CoordinateTransformFactory().createTransform(UTM, WGS);
    private Geo() {}

    public static Coordinate project(double lon, double lat) {
        if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon < -180 || lon > 180 || lat < -80 || lat > 84)
            throw new IllegalArgumentException("Координаты вне допустимого диапазона WGS84/UTM");
        ProjCoordinate out = new ProjCoordinate();
        FORWARD.transform(new ProjCoordinate(lon, lat), out);
        return new Coordinate(out.x, out.y);
    }
    public static List<Double> unproject(Coordinate point) {
        ProjCoordinate out = new ProjCoordinate();
        INVERSE.transform(new ProjCoordinate(point.x, point.y), out);
        return Arrays.asList(out.x, out.y);
    }
    private static Coordinate coordinate(JsonNode node) {
        if (!node.isArray() || node.size() < 2 || !node.get(0).isNumber() || !node.get(1).isNumber())
            throw new IllegalArgumentException("Некорректная координата");
        return project(node.get(0).asDouble(), node.get(1).asDouble());
    }
    private static Coordinate[] coordinates(JsonNode array) {
        if (!array.isArray()) throw new IllegalArgumentException("Ожидается массив координат");
        Coordinate[] coords = new Coordinate[array.size()];
        for (int i=0;i<coords.length;i++) coords[i]=coordinate(array.get(i));
        return coords;
    }
    private static Polygon polygon(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) throw new IllegalArgumentException("Пустой полигон");
        LinearRing shell=GF.createLinearRing(coordinates(rings.get(0)));
        LinearRing[] holes=new LinearRing[rings.size()-1];
        for(int i=1;i<rings.size();i++) holes[i-1]=GF.createLinearRing(coordinates(rings.get(i)));
        return GF.createPolygon(shell,holes);
    }
    public static Geometry read(JsonNode geometry) {
        JsonNode c=geometry.path("coordinates");
        Geometry result;
        switch(geometry.path("type").asText()) {
            case "Point": result=GF.createPoint(coordinate(c)); break;
            case "LineString": result=GF.createLineString(coordinates(c)); break;
            case "Polygon": result=polygon(c); break;
            case "MultiPolygon":
                Polygon[] ps=new Polygon[c.size()];
                for(int i=0;i<ps.length;i++) ps[i]=polygon(c.get(i));
                result=GF.createMultiPolygon(ps); break;
            case "MultiLineString":
                LineString[] ls=new LineString[c.size()];
                for(int i=0;i<ls.length;i++) ls[i]=GF.createLineString(coordinates(c.get(i)));
                result=GF.createMultiLineString(ls); break;
            default: throw new IllegalArgumentException("Неподдерживаемая геометрия: "+geometry.path("type").asText());
        }
        if(result.isEmpty() || !result.isValid()) throw new IllegalArgumentException("Пустая или невалидная геометрия");
        return result;
    }
    public static LineString line(Coordinate a, Coordinate b) { return GF.createLineString(new Coordinate[]{a,b}); }
    public static Map<String,Object> point(Coordinate coordinate) { return map("type","Point","coordinates",unproject(coordinate)); }
    public static Map<String,Object> lineJson(List<Coordinate> coordinates) {
        List<List<Double>> values=new ArrayList<>();
        for(Coordinate c:coordinates) values.add(unproject(c));
        return map("type","LineString","coordinates",values);
    }
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]);
        return out;
    }
    public static Map<String,Object> feature(Object geometry, Map<String,Object> properties) {
        return map("type","Feature","geometry",geometry,"properties",properties);
    }
}

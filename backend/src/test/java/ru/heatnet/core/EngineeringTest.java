package ru.heatnet.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.locationtech.jts.geom.Coordinate;
import static org.junit.jupiter.api.Assertions.*;

class EngineeringTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private Catalog catalog;
    @BeforeEach void setup() throws Exception {catalog=new Catalog(mapper);}
    @Test void capacityBoundariesAndInvalidFlows() {
        assertEquals(50,catalog.forFlow(3.5).diameter);
        assertEquals(65,catalog.forFlow(3.5001).diameter);
        assertEquals(1400,catalog.forFlow(22501.9).diameter);
        assertThrows(IllegalArgumentException.class,()->catalog.forFlow(22502));
        assertThrows(IllegalArgumentException.class,()->catalog.forFlow(Double.NaN));
        assertThrows(IllegalArgumentException.class,()->catalog.forFlow(-1));
    }
    @Test void projectionUsesMetersAndRoundTrips() {
        Coordinate projected=Geo.project(37.64,55.70);
        List<Double> wgs=Geo.unproject(projected);
        assertEquals(37.64,wgs.get(0),1e-8);assertEquals(55.70,wgs.get(1),1e-8);
        assertTrue(projected.x>300000&&projected.x<500000);
        assertTrue(projected.y>6000000&&projected.y<6300000);
        assertTrue(projected.distance(Geo.project(37.641,55.70))>60);
    }
    @Test void twoNewChambersIncludeConnectionButDoNotReconstructOldNetwork() {
        Dataset data=new Dataset();
        Dataset.Item source=item("source","source",Geo.GF.createPoint(new Coordinate(0,0)),Geo.map());
        Dataset.Item network=item("network","heat_network",Geo.line(new Coordinate(0,0),new Coordinate(1000,0)),
            Geo.map("diameter",50,"flow_tph",1,"upstream_object_id","source"));
        data.byId.put(source.id,source);data.byId.put(network.id,network);data.networks.add(network);
        Router.Candidate candidate=new Router.Candidate();candidate.name="Test";
        candidate.roots.add(root(network,200,10));candidate.roots.add(root(network,800,20));
        Engineering.Evaluated result=new Engineering(catalog).evaluate(data,candidate,"1");
        List<Map<String,Object>> chambers=new ArrayList<>();
        for(Map<String,Object> feature:result.features) {
            Map<String,Object> props=(Map<String,Object>)feature.get("properties");
            if(props.get("object_type").equals("heat_chamber")) chambers.add(props);
            assertNotEquals("heat_network_reconstruction",props.get("object_type"));
        }
        assertEquals(2,chambers.size());
        assertEquals(6000000d,(Double)result.summary.get("construction_cost"),1e-6);
        assertEquals(6000000d,(Double)result.summary.get("chamber_construction_cost"),1e-6);
        assertEquals(0,(Integer)result.summary.get("existing_chamber_tie_in_count"));
    }
    @Test void nodeDoesNotResetContinuousDiameterLength() {
        Dataset data=new Dataset();data.reconstructionKnown=false;
        Dataset.Item network=item("network","heat_network",Geo.line(new Coordinate(0,-100),new Coordinate(0,0)),Geo.map("diameter",100));data.networks.add(network);
        Router.Node root=root(network,0,3);
        Router.Node node=new Router.Node("middle",new Coordinate(100,0));node.flow=3;
        Router.Node end=new Router.Node("end",new Coordinate(200,0));end.flow=3;
        end.connection=new Dataset.Connection("cp","oks",end.point,3);
        Router.Candidate candidate=new Router.Candidate();candidate.roots.add(root);candidate.edges.add(edge(root,node));candidate.edges.add(edge(node,end));
        Engineering.Evaluated result=new Engineering(catalog).evaluate(data,candidate,"1");
        assertTrue(result.violations.stream().anyMatch(v->v.contains("181")));
    }
    private Router.Edge edge(Router.Node a,Router.Node b) {
        Router.Edge edge=new Router.Edge();edge.from=a;edge.to=b;edge.pipe=catalog.forFlow(3);edge.coordinates=Arrays.asList(a.point,b.point);edge.length=a.point.distance(b.point);return edge;
    }
    private Dataset.Item item(String id,String type,org.locationtech.jts.geom.Geometry geometry,Map<String,Object> properties) {
        Dataset.Item item=new Dataset.Item();item.id=id;item.type=type;item.geometry=geometry;item.properties=mapper.valueToTree(properties);return item;
    }
    private Router.Node root(Dataset.Item existing,double position,double flow) {
        Router.Node node=new Router.Node("tie-"+position,new Coordinate(position,0));node.flow=flow;
        Router.Target target=new Router.Target();target.id=node.id;target.existing=existing;target.point=node.point;node.target=target;return node;
    }
}

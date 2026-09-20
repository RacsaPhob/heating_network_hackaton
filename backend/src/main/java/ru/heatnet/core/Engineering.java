package ru.heatnet.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;

@Component
public class Engineering {
    private final Catalog catalog;
    public Engineering(Catalog catalog) {this.catalog=catalog;}
    public static class Evaluated {
        public List<Map<String,Object>> features=new ArrayList<>();
        public Map<String,Object> summary;
        public Map<String,Object> display;
        public List<String> violations=new ArrayList<>();
    }
    private static class Interval {
        double from,to,flow;
        Interval(double from,double to,double flow) {this.from=Math.min(from,to);this.to=Math.max(from,to);this.flow=flow;}
    }
    private static double money(double value) {return BigDecimal.valueOf(value).setScale(2,RoundingMode.HALF_UP).doubleValue();}
    private static double price(double length,double rate,double coefficient) {
        return BigDecimal.valueOf(length).multiply(BigDecimal.valueOf(rate)).multiply(BigDecimal.valueOf(coefficient)).doubleValue();
    }
    private String nodeId(String prefix,Router.Node node) {return node.connection!=null?node.connection.id:prefix+node.id;}
    public Evaluated evaluate(Dataset data,Router.Candidate candidate,String variantId) {
        Evaluated out=new Evaluated();String prefix="v"+variantId+"-";
        double construction=0,chambers=0,tieIns=0,reconstruction=0,chamberReconstruction=0,length=0,reconstructionLength=0;
        Set<String> emitted=new HashSet<>();
        Map<Router.Node,List<Router.Edge>> outgoing=new HashMap<>();
        Map<Router.Node,Router.Edge> incoming=new HashMap<>();
        for(Router.Edge edge:candidate.edges) {outgoing.computeIfAbsent(edge.from,k->new ArrayList<>()).add(edge);incoming.put(edge.to,edge);}
        for(Router.Node root:candidate.roots) checkLength(root,0,-1,outgoing,out.violations);
        checkCrossings(candidate.edges,out.violations);
        Map<String,List<Interval>> added=new LinkedHashMap<>();
        Map<String,Integer> reconstructedDiameters=new HashMap<>();
        if(data.reconstructionKnown) {
            for(Router.Node root:candidate.roots) addUpstream(data,root,added);
            int n=0;
            for(Map.Entry<String,List<Interval>> entry:added.entrySet()) {
                Dataset.Item existing=data.byId.get(entry.getKey());
                TreeSet<Double> cuts=new TreeSet<>();
                for(Interval interval:entry.getValue()) {cuts.add(interval.from);cuts.add(interval.to);}
                List<Double> sorted=new ArrayList<>(cuts);LengthIndexedLine line=new LengthIndexedLine(existing.geometry);
                for(int i=1;i<sorted.size();i++) {
                    double a=sorted.get(i-1),b=sorted.get(i),mid=(a+b)/2;
                    double flow=entry.getValue().stream().filter(v->mid>v.from-1e-6&&mid<v.to+1e-6).mapToDouble(v->v.flow).sum();
                    if(flow<=0||b-a<1e-6) continue;
                    Catalog.Pipe required=catalog.forFlow(existing.flow()+flow);
                    if(required.diameter<=existing.diameter()) continue;
                    Geometry part=line.extractLine(a,b);double partLength=part.getLength();
                    double cost=price(partLength,required.reconstruction,1);reconstruction+=cost;reconstructionLength+=partLength;
                    reconstructedDiameters.merge(existing.id,required.diameter,Math::max);
                    out.features.add(Geo.feature(Geo.lineJson(Arrays.asList(part.getCoordinates())),Geo.map(
                        "id",prefix+"recon-"+(++n),"object_type","heat_network_reconstruction","variant_id",variantId,
                        "existing_object_id",existing.id,"existing_flow_tph",existing.flow(),"added_flow_tph",flow,
                        "calculated_flow_tph",existing.flow()+flow,"existing_diameter",existing.diameter(),
                        "required_diameter",required.diameter,"length",partLength,"cost",cost)));
                }
            }
        }
        for(Router.Node root:candidate.roots) {
            Dataset.Item existing=root.target.existing;
            int required=catalog.forFlow(root.flow).diameter;
            int original=existing.diameter();
            if(original==0) original=data.networks.stream().filter(n->n.geometry.distance(existing.geometry)<1).mapToInt(Dataset.Item::diameter).max().orElse(required);
            int maxDiameter=Math.max(original,required);
            if(existing.type.equals("heat_network")) maxDiameter=Math.max(maxDiameter,reconstructedDiameters.getOrDefault(existing.id,0));
            else for(Dataset.Item network:data.networks) if(network.geometry.distance(existing.geometry)<1) maxDiameter=Math.max(maxDiameter,reconstructedDiameters.getOrDefault(network.id,network.diameter()));
            tieIns+=5000000;
            out.features.add(Geo.feature(Geo.point(root.point),Geo.map("id",nodeId(prefix,root),"object_type","tie_in","variant_id",variantId,
                "existing_object_id",existing.id,"existing_object_type",existing.type,"existing_diameter",original,
                "required_diameter",required,"cost",5000000)));
            if(existing.type.equals("heat_network")) {
                double cost=catalog.chamber(maxDiameter);chambers+=cost;
                out.features.add(Geo.feature(Geo.point(root.point),Geo.map("id",prefix+root.id+"-chamber","object_type","heat_chamber","variant_id",variantId,"diameter",maxDiameter,"cost",cost)));
            } else if(data.reconstructionKnown&&maxDiameter>original) {
                double cost=catalog.chamber(maxDiameter);chamberReconstruction+=cost;
                out.features.add(Geo.feature(Geo.point(root.point),Geo.map("id",prefix+root.id+"-reconstruction","object_type","heat_chamber_reconstruction","variant_id",variantId,
                    "existing_object_id",existing.id,"existing_diameter",original,"required_diameter",maxDiameter,"cost",cost)));
            }
        }
        int edgeNumber=0;
        for(Router.Edge edge:candidate.edges) {
            length+=edge.length;
            LineString line=Geo.GF.createLineString(edge.coordinates.toArray(new Coordinate[0]));
            LengthIndexedLine indexed=new LengthIndexedLine(line);
            List<Interval> special=new ArrayList<>();
            for(Dataset.Item network:data.networks) {
                Geometry crossing=line.intersection(network.geometry);
                if(crossing.isEmpty()) continue;
                if(crossing.getDimension()>0) {out.violations.add("Новая трасса совпадает с существующей линией на участке.");continue;}
                for(Coordinate point:crossing.getCoordinates()) {
                    if(edge.from.target!=null&&point.distance(edge.from.point)<.05) continue;
                    double index=indexed.project(point);
                    special.add(new Interval(Math.max(0,index-2),Math.min(edge.length,index+2),0));
                }
            }
            TreeSet<Double> cuts=new TreeSet<>();cuts.add(0d);cuts.add(edge.length);
            for(Interval interval:special) {cuts.add(interval.from);cuts.add(interval.to);}
            List<Double> values=new ArrayList<>(cuts);String previous=nodeId(prefix,edge.from);
            for(int i=1;i<values.size();i++) {
                double a=values.get(i-1),b=values.get(i),mid=(a+b)/2;
                if(b-a<1e-6) continue;
                boolean crossing=special.stream().anyMatch(v->mid>=v.from&&mid<=v.to);
                Geometry part=indexed.extractLine(a,b);
                String end=i==values.size()-1?nodeId(prefix,edge.to):prefix+"special-node-"+edgeNumber+"-"+i;
                if(i<values.size()-1) out.features.add(Geo.feature(Geo.point(indexed.extractPoint(b)),Geo.map("id",end,"object_type","technical_node","variant_id",variantId)));
                double cost=price(part.getLength(),edge.pipe.cost,crossing?1.05:1);construction+=cost;
                out.features.add(Geo.feature(Geo.lineJson(Arrays.asList(part.getCoordinates())),Geo.map(
                    "id",prefix+"edge-"+edgeNumber+"-"+i,"object_type","heat_network","variant_id",variantId,
                    "start_node_id",previous,"end_node_id",end,"flow_tph",edge.to.flow,"diameter",edge.pipe.diameter,
                    "length",part.getLength(),"laying_method",crossing?"special":"base","depth_start",null,"depth_end",null,"cost",cost)));
                previous=end;
            }
            edgeNumber++;
            Router.Node node=edge.to;
            if(node.connection==null&&emitted.add(node.id)) {
                List<Router.Edge> children=outgoing.getOrDefault(node,Collections.emptyList());
                int degree=children.size()+1;
                int diameter=edge.pipe.diameter;
                for(Router.Edge child:children) diameter=Math.max(diameter,child.pipe.diameter);
                if(degree>=3) {
                    double cost=catalog.chamber(diameter);chambers+=cost;
                    out.features.add(Geo.feature(Geo.point(node.point),Geo.map("id",nodeId(prefix,node),"object_type","heat_chamber","variant_id",variantId,"diameter",diameter,"cost",cost)));
                } else out.features.add(Geo.feature(Geo.point(node.point),Geo.map("id",nodeId(prefix,node),"object_type","technical_node","variant_id",variantId)));
            }
        }
        List<String> unconnected=new ArrayList<>(new LinkedHashSet<>(candidate.unconnected));double penalty=0;
        for(Dataset.Connection c:data.connections) if(unconnected.contains(c.oksId)) penalty+=catalog.penalty(c.flow);
        double totalCost=construction+chambers+tieIns+reconstruction+chamberReconstruction+penalty;
        double totalLength=length+reconstructionLength;
        out.summary=Geo.map("id",prefix+"summary","object_type","variant_summary","variant_id",variantId,"rank",0,
            "construction_cost",construction,"chamber_construction_cost",chambers,"tie_in_cost",tieIns,
            "reconstruction_cost",reconstruction,"chamber_reconstruction_cost",chamberReconstruction,"unconnected_penalty",penalty,
            "calculated_cost",totalCost,"new_network_length",length,"reconstruction_length",reconstructionLength,
            "length",totalLength,"score",catalog.score(totalCost,totalLength),"unconnected_oks_ids",unconnected);
        out.features.add(Geo.feature(null,out.summary));
        out.display=Geo.map("variant_id",variantId,"name",candidate.name,"summary",out.summary,
            "connected_count",data.connections.size()-unconnected.size(),"total_count",data.connections.size(),
            "tie_in_count",candidate.roots.size(),"cost_complete",data.reconstructionKnown,
            "checks_passed",out.violations.isEmpty(),"violations",out.violations,
            "grid_size_m",candidate.gridSize,"notes",new ArrayList<>(new LinkedHashSet<>(candidate.notes)),
            "unconnected_reasons",candidate.unconnectedReasons,
            "score_cost_component",.7*totalCost/25000000,"score_length_component",.3*totalLength/100,
            "works_cost",construction+chambers+tieIns+reconstruction+chamberReconstruction);
        return out;
    }
    private void checkLength(Router.Node node,double previousLength,int previousDiameter,Map<Router.Node,List<Router.Edge>> outgoing,List<String> violations) {
        for(Router.Edge edge:outgoing.getOrDefault(node,Collections.emptyList())) {
            double continuous=(previousDiameter==edge.pipe.diameter?previousLength:0)+edge.length;
            if(continuous>edge.pipe.maxLength+1e-6) violations.add("ДУ "+edge.pipe.diameter+": непрерывная длина "+Math.round(continuous)+" м превышает "+Math.round(edge.pipe.maxLength)+" м.");
            checkLength(edge.to,continuous,edge.pipe.diameter,outgoing,violations);
        }
    }
    private void checkCrossings(List<Router.Edge> edges,List<String> violations) {
        for(int i=0;i<edges.size();i++) for(int j=i+1;j<edges.size();j++) {
            Router.Edge a=edges.get(i),b=edges.get(j);
            Geometry intersection=Geo.GF.createLineString(a.coordinates.toArray(new Coordinate[0])).intersection(Geo.GF.createLineString(b.coordinates.toArray(new Coordinate[0])));
            if(intersection.isEmpty()) continue;
            boolean bad=intersection.getDimension()>0;
            for(Coordinate point:intersection.getCoordinates()) {
                boolean shared=(a.from==b.from&&point.distance(a.from.point)<.05)||(a.to==b.from&&point.distance(a.to.point)<.05)||
                    (a.from==b.to&&point.distance(a.from.point)<.05)||(a.to==b.to&&point.distance(a.to.point)<.05);
                if(!shared) bad=true;
            }
            if(bad) {violations.add("Пересечение новых ветвей вне общего узла: требуется доработка трассы.");return;}
        }
    }
    private void addUpstream(Dataset data,Router.Node root,Map<String,List<Interval>> events) {
        Dataset.Item item=root.target.existing;boolean first=true;Set<String> seen=new HashSet<>();
        while(item!=null&&!item.type.equals("source")) {
            if(!seen.add(item.id)) throw new IllegalArgumentException("Цикл upstream");
            Dataset.Item upstream=data.byId.get(item.properties.path("upstream_object_id").asText());
            if(upstream==null) throw new IllegalArgumentException("Потеряна upstream-связь");
            if(item.type.equals("heat_network")) {
                double length=item.geometry.getLength(),from=0,to=length;
                if(first) {
                    Coordinate[] coordinates=item.geometry.getCoordinates();
                    double firstDistance=upstream.geometry.distance(Geo.GF.createPoint(coordinates[0]));
                    double lastDistance=upstream.geometry.distance(Geo.GF.createPoint(coordinates[coordinates.length-1]));
                    if(Math.abs(firstDistance-lastDistance)<.01) throw new IllegalArgumentException("Неоднозначное направление существующей линии "+item.id);
                    double index=new LengthIndexedLine(item.geometry).project(root.point);
                    if(firstDistance<lastDistance) to=index;else from=index;
                }
                events.computeIfAbsent(item.id,key->new ArrayList<>()).add(new Interval(from,to,root.flow));
            }
            first=false;item=upstream;
        }
    }
}

package ru.heatnet.core;

import java.util.*;
import java.util.function.Consumer;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.*;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;

/** Multi-source shortest-path forest. No coordinates or IDs of the sample are fixed here. */
@Component
public class Router {
    private final Catalog catalog;
    public Router(Catalog catalog) {this.catalog=catalog;}
    public static class Target {
        public String id;
        public Dataset.Item existing;
        public Coordinate point;
        public int grid;
    }
    public static class Node {
        public String id;
        public Coordinate point;
        public Dataset.Connection connection;
        public Target target;
        public Node parent;
        public List<Node> children=new ArrayList<>();
        public double flow;
        public Node(String id,Coordinate point) {this.id=id;this.point=point;}
    }
    public static class Edge {
        public Node from,to;
        public List<Coordinate> coordinates;
        public List<Coordinate> originalCoordinates;
        public Catalog.Pipe pipe;
        public double length;
    }
    public static class Candidate {
        public List<Node> roots=new ArrayList<>();
        public List<Edge> edges=new ArrayList<>();
        public List<String> unconnected=new ArrayList<>();
        public List<String> notes=new ArrayList<>();
        public Map<String,String> unconnectedReasons=new LinkedHashMap<>();
        public String name;
        public double gridSize;
    }
    private static class Obstacle {
        Geometry geometry;
        PreparedGeometry prepared;
        Obstacle(Geometry geometry) {this.geometry=geometry;this.prepared=PreparedGeometryFactory.prepare(geometry);}
    }
    private static class Space {
        STRtree index=new STRtree();
        Map<String,Coordinate> exits=new HashMap<>();
        boolean clear(Coordinate a,Coordinate b) {
            Geometry line=Geo.line(a,b);
            for(Object value:index.query(line.getEnvelopeInternal())) if(((Obstacle)value).prepared.intersects(line)) return false;
            return true;
        }
        boolean free(Coordinate coordinate) {
            Geometry point=Geo.GF.createPoint(coordinate);
            for(Object value:index.query(point.getEnvelopeInternal())) if(((Obstacle)value).prepared.intersects(point)) return false;
            return true;
        }
    }
    private static class QueueNode implements Comparable<QueueNode> {
        int index; double distance;
        QueueNode(int index,double distance) {this.index=index;this.distance=distance;}
        @Override public int compareTo(QueueNode other) {int c=Double.compare(distance,other.distance);return c==0?Integer.compare(index,other.index):c;}
    }
    private Space space(Dataset data,Catalog.Pipe maxPipe,List<String> notes) {
        Space space=new Space();
        double buildingGap=maxPipe.diameter<500?5:maxPipe.diameter<=800?7:9;
        for(Dataset.Item item:data.items) {
            boolean building=item.type.equals("oks_existing")||item.type.equals("oks_future")||item.restriction.equals("oks");
            if(!building&&!item.type.equals("restriction")) continue;
            double gap=building?buildingGap:catalog.raw.path("restrictions").path(item.restriction).path("clearance_m").asDouble(1);
            Geometry buffered=item.geometry.buffer(gap+maxPipe.width/2,4);
            // A narrow terminal access corridor is an explicit demo assumption for unlabelled building footprints.
            for(Dataset.Connection c:data.connections) {
                Point point=Geo.GF.createPoint(c.point);
                boolean ownFuture=item.type.equals("oks_future")&&item.id.equals(c.oksId);
                if(building&&buffered.covers(point)&&(data.demo||ownFuture)) {
                    Coordinate border=DistanceOp.nearestPoints(point,buffered.getBoundary())[1];
                    double dx=border.x-c.point.x,dy=border.y-c.point.y,len=Math.hypot(dx,dy);
                    if(len>0) {
                        Coordinate outside=new Coordinate(border.x+dx/len*3,border.y+dy/len*3);
                        buffered=buffered.difference(Geo.line(c.point,outside).buffer(Math.max(1.5,maxPipe.width/2+.5),2));
                        space.exits.put(c.id,outside);
                        notes.add("Подход к точке "+c.id+": выделен узкий коридор через отступ здания"+(ownFuture?".":" (допущение деморежима)."));
                    }
                }
            }
            if(!buffered.isEmpty()) {Obstacle obstacle=new Obstacle(buffered);space.index.insert(buffered.getEnvelopeInternal(),obstacle);}
        }
        space.index.build();
        return space;
    }
    public Candidate route(Dataset data,int variant,Consumer<String> progress) {
        Candidate out=new Candidate();
        out.name=variant==0?"Короткие подключения":variant==1?"Приоритет существующих камер":"Укрупнённые подключения";
        double total=data.connections.stream().mapToDouble(c->c.flow).sum();
        Catalog.Pipe maxPipe=catalog.forFlow(total);
        Space space=space(data,maxPipe,out.notes);
        out.notes.add("MVP обходит дороги и коммуникации как препятствия; оптимизация специальных проходов через них не реализована.");
        Envelope bounds=new Envelope();
        for(Dataset.Connection c:data.connections) bounds.expandToInclude(c.point);
        for(Dataset.Item network:data.networks) bounds.expandToInclude(network.geometry.getEnvelopeInternal());
        bounds.expandBy(130);
        double step=Math.max(10,Math.sqrt(bounds.getArea()/95000));
        out.gridSize=step;
        int width=(int)Math.ceil(bounds.getWidth()/step)+1,height=(int)Math.ceil(bounds.getHeight()/step)+1,size=width*height;
        if(size>150000) throw new IllegalArgumentException("Слишком большая территория для сетки MVP");
        Coordinate[] coordinates=new Coordinate[size];boolean[] free=new boolean[size];
        for(int i=0;i<size;i++) {
            coordinates[i]=new Coordinate(bounds.getMinX()+(i%width)*step,bounds.getMinY()+(i/width)*step);
            free[i]=space.free(coordinates[i]);
        }
        progress.accept("Выбор точек врезки");
        List<Target> targets=new ArrayList<>();
        for(Dataset.Item chamber:data.chambers) {
            long degree=data.networks.stream().filter(n->n.geometry.distance(chamber.geometry)<1.0).count();
            if(degree>=4||degree==0) continue;
            Target t=new Target();t.id="ch-"+chamber.id;t.existing=chamber;t.point=chamber.geometry.getCoordinate();targets.add(t);
        }
        for(Dataset.Item network:data.networks) {
            LengthIndexedLine line=new LengthIndexedLine(network.geometry);double length=network.geometry.getLength();
            int samples=Math.max(1,(int)Math.ceil(length/(variant==2?160:50)));
            for(int n=0;n<=samples;n++) {
                Coordinate point=line.extractPoint(length*n/samples);
                boolean nearChamber=data.chambers.stream().anyMatch(ch->ch.geometry.getCoordinate().distance(point)<=10);
                if(nearChamber) continue;
                Target t=new Target();t.id="net-"+network.id+"-"+n;t.existing=network;t.point=point;targets.add(t);
            }
        }
        double[] distance=new double[size];Arrays.fill(distance,Double.POSITIVE_INFINITY);
        int[] parent=new int[size],root=new int[size];Arrays.fill(parent,-1);Arrays.fill(root,-1);
        PriorityQueue<QueueNode> queue=new PriorityQueue<>();
        for(int k=0;k<targets.size();k++) {
            Target target=targets.get(k);
            int nearest=nearestFree(target.point,coordinates,free,bounds,step,width,height,space,7);
            if(nearest<0) continue;
            target.grid=nearest;
            double penalty=variant==1&&!target.existing.type.equals("heat_chamber")?100:0;
            double cost=target.point.distance(coordinates[nearest])+penalty;
            if(cost<distance[nearest]) {distance[nearest]=cost;root[nearest]=k;queue.add(new QueueNode(nearest,cost));}
        }
        progress.accept("Поиск путей вокруг препятствий");
        byte[] right=new byte[size],down=new byte[size];
        while(!queue.isEmpty()) {
            QueueNode q=queue.poll();if(q.distance!=distance[q.index]) continue;
            int x=q.index%width,y=q.index/width;
            int[] neighbours={x>0?q.index-1:-1,x+1<width?q.index+1:-1,y>0?q.index-width:-1,y+1<height?q.index+width:-1};
            for(int next:neighbours) {
                if(next<0||!free[next]) continue;
                double cost=q.distance+step;
                if(cost>=distance[next]) continue;
                int lower=Math.min(next,q.index);byte[] cache=Math.abs(next-q.index)==1?right:down;
                if(cache[lower]==0) cache[lower]=(byte)(space.clear(coordinates[q.index],coordinates[next])?1:2);
                if(cache[lower]==2) continue;
                distance[next]=cost;parent[next]=q.index;root[next]=root[q.index];queue.add(new QueueNode(next,cost));
            }
        }
        Map<Integer,Node> used=new HashMap<>();Map<Integer,Node> roots=new LinkedHashMap<>();
        for(Dataset.Connection connection:data.connections) {
            // Anchor a narrow building access corridor explicitly. A coarse grid need not
            // contain a point visible directly from the interior connection point.
            // Keep the original conservative search as the first candidate; extended
            // terminal access is explored by the alternatives and checked separately.
            Coordinate exit=variant==0?connection.point:space.exits.getOrDefault(connection.id,connection.point);
            int grid=space.clear(connection.point,exit)?nearestReached(exit,coordinates,distance,bounds,step,width,height,space,9):-1;
            if(grid<0) {
                out.unconnected.add(connection.oksId);
                out.unconnectedReasons.put(connection.oksId,space.free(exit)?"Не найден доступный путь к сети при текущих отступах и запретах.":"Выход из здания перекрыт другим ограничением.");
                continue;
            }
            Node leaf=new Node("connection-"+connection.id,connection.point);leaf.connection=connection;
            Node previous=leaf;
            if(exit.distance(connection.point)>1e-5) {
                Node portal=new Node("access-"+connection.id,exit);portal.children.add(leaf);leaf.parent=portal;previous=portal;
            }
            int cursor=grid;
            while(cursor>=0) {
                Node node=used.get(cursor);boolean known=node!=null;
                if(!known) {node=new Node("grid-"+cursor,coordinates[cursor]);used.put(cursor,node);}
                previous.parent=node;node.children.add(previous);
                if(known) break;
                if(parent[cursor]<0) {
                    int key=root[cursor];Target target=targets.get(key);
                    Node rootNode=roots.computeIfAbsent(key,r->{Node nodeRoot=new Node("tie-"+target.id,target.point);nodeRoot.target=target;return nodeRoot;});
                    node.parent=rootNode;rootNode.children.add(node);break;
                }
                previous=node;cursor=parent[cursor];
            }
        }
        for(Node node:roots.values()) {
            if(excessDegree(node)) {
                List<String> excluded=new ArrayList<>();collectDisconnected(node,excluded);out.unconnected.addAll(excluded);
                for(String id:excluded) out.unconnectedReasons.put(id,"В найденной группе превышено допустимое число примыканий камеры.");
                out.notes.add("Группа исключена: превышено число примыканий камеры.");continue;
            }
            sumFlow(node);out.roots.add(node);collapse(node,space,out.edges);
        }
        return out;
    }
    private boolean excessDegree(Node node) {
        if(node.children.size()+(node.parent==null?0:1)>4) return true;
        for(Node child:node.children) if(excessDegree(child)) return true;
        return false;
    }
    private void collectDisconnected(Node node,List<String> output) {
        if(node.connection!=null) output.add(node.connection.oksId);
        for(Node child:node.children) collectDisconnected(child,output);
    }
    private double sumFlow(Node node) {
        node.flow=node.connection==null?0:node.connection.flow;
        for(Node child:node.children) node.flow+=sumFlow(child);
        return node.flow;
    }
    private void collapse(Node start,Space space,List<Edge> edges) {
        for(Node first:start.children) {
            Node end=first;List<Coordinate> path=new ArrayList<>();path.add(start.point);path.add(end.point);
            while(end.children.size()==1&&end.connection==null) {end=end.children.get(0);path.add(end.point);}
            List<Coordinate> simplified=new ArrayList<>();int current=0;simplified.add(path.get(0));
            while(current<path.size()-1) {
                int next=path.size()-1;
                while(next>current+1&&!space.clear(path.get(current),path.get(next))) next--;
                if(path.get(next).distance(simplified.get(simplified.size()-1))>1e-5) simplified.add(path.get(next));
                current=next;
            }
            if(simplified.size()==1) simplified.add(new Coordinate(simplified.get(0)));
            Edge edge=new Edge();edge.from=start;edge.to=end;edge.coordinates=simplified;edge.originalCoordinates=path;edge.pipe=catalog.forFlow(end.flow);
            edge.length=Geo.GF.createLineString(simplified.toArray(new Coordinate[0])).getLength();edges.add(edge);
            collapse(end,space,edges);
        }
    }
    private int nearestFree(Coordinate point,Coordinate[] coordinates,boolean[] free,Envelope bounds,double step,int width,int height,Space space,int radius) {
        // Only a local square is considered; no per-target full geometry scan.
        int cx=(int)Math.round((point.x-bounds.getMinX())/step),cy=(int)Math.round((point.y-bounds.getMinY())/step);
        int best=-1;double distance=Double.POSITIVE_INFINITY;
        for(int y=Math.max(0,cy-radius);y<=Math.min(height-1,cy+radius);y++) for(int x=Math.max(0,cx-radius);x<=Math.min(width-1,cx+radius);x++) {
            int i=y*width+x;double d=point.distance(coordinates[i]);
            if(free[i]&&d<distance&&space.clear(point,coordinates[i])) {best=i;distance=d;}
        }
        return best;
    }
    private int nearestReached(Coordinate point,Coordinate[] coordinates,double[] distances,Envelope bounds,double step,int width,int height,Space space,int radius) {
        int cx=(int)Math.round((point.x-bounds.getMinX())/step),cy=(int)Math.round((point.y-bounds.getMinY())/step);
        int best=-1;double cost=Double.POSITIVE_INFINITY;
        for(int y=Math.max(0,cy-radius);y<=Math.min(height-1,cy+radius);y++) for(int x=Math.max(0,cx-radius);x<=Math.min(width-1,cx+radius);x++) {
            int i=y*width+x;double d=point.distance(coordinates[i])+distances[i];
            if(d<cost&&space.clear(point,coordinates[i])) {best=i;cost=d;}
        }
        return best;
    }
}

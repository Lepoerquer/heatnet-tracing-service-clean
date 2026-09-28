package ru.heatnet.qa;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import ru.heatnet.HeatnetApplication;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.ingest.IngestService;
import ru.heatnet.routing.RouteFinder;
import ru.heatnet.routing.RouteFinderFactory;
import ru.heatnet.routing.RouteResult;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintBundle;
import ru.heatnet.rules.model.RestrictionFeature;

/**
 * QA (роль 4): синтетические бенчмарки. Запуск: java -cp ... ru.heatnet.qa.QaBenchSuite ingest|routing|snap|engine args...
 */
public final class QaBenchSuite {

    private QaBenchSuite() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HeatnetApplication.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=local");
        try {
            switch (mode) {
                case "ingest":
                    benchIngest(ctx, Integer.parseInt(args[1]));
                    break;
                case "routing":
                    benchRouting(ctx, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                    break;
                case "engine":
                    benchEngine(ctx, Integer.parseInt(args[1]));
                    break;
                case "snap":
                    benchSnap(ctx, Integer.parseInt(args[1]));
                    break;
                default:
                    System.out.println("unknown mode " + mode);
            }
        } finally {
            ctx.close();
        }
    }

    // --------------------------------------------------------------------------------- ingest

    private static void benchIngest(ConfigurableApplicationContext ctx, int targetMb) throws Exception {
        IngestService ingestService = ctx.getBean(IngestService.class);
        Path f = Files.createTempFile("qa-ingest-", ".geojson");
        long features = writeRealisticGeoJson(f, targetMb);
        long bytes = Files.size(f);
        System.gc();
        long base = usedMb();
        final AtomicLong peak = new AtomicLong(base);
        Thread sampler = new Thread(() -> {
            try {
                while (true) {
                    peak.accumulateAndGet(usedMb(), Math::max);
                    Thread.sleep(50);
                }
            } catch (InterruptedException ignored) {
                // stop
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long t = System.nanoTime();
        String outcome = "OK";
        IngestResult r = null;
        try (InputStream in = Files.newInputStream(f)) {
            r = ingestService.ingest(in);
        } catch (OutOfMemoryError oom) {
            outcome = "OOM";
        }
        double sec = (System.nanoTime() - t) / 1e9;
        sampler.interrupt();
        long retained = -1;
        if (r != null) {
            System.gc();
            retained = usedMb() - base;
        }
        System.out.printf(Locale.ROOT,
                "BENCH-INGEST file=%.0fMB features=%d outcome=%s time=%.1fs throughput=%.1fMB/s peakHeap=%dMB retainedAfterGc=%dMB maxHeap=%dMB%n",
                bytes / 1048576.0, features, outcome, sec, bytes / 1048576.0 / sec, peak.get(), retained,
                Runtime.getRuntime().maxMemory() / 1048576);
        Files.deleteIfExists(f);
    }

    private static long usedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1048576;
    }

    /** Реалистичная плотность: ограничения-полигоны по 12 вершин с 6-значными координатами и атрибутами. */
    private static long writeRealisticGeoJson(Path target, int targetMb) throws Exception {
        long targetBytes = targetMb * 1024L * 1024L;
        Random rnd = new Random(42);
        long written = 0;
        long n = 0;
        try (BufferedWriter w = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            String head = "{\"type\":\"FeatureCollection\",\"features\":["
                    + "{\"type\":\"Feature\",\"properties\":{\"id\":\"src\",\"object_type\":\"source\"},"
                    + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.62,55.70]}}";
            w.write(head);
            written += head.length();
            String[] types = {"park", "water", "social_area", "prohibited_site", "oks", "road", "tram_tracks"};
            while (written < targetBytes) {
                n++;
                double cx = 37.50 + rnd.nextDouble() * 0.2;
                double cy = 55.60 + rnd.nextDouble() * 0.2;
                StringBuilder sb = new StringBuilder(600);
                sb.append(",{\"type\":\"Feature\",\"properties\":{\"id\":").append(n)
                        .append(",\"object_type\":\"restriction\",\"restriction_type\":\"")
                        .append(types[(int) (n % types.length)]).append("\",\"address\":\"Тестовая улица, дом ")
                        .append(n % 500).append(", строение ").append(n % 20)
                        .append("\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[");
                int verts = 12;
                double r = 0.0001 + rnd.nextDouble() * 0.0004;
                for (int i = 0; i < verts; i++) {
                    double a = 2 * Math.PI * i / verts;
                    sb.append(i == 0 ? "" : ",").append('[')
                            .append(String.format(Locale.ROOT, "%.7f", cx + r * Math.cos(a))).append(',')
                            .append(String.format(Locale.ROOT, "%.7f", cy + r * 0.6 * Math.sin(a))).append(']');
                }
                sb.append(",[").append(String.format(Locale.ROOT, "%.7f", cx + r)).append(',')
                        .append(String.format(Locale.ROOT, "%.7f", cy)).append("]]]}}");
                w.write(sb.toString());
                written += sb.length();
            }
            w.write("]}");
        }
        return n + 1;
    }

    // --------------------------------------------------------------------------------- routing / engine

    /** Город из nx*ny зданий 20x20 м с улицами 15 м; маршрут из угла в угол. */
    private static List<RestrictionFeature> cityBlocks(ConfigurableApplicationContext ctx, int nx, int ny) {
        ReferenceData ref = ctx.getBean(ReferenceData.class);
        GeometryFactory gf = ctx.getBean(ProjectionService.class).utmFactory();
        List<RestrictionFeature> list = new ArrayList<>();
        double pitch = 35.0;
        int id = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                double x = 412000 + i * pitch;
                double y = 6175000 + j * pitch;
                org.locationtech.jts.geom.Polygon p = gf.createPolygon(new Coordinate[] {
                        new Coordinate(x, y), new Coordinate(x + 20, y), new Coordinate(x + 20, y + 20),
                        new Coordinate(x, y + 20), new Coordinate(x, y)});
                list.add(new RestrictionFeature("b" + (id++), "oks_existing", ref.getRules().restriction("oks_existing"), p));
            }
        }
        return list;
    }

    private static void benchEngine(ConfigurableApplicationContext ctx, int nBuildings) {
        RestrictionEngineFactory ef = ctx.getBean(RestrictionEngineFactory.class);
        int side = (int) Math.ceil(Math.sqrt(nBuildings));
        List<RestrictionFeature> feats = cityBlocks(ctx, side, side);
        long t = System.nanoTime();
        SpatialConstraintBundle b = ef.createBundle(feats, 200);
        double sec = (System.nanoTime() - t) / 1e9;
        System.out.printf(Locale.ROOT, "BENCH-ENGINE buildings=%d createBundle=%.3fs outlines=%d%n",
                feats.size(), sec, b.getBlockedOutlines().size());
    }

    private static void benchRouting(ConfigurableApplicationContext ctx, int nx, int ny) {
        RestrictionEngineFactory ef = ctx.getBean(RestrictionEngineFactory.class);
        RouteFinderFactory rff = ctx.getBean(RouteFinderFactory.class);
        GeometryFactory gf = ctx.getBean(ProjectionService.class).utmFactory();
        List<RestrictionFeature> feats = cityBlocks(ctx, nx, ny);
        long t0 = System.nanoTime();
        SpatialConstraintBundle bundle = ef.createBundle(feats, 200);
        double build = (System.nanoTime() - t0) / 1e9;
        RouteFinder finder = rff.create(bundle, 200);
        Point from = gf.createPoint(new Coordinate(412000 - 10, 6175000 - 10));
        Point to = gf.createPoint(new Coordinate(412000 + (nx - 1) * 35.0 + 30, 6175000 + (ny - 1) * 35.0 + 30));
        long t = System.nanoTime();
        RouteResult r = finder.findRoute(from, to, 200);
        double sec = (System.nanoTime() - t) / 1e9;
        double straight = from.distance(to);
        double manhattanLb = Math.abs(to.getX() - from.getX()) + Math.abs(to.getY() - from.getY());
        int vertices = r.isFound() ? r.getPathUtm().getNumPoints() : 0;
        System.out.printf(Locale.ROOT,
                "BENCH-ROUTING buildings=%d bundle=%.2fs route=%.2fs status=%s length=%.1f straight=%.1f manhattan=%.1f vertices=%d ratioToStraight=%.3f%n",
                feats.size(), build, sec, r.getStatus(), r.getLengthM(), straight, manhattanLb, vertices,
                r.isFound() ? r.getLengthM() / straight : -1.0);
    }

    // --------------------------------------------------------------------------------- snap (ingest existing network)

    private static void benchSnap(ConfigurableApplicationContext ctx, int nPipes) throws Exception {
        IngestService ingestService = ctx.getBean(IngestService.class);
        Path f = Files.createTempFile("qa-snap-", ".geojson");
        try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write("{\"type\":\"FeatureCollection\",\"features\":[");
            w.write("{\"type\":\"Feature\",\"properties\":{\"id\":\"src\",\"object_type\":\"source\"},"
                    + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.5000,55.6000]}}");
            // цепочка труб вдоль параллели; без upstream_object_id (как в конкурсном наборе)
            double lon = 37.5000;
            double step = 0.0005;
            for (int i = 0; i < nPipes; i++) {
                double row = 55.6000 + (i / 200) * 0.0004; // ломаная змейка не нужна: связность внутри строки
                double a = lon + (i % 200) * step;
                double b = a + step;
                w.write(",{\"type\":\"Feature\",\"properties\":{\"id\":" + (i + 1)
                        + ",\"object_type\":\"heat_network\",\"diameter\":300},\"geometry\":{\"type\":\"LineString\","
                        + "\"coordinates\":[[" + String.format(Locale.ROOT, "%.6f", a) + "," + String.format(Locale.ROOT, "%.6f", row)
                        + "],[" + String.format(Locale.ROOT, "%.6f", b) + "," + String.format(Locale.ROOT, "%.6f", row) + "]]}}");
            }
            w.write("]}");
        }
        long t = System.nanoTime();
        IngestResult r;
        try (InputStream in = Files.newInputStream(f)) {
            r = ingestService.ingest(in);
        }
        double sec = (System.nanoTime() - t) / 1e9;
        System.out.printf(Locale.ROOT, "BENCH-SNAP pipes=%d ingest=%.2fs segments=%d%n", nPipes, sec,
                r.getExistingNetwork().getSegments().size());
        Files.deleteIfExists(f);
    }

    @SuppressWarnings("unused")
    private static LineString unused(GeometryFactory gf) {
        return null;
    }
}

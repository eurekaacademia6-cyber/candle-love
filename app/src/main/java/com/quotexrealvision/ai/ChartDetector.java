package com.quotexrealvision.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Real chart-body detector.
 *
 * It detects the actual saturated candle colors, finds connected candle regions
 * separately for green and red/orange candles, extracts the dense rectangular
 * candle body from each region, and uses the remaining vertical extent as the
 * wick. Prediction is blocked until enough reliable bodies are detected.
 */
public final class ChartDetector {

    private static final int MIN_CANDLES = 10;
    private static final int MAX_CANDLES = 20;

    private static final class Component {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int area = 0;
        final boolean green;

        Component(boolean green) {
            this.green = green;
        }

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }
    }

    private static final class Body {
        int centerX;
        int bodyTop;
        int bodyBottom;
        int wickTop;
        int wickBottom;
        boolean bullish;
        double confidence;
    }

    public DetectionResult detect(int[] pixels, int width, int height) {
        if (pixels == null || width < 200 || height < 160) {
            return new DetectionResult(
                    new ArrayList<>(),
                    0.0,
                    "Camera frame is too small."
            );
        }

        // The chart should occupy the main camera area. Top/bottom UI strips
        // are intentionally ignored because they create false colored regions.
        int left = Math.max(0, width / 30);
        int right = Math.min(width - 1, width * 29 / 30);
        int top = Math.max(0, height * 35 / 100);
        int bottom = Math.min(height - 1, height * 91 / 100);

        boolean[] green = new boolean[width * height];
        boolean[] warm = new boolean[width * height];

        for (int y = top; y <= bottom; y++) {
            int row = y * width;
            for (int x = left; x <= right; x++) {
                int p = pixels[row + x];
                int r = (p >> 16) & 255;
                int g = (p >> 8) & 255;
                int b = p & 255;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                int chroma = max - min;

                boolean isGreen =
                        chroma >= 32 &&
                        g >= 95 &&
                        g > r * 1.12 &&
                        g > b * 1.03;

                boolean isWarm =
                        chroma >= 28 &&
                        r >= 115 &&
                        r > b * 1.12 &&
                        (r > g * 1.07 || (r >= 150 && g >= 50));

                if (isGreen) green[row + x] = true;
                if (isWarm) warm[row + x] = true;
            }
        }

        List<Component> components = new ArrayList<>();
        components.addAll(findComponents(
                green, width, height, left, right, top, bottom, true));
        components.addAll(findComponents(
                warm, width, height, left, right, top, bottom, false));

        components.sort(Comparator.comparingInt(c -> c.minX));

        List<Body> bodies = new ArrayList<>();
        for (Component component : components) {
            Body body = extractBody(
                    component,
                    green,
                    warm,
                    width,
                    height,
                    top,
                    bottom
            );
            if (body != null) bodies.add(body);
        }

        // Remove near-duplicates caused by anti-aliased colored edges.
        bodies.sort(Comparator.comparingInt(b -> b.centerX));
        List<Body> filtered = new ArrayList<>();
        int minSeparation = Math.max(6, width / 45);

        for (Body body : bodies) {
            if (filtered.isEmpty()) {
                filtered.add(body);
                continue;
            }

            Body previous = filtered.get(filtered.size() - 1);
            if (Math.abs(body.centerX - previous.centerX) < minSeparation) {
                if (body.confidence > previous.confidence) {
                    filtered.set(filtered.size() - 1, body);
                }
            } else {
                filtered.add(body);
            }
        }

        // Keep the latest visible candles. A chart may contain more than 15.
        if (filtered.size() > MAX_CANDLES) {
            filtered = new ArrayList<>(
                    filtered.subList(
                            filtered.size() - MAX_CANDLES,
                            filtered.size()
                    )
            );
        }

        List<Candle> candles = new ArrayList<>();
        List<Integer> centers = new ArrayList<>();
        double confidenceSum = 0.0;

        for (Body body : filtered) {
            double fullRange = Math.max(1.0, body.wickBottom - body.wickTop);
            double scale = fullRange / 100.0;
            double base = 100.0;

            double openPixel = body.bullish
                    ? body.bodyBottom
                    : body.bodyTop;

            double closePixel = body.bullish
                    ? body.bodyTop
                    : body.bodyBottom;

            double open = base + (height / 2.0 - openPixel) / scale;
            double close = base + (height / 2.0 - closePixel) / scale;
            double high = base + (height / 2.0 - body.wickTop) / scale;
            double low = base + (height / 2.0 - body.wickBottom) / scale;

            candles.add(
                    new Candle(
                            open,
                            Math.max(high, Math.max(open, close)),
                            Math.min(low, Math.min(open, close)),
                            close,
                            body.bullish,
                            body.confidence
                    )
            );

            centers.add(body.centerX);
            confidenceSum += body.confidence;
        }

        double quality = computeQuality(
                candles.size(),
                confidenceSum,
                centers
        );

        if (candles.size() < MIN_CANDLES) {
            return new DetectionResult(
                    candles,
                    quality,
                    "Only " + candles.size()
                            + " candle bodies detected. Keep the chart filling the frame and avoid glare."
            );
        }

        return new DetectionResult(
                candles,
                quality,
                "Detected real colored candle bodies and wick structure."
        );
    }

    private List<Component> findComponents(
            boolean[] mask,
            int width,
            int height,
            int left,
            int right,
            int top,
            int bottom,
            boolean green) {

        boolean[] visited = new boolean[mask.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        List<Component> components = new ArrayList<>();

        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int start = y * width + x;
                if (!mask[start] || visited[start]) continue;

                Component c = new Component(green);
                visited[start] = true;
                queue.add(start);

                while (!queue.isEmpty()) {
                    int idx = queue.removeFirst();
                    int px = idx % width;
                    int py = idx / width;

                    c.area++;
                    c.minX = Math.min(c.minX, px);
                    c.maxX = Math.max(c.maxX, px);
                    c.minY = Math.min(c.minY, py);
                    c.maxY = Math.max(c.maxY, py);

                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;

                            int nx = px + dx;
                            int ny = py + dy;

                            if (nx < left || nx > right ||
                                    ny < top || ny > bottom) continue;

                            int ni = ny * width + nx;

                            if (mask[ni] && !visited[ni]) {
                                visited[ni] = true;
                                queue.add(ni);
                            }
                        }
                    }
                }

                int w = c.width();
                int h = c.height();
                double fill = (double) c.area / Math.max(1, w * h);

                // Candle bodies are compact; isolated wick/text components are
                // much thinner or have very low bounding-box fill.
                if (c.area >= 20 && w >= 5 && h >= 4 && fill >= 0.16) {
                    components.add(c);
                }
            }
        }

        return components;
    }

    private Body extractBody(
            Component component,
            boolean[] green,
            boolean[] warm,
            int width,
            int height,
            int top,
            int bottom) {

        int x0 = component.minX;
        int x1 = component.maxX;
        int y0 = component.minY;
        int y1 = component.maxY;
        int componentWidth = x1 - x0 + 1;

        int[] rowCounts = new int[y1 - y0 + 1];
        int total = 0;

        for (int y = y0; y <= y1; y++) {
            int count = 0;
            for (int x = x0; x <= x1; x++) {
                int idx = y * width + x;
                if (green[idx] || warm[idx]) count++;
            }
            rowCounts[y - y0] = count;
            total += count;
        }

        int threshold = Math.max(
                3,
                (int) Math.ceil(componentWidth * 0.40)
        );

        int bestStart = -1;
        int bestEnd = -1;
        int currentStart = -1;
        int bestLength = -1;

        for (int i = 0; i < rowCounts.length; i++) {
            if (rowCounts[i] >= threshold) {
                if (currentStart < 0) currentStart = i;
            } else if (currentStart >= 0) {
                int end = i - 1;
                int len = end - currentStart + 1;
                if (len >= 2 && len > bestLength) {
                    bestLength = len;
                    bestStart = currentStart;
                    bestEnd = end;
                }
                currentStart = -1;
            }
        }

        if (currentStart >= 0) {
            int end = rowCounts.length - 1;
            int len = end - currentStart + 1;
            if (len >= 2 && len > bestLength) {
                bestStart = currentStart;
                bestEnd = end;
            }
        }

        if (bestStart < 0 || bestEnd < bestStart) return null;

        int bodyTop = y0 + bestStart;
        int bodyBottom = y0 + bestEnd;
        int bodyHeight = bodyBottom - bodyTop + 1;

        if (bodyHeight < 3 || bodyHeight > Math.max(12, height / 4)) {
            return null;
        }

        int greenBody = 0;
        int warmBody = 0;
        for (int y = bodyTop; y <= bodyBottom; y++) {
            for (int x = x0; x <= x1; x++) {
                int idx = y * width + x;
                if (green[idx]) greenBody++;
                if (warm[idx]) warmBody++;
            }
        }

        int bodyPixels = greenBody + warmBody;
        if (bodyPixels < 15) return null;

        boolean bullish = greenBody >= warmBody;
        double colorPurity =
                (double) Math.max(greenBody, warmBody)
                        / Math.max(1, bodyPixels);

        // Trace wick only within a limited distance from the body so unrelated
        // colored chart text cannot become a giant wick.
        int cx = (x0 + x1) / 2;
        int wx0 = Math.max(0, cx - 2);
        int wx1 = Math.min(width - 1, cx + 2);
        int maxWick = Math.max(10, bodyHeight * 2);

        int wickTop = bodyTop;
        int wickBottom = bodyBottom;

        int misses = 0;
        for (int y = bodyTop - 1;
             y >= Math.max(top, bodyTop - maxWick);
             y--) {

            boolean hit = false;
            for (int x = wx0; x <= wx1; x++) {
                int idx = y * width + x;
                if (green[idx] || warm[idx]) {
                    hit = true;
                    break;
                }
            }

            if (hit) {
                wickTop = y;
                misses = 0;
            } else {
                misses++;
                if (misses >= 2) break;
            }
        }

        misses = 0;
        for (int y = bodyBottom + 1;
             y <= Math.min(bottom, bodyBottom + maxWick);
             y++) {

            boolean hit = false;
            for (int x = wx0; x <= wx1; x++) {
                int idx = y * width + x;
                if (green[idx] || warm[idx]) {
                    hit = true;
                    break;
                }
            }

            if (hit) {
                wickBottom = y;
                misses = 0;
            } else {
                misses++;
                if (misses >= 2) break;
            }
        }

        double fill =
                (double) bodyPixels
                        / Math.max(1, bodyHeight * componentWidth);

        double confidence =
                Math.min(
                        1.0,
                        0.40
                                + 0.30 * Math.min(1.0, fill)
                                + 0.30 * colorPurity
                );

        Body result = new Body();
        result.centerX = cx;
        result.bodyTop = bodyTop;
        result.bodyBottom = bodyBottom;
        result.wickTop = wickTop;
        result.wickBottom = wickBottom;
        result.bullish = bullish;
        result.confidence = confidence;
        return result;
    }

    private double computeQuality(
            int count,
            double confidenceSum,
            List<Integer> centers) {

        if (count == 0) return 0.0;

        double countScore = Math.min(1.0, count / 15.0);
        double bodyScore = confidenceSum / Math.max(1.0, count);

        double spacingScore = 0.0;
        if (centers.size() >= 4) {
            double sum = 0.0;
            for (int i = 1; i < centers.size(); i++) {
                sum += centers.get(i) - centers.get(i - 1);
            }
            double mean = sum / (centers.size() - 1);
            double variance = 0.0;
            for (int i = 1; i < centers.size(); i++) {
                double d = centers.get(i) - centers.get(i - 1) - mean;
                variance += d * d;
            }
            double sd = Math.sqrt(
                    variance / Math.max(1, centers.size() - 2)
            );
            double cv = mean > 0 ? sd / mean : 1.0;
            spacingScore = Math.max(0.0, 1.0 - cv * 2.0);
        }

        return Math.min(
                1.0,
                0.45 * countScore
                        + 0.35 * bodyScore
                        + 0.20 * spacingScore
        );
    }
}

package com.meshtastic.client.utils;

import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads SVG icons from resources.
 * The loader parses SVG path data and viewBox values, then creates a JavaFX
 * {@code SVGPath}. Fill color is controlled through CSS ({@code -fx-fill}) so
 * icons can follow the active theme.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class SvgIconLoader {

    private static final Logger log = LoggerFactory.getLogger(SvgIconLoader.class);

    private static final Pattern PATH_D_PATTERN = Pattern.compile("<path[^>]+d=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern VIEWBOX_PATTERN = Pattern.compile("viewBox=\"([^\"]+)\"");
    private static final Pattern FILL_RULE_PATTERN = Pattern.compile("fill-rule=\"evenodd\"");

    /** Cache from resource path to parsed SVG data. */
    private static final Map<String, SvgData> cache = new HashMap<>();

    private record SvgData(String pathData, double viewBoxSize, boolean evenOdd) {}

    /**
     * Creates an {@code SVGPath} from an SVG resource.
     *
     * @param resourcePath SVG resource path, for example {@code "/drawer/icon/chat.svg"}
     * @param size icon size in pixels, used for both width and height
     * @return loaded SVG path, or {@code null} when loading fails
     */
    public static SVGPath load(String resourcePath, double size) {
        SvgData data = parseSvg(resourcePath);
        if (data == null) { return null; }

        SVGPath svgPath = new SVGPath();
        svgPath.setContent(data.pathData());
        if (data.evenOdd()) {
            svgPath.setFillRule(FillRule.EVEN_ODD);
        }
        svgPath.getStyleClass().add("svg-icon");

        // Scale from SVG viewBox to the requested target size.
        double scale = size / data.viewBoxSize();
        svgPath.setScaleX(scale);
        svgPath.setScaleY(scale);

        return svgPath;
    }

    private static SvgData parseSvg(String resourcePath) {
        SvgData cached = cache.get(resourcePath);
        if (cached != null) { return cached; }

        try (InputStream is = SvgIconLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("SVG ресурс не найден: {}", resourcePath);
                return null;
            }
            String svg = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining(" "));

        // Extract path data.
            Matcher pathMatcher = PATH_D_PATTERN.matcher(svg);
            if (!pathMatcher.find()) {
                log.warn("Не найден <path d=\"...\"> в SVG: {}", resourcePath);
                return null;
            }
            String pathData = pathMatcher.group(1);

        // Extract viewBox size.
            double viewBoxSize = 48; // fallback
            Matcher vbMatcher = VIEWBOX_PATTERN.matcher(svg);
            if (vbMatcher.find()) {
                String[] parts = vbMatcher.group(1).trim().split("\\s+");
                if (parts.length >= 4) {
                    viewBoxSize = Double.parseDouble(parts[2]);
                }
            }

            boolean evenOdd = FILL_RULE_PATTERN.matcher(svg).find();

            SvgData data = new SvgData(pathData, viewBoxSize, evenOdd);
            cache.put(resourcePath, data);
            return data;
        } catch (Exception e) {
            log.error("Ошибка загрузки SVG: {}", resourcePath, e);
            return null;
        }
    }

    private SvgIconLoader() {}
}

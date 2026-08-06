package com.yellastrodev.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;

/**
 * Растеризует выбранные SVG-исходники
 * в PNG-наборы Compose Multiplatform
 * для каждой density.
 */
@CacheableTask
public abstract class RasterizeSvgToPngTask extends DefaultTask {
    private static final Pattern SIZE_PATTERN = Pattern.compile("^(\\d+)x(\\d+)$");

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceDirectory();

    /** Сопоставление имени drawable размеру SVG в dp, например 355x237. */
    @Input
    public abstract MapProperty<String, String> getAssets();

    /** Сопоставление Android density её коэффициенту относительно mdpi. */
    @Input
    public abstract MapProperty<String, Double> getDensityScales();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /** Создаёт прозрачные PNG с сохранением исходного соотношения и размеров. */
    @TaskAction
    public void rasterize() {
        File sourceRoot = getSourceDirectory().get().getAsFile();
        File outputRoot = getOutputDirectory().get().getAsFile();

        getFileSystemOperations().delete(spec -> spec.delete(outputRoot));

        Map<String, String> assets = new TreeMap<>(getAssets().get());
        Map<String, Double> densities = new TreeMap<>(getDensityScales().get());

        for (Map.Entry<String, String> asset : assets.entrySet()) {
            String resourceName = asset.getKey();
            File sourceFile = new File(sourceRoot, resourceName + ".svg");
            if (!sourceFile.isFile()) {
                throw new GradleException("SVG-исходник не найден: " + sourceFile);
            }

            Matcher sizeMatcher = SIZE_PATTERN.matcher(asset.getValue());
            if (!sizeMatcher.matches()) {
                throw new GradleException(
                    "Некорректный размер для " + resourceName + ": " + asset.getValue()
                );
            }
            int widthDp = Integer.parseInt(sizeMatcher.group(1));
            int heightDp = Integer.parseInt(sizeMatcher.group(2));

            for (Map.Entry<String, Double> density : densities.entrySet()) {
                int widthPx = (int) Math.round(widthDp * density.getValue());
                int heightPx = (int) Math.round(heightDp * density.getValue());
                File densityDirectory = new File(outputRoot, "drawable-" + density.getKey());
                File outputFile = new File(densityDirectory, resourceName + ".png");

                if (!densityDirectory.mkdirs() && !densityDirectory.isDirectory()) {
                    throw new GradleException("Не удалось создать каталог: " + densityDirectory);
                }

                renderPng(sourceFile, outputFile, widthPx, heightPx);
                getLogger().info(
                    "[rasterize] {} -> {} ({}x{})",
                    sourceFile.getName(),
                    outputFile,
                    widthPx,
                    heightPx
                );
            }
        }
    }

    private void renderPng(File sourceFile, File outputFile, int widthPx, int heightPx) {
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) widthPx);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) heightPx);

        TranscoderInput input = new TranscoderInput(sourceFile.toURI().toString());
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            TranscoderOutput output = new TranscoderOutput(outputStream);
            transcoder.transcode(input, output);
        } catch (IOException | TranscoderException exception) {
            if (outputFile.exists() && !outputFile.delete()) {
                getLogger().warn("[rasterize] Не удалось удалить неполный файл {}", outputFile);
            }
            throw new GradleException("Не удалось растрировать " + sourceFile, exception);
        }
    }
}

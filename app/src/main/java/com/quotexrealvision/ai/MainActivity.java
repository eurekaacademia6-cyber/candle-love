package com.quotexrealvision.ai;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_REQUEST = 1001;

    private PreviewView previewView;
    private TextView stateText;
    private TextView detectionText;
    private TextView qualityText;
    private TextView predictionText;
    private TextView probabilityText;
    private TextView whyText;
    private TextView diagnosticText;

    private ExecutorService executor;

    private ChartDetector detector;
    private PredictionEngine predictor;

    private volatile boolean busy = false;
    private volatile long lastAnalysisTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        stateText = findViewById(R.id.stateText);
        detectionText = findViewById(R.id.detectionText);
        qualityText = findViewById(R.id.qualityText);
        predictionText = findViewById(R.id.predictionText);
        probabilityText = findViewById(R.id.probabilityText);
        whyText = findViewById(R.id.whyText);
        diagnosticText = findViewById(R.id.diagnosticText);

        executor = Executors.newSingleThreadExecutor();
        detector = new ChartDetector();
        predictor = new PredictionEngine();

        requestCameraPermission();
    }

    private void requestCameraPermission() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED) {

            startCamera();

        } else {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_REQUEST
            );
        }
    }

    private void startCamera() {

        stateText.setText("LIVE");

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(
                new Runnable() {

                    @Override
                    public void run() {

                        try {

                            ProcessCameraProvider provider =
                                    future.get();

                            Preview preview =
                                    new Preview.Builder().build();

                            preview.setSurfaceProvider(
                                    previewView.getSurfaceProvider()
                            );

                            ImageAnalysis analysis =
                                    new ImageAnalysis.Builder()
                                            .setBackpressureStrategy(
                                                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                            )
                                            .setOutputImageFormat(
                                                    ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                                            )
                                            .build();

                            analysis.setAnalyzer(
                                    executor,
                                    new ImageAnalysis.Analyzer() {

                                        @Override
                                        public void analyze(
                                                @NonNull ImageProxy image
                                        ) {
                                            analyzeFrame(image);
                                        }
                                    }
                            );

                            provider.unbindAll();

                            provider.bindToLifecycle(
                                    MainActivity.this,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                            );

                        } catch (Exception e) {

                            stateText.setText("CAMERA ERROR");

                            diagnosticText.setText(
                                    "Camera setup failed: "
                                            + e.getClass().getSimpleName()
                            );
                        }
                    }
                },
                ContextCompat.getMainExecutor(this)
        );
    }

    private void analyzeFrame(ImageProxy image) {

        long now = System.currentTimeMillis();

        if (busy || now - lastAnalysisTime < 900L) {
            image.close();
            return;
        }

        busy = true;
        lastAnalysisTime = now;

        try {

            int width = image.getWidth();
            int height = image.getHeight();

            int[] pixels = readPixels(image);

            DetectionResult detection =
                    detector.detect(
                            pixels,
                            width,
                            height
                    );

            Signal signal =
                    predictor.predict(
                            detection.candles,
                            detection.quality
                    );

            runOnUiThread(
                    new Runnable() {

                        @Override
                        public void run() {
                            updateScreen(
                                    detection,
                                    signal
                            );
                        }
                    }
            );

        } catch (Exception e) {

            runOnUiThread(
                    new Runnable() {

                        @Override
                        public void run() {
                            diagnosticText.setText(
                                    "Analysis error: "
                                            + e.getClass().getSimpleName()
                            );
                        }
                    }
            );

        } finally {

            image.close();
            busy = false;
        }
    }

    private int[] readPixels(ImageProxy image) {

        ImageProxy.PlaneProxy plane =
                image.getPlanes()[0];

        java.nio.ByteBuffer buffer =
                plane.getBuffer();

        int width =
                image.getWidth();

        int height =
                image.getHeight();

        int rowStride =
                plane.getRowStride();

        int pixelStride =
                plane.getPixelStride();

        int[] pixels =
                new int[width * height];

        if (pixelStride < 4) {
            return pixels;
        }

        byte[] row =
                new byte[rowStride];

        for (int y = 0; y < height; y++) {

            int position =
                    y * rowStride;

            if (position >= buffer.capacity()) {
                break;
            }

            buffer.position(position);

            int available =
                    Math.min(
                            rowStride,
                            buffer.remaining()
                    );

            buffer.get(
                    row,
                    0,
                    available
            );

            for (int x = 0; x < width; x++) {

                int offset =
                        x * pixelStride;

                if (offset + 3 >= available) {
                    continue;
                }

                int red =
                        row[offset] & 255;

                int green =
                        row[offset + 1] & 255;

                int blue =
                        row[offset + 2] & 255;

                int alpha =
                        row[offset + 3] & 255;

                pixels[
                        y * width + x
                ] =
                        (alpha << 24)
                                | (red << 16)
                                | (green << 8)
                                | blue;
            }
        }

        return pixels;
    }

    private void updateScreen(
            DetectionResult detection,
            Signal signal
    ) {

        detectionText.setText(
                "Detected: "
                        + detection.candles.size()
                        + " candles"
        );

        qualityText.setText(
                String.format(
                        Locale.US,
                        "Quality: %.0f%%",
                        detection.quality * 100.0
                )
        );

        if (!detection.usable()) {

            predictionText.setText(
                    "SCAN AGAIN"
            );

            probabilityText.setText(
                    "UP --    DOWN --"
            );

            whyText.setText(
                    detection.message
            );

            diagnosticText.setText(
                    "Prediction blocked until 10+ reliable candles are detected."
            );

            return;
        }

        predictionText.setText(
                signal.label
        );

        probabilityText.setText(
                String.format(
                        Locale.US,
                        "UP %.1f%%    DOWN %.1f%%",
                        signal.upProbability * 100.0,
                        (1.0 - signal.upProbability) * 100.0
                )
        );

        StringBuilder text =
                new StringBuilder();

        for (String reason : signal.reasons) {

            text.append("- ")
                    .append(reason)
                    .append("\n");
        }

        whyText.setText(
                text.toString()
        );

        diagnosticText.setText(
                String.format(
                        Locale.US,
                        "Confidence %.0f%% | Agreement %.0f%% | %s",
                        signal.confidence * 100.0,
                        signal.agreement * 100.0,
                        detection.message
                )
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode != CAMERA_REQUEST) {
            return;
        }

        if (grantResults.length > 0
                && grantResults[0]
                == PackageManager.PERMISSION_GRANTED) {

            startCamera();

        } else {

            stateText.setText(
                    "CAMERA DENIED"
            );

            diagnosticText.setText(
                    "Camera permission is required."
            );
        }
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        if (executor != null) {
            executor.shutdownNow();
        }
    }
}

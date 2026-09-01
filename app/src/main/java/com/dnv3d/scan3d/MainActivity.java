package com.dnv3d.scan3d;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int BINS_PER_RING = 24;
    private static final int RINGS = 3;
    private static final int TARGET_PHOTOS = BINS_PER_RING * RINGS;
    private static final long STABLE_REQUIRED_MS = 300;
    private static final float GYRO_STABLE_RAD_S = 0.28f;

    private PreviewView previewView;
    private ScanOverlayView overlayView;
    private TextView txtInstruction;
    private TextView txtTelemetry;
    private TextView txtCounter;
    private ProgressBar progressScan;
    private Button btnStart;
    private Button btnManual;
    private Button btnFinish;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor gyroSensor;

    // headingDeg representa a direção REAL do eixo óptico da câmera no plano horizontal.
    // relativeYawDeg usa a direção inicial da sessão como 0°, evitando depender do norte magnético.
    private float headingDeg = 0f;
    private float relativeYawDeg = 0f;
    private float pitchDeg = 0f;
    private float rollDeg = 0f;
    private float headingZeroDeg = 0f;
    private boolean headingZeroReady = false;

    private float gyroSpeed = 999f;
    private long stableSince = 0L;

    private boolean scanning = false;
    private boolean captureBusy = false;
    private int ringIndex = 0;
    private final boolean[][] ringBins = new boolean[RINGS][BINS_PER_RING];
    private final List<PhotoMeta> photos = new ArrayList<>();
    private File sessionDir;
    private File imagesDir;

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else Toast.makeText(this, "A câmera é necessária para escanear.", Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.scanOverlay);
        txtInstruction = findViewById(R.id.txtInstruction);
        txtTelemetry = findViewById(R.id.txtTelemetry);
        txtCounter = findViewById(R.id.txtCounter);
        progressScan = findViewById(R.id.progressScan);
        btnStart = findViewById(R.id.btnStart);
        btnManual = findViewById(R.id.btnManual);
        btnFinish = findViewById(R.id.btnFinish);

        cameraExecutor = Executors.newSingleThreadExecutor();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        btnStart.setOnClickListener(v -> startScan());
        btnManual.setOnClickListener(v -> capturePhoto(true));
        btnFinish.setOnClickListener(v -> finishScan());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(95)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            } catch (Exception e) {
                Toast.makeText(this, "Falha ao iniciar câmera: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startScan() {
        if (imageCapture == null) {
            Toast.makeText(this, "A câmera ainda está iniciando.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (rotationSensor == null) {
            Toast.makeText(this, "Este celular não forneceu sensor de orientação.", Toast.LENGTH_LONG).show();
            return;
        }

        photos.clear();
        ringIndex = 0;
        clearAllBins();
        headingZeroDeg = headingDeg;
        headingZeroReady = true;
        relativeYawDeg = 0f;
        stableSince = 0L;

        sessionDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "DNVScan/session_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
        imagesDir = new File(sessionDir, "images");
        if (!imagesDir.mkdirs() && !imagesDir.exists()) {
            Toast.makeText(this, "Não consegui criar a pasta da sessão.", Toast.LENGTH_LONG).show();
            return;
        }

        scanning = true;
        btnStart.setEnabled(false);
        btnManual.setEnabled(true);
        btnFinish.setEnabled(true);
        txtInstruction.setText("FAIXA BAIXA: comece daqui e dê uma volta completa na peça.");
        updateUi();
    }

    private void clearAllBins() {
        for (int r = 0; r < RINGS; r++) {
            for (int b = 0; b < BINS_PER_RING; b++) ringBins[r][b] = false;
        }
    }

    private boolean[] currentBins() {
        int safeRing = Math.max(0, Math.min(RINGS - 1, ringIndex));
        return ringBins[safeRing];
    }

    private static float normalize360(float degrees) {
        float value = degrees % 360f;
        if (value < 0f) value += 360f;
        return value;
    }

    private static float normalize180(float degrees) {
        float value = normalize360(degrees);
        if (value > 180f) value -= 360f;
        return value;
    }

    private int currentBin() {
        float normalized = normalize360(relativeYawDeg);
        int bin = (int) Math.floor(normalized / (360f / BINS_PER_RING));
        return Math.max(0, Math.min(BINS_PER_RING - 1, bin));
    }

    private int countBins(int ring) {
        if (ring < 0 || ring >= RINGS) return 0;
        int count = 0;
        for (boolean filled : ringBins[ring]) if (filled) count++;
        return count;
    }

    private int countAllBins() {
        int total = 0;
        for (int r = 0; r < RINGS; r++) total += countBins(r);
        return total;
    }

    private void maybeAutoCapture() {
        if (!scanning || captureBusy || ringIndex >= RINGS) return;
        int bin = currentBin();
        if (ringBins[ringIndex][bin]) return;

        long now = SystemClock.elapsedRealtime();
        if (gyroSpeed <= GYRO_STABLE_RAD_S) {
            if (stableSince == 0L) stableSince = now;
            if (now - stableSince >= STABLE_REQUIRED_MS) capturePhoto(false);
        } else {
            stableSince = 0L;
        }
    }

    private void capturePhoto(boolean manual) {
        if (!scanning || imageCapture == null || captureBusy || ringIndex >= RINGS) return;

        final int capturedRing = ringIndex;
        final int bin = currentBin();
        if (ringBins[capturedRing][bin]) {
            if (manual) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Este setor já foi capturado. Gire mais um pouco ao redor da peça.", Toast.LENGTH_SHORT).show());
            }
            return;
        }

        final float capturedHeading = headingDeg;
        final float capturedYaw = relativeYawDeg;
        final float capturedPitch = pitchDeg;
        final float capturedRoll = rollDeg;

        captureBusy = true;
        stableSince = 0L;
        String fileName = String.format(Locale.US, "r%d_b%02d_%03d.jpg",
                capturedRing + 1, bin, photos.size() + 1);
        File outputFile = new File(imagesDir, fileName);
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(outputFile).build();

        imageCapture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                long ts = System.currentTimeMillis();
                photos.add(new PhotoMeta(fileName, ts, capturedRing, bin,
                        capturedHeading, capturedYaw, capturedPitch, capturedRoll, manual));
                ringBins[capturedRing][bin] = true;
                captureBusy = false;
                runOnUiThread(() -> {
                    updateUi();
                    if (capturedRing == ringIndex && countBins(ringIndex) >= BINS_PER_RING) advanceRing();
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                captureBusy = false;
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Erro ao fotografar: " + exception.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void advanceRing() {
        ringIndex++;
        stableSince = 0L;

        if (ringIndex == 1) {
            txtInstruction.setText("FAIXA MÉDIA: câmera na altura do centro. Volte ao ponto inicial e dê outra volta.");
        } else if (ringIndex == 2) {
            txtInstruction.setText("FAIXA ALTA: câmera acima da peça, inclinada para baixo. Dê a última volta.");
        } else {
            scanning = false;
            txtInstruction.setText("CAPTURA COMPLETA. Toque em FINALIZAR para gerar o pacote.");
            btnManual.setEnabled(false);
            btnStart.setEnabled(true);
        }
        updateUi();
    }

    private void updateUi() {
        int occupied = countAllBins();
        txtCounter.setText(occupied + " / " + TARGET_PHOTOS + " posições");
        progressScan.setProgress(Math.min(TARGET_PHOTOS, occupied));

        int shownRing = Math.min(ringIndex, RINGS - 1);
        int currentRingCount = countBins(shownRing);
        txtTelemetry.setText(String.format(Locale.getDefault(),
                "Faixa %d/3: %02d/24 • setor %02d/24 • volta %.0f° • inclinação %.0f° • mov. %.2f rad/s",
                shownRing + 1,
                currentRingCount,
                currentBin() + 1,
                relativeYawDeg,
                pitchDeg,
                gyroSpeed));

        overlayView.update(currentBins(), shownRing);
    }

    private void finishScan() {
        if (sessionDir == null || photos.isEmpty()) {
            Toast.makeText(this, "Ainda não há fotos nesta sessão.", Toast.LENGTH_SHORT).show();
            return;
        }
        scanning = false;
        btnManual.setEnabled(false);
        txtInstruction.setText("Gerando pacote da sessão…");

        cameraExecutor.execute(() -> {
            try {
                writeMetadata();
                File zip = zipSession();
                runOnUiThread(() -> {
                    txtInstruction.setText("Pacote pronto: " + photos.size() + " fotos em " + countAllBins() + " posições.");
                    btnStart.setEnabled(true);
                    shareZip(zip);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Erro ao finalizar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void writeMetadata() throws Exception {
        File meta = new File(sessionDir, "metadata.json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(meta), StandardCharsets.UTF_8)) {
            w.write("{\n");
            w.write("  \"app\": \"DNV Scan 3D\",\n");
            w.write("  \"version\": \"0.2.0\",\n");
            w.write("  \"orientationMode\": \"camera-forward-relative\",\n");
            w.write(String.format(Locale.US, "  \"headingZero\": %.3f,\n", headingZeroDeg));
            w.write("  \"createdAt\": " + System.currentTimeMillis() + ",\n");
            w.write("  \"rings\": 3,\n");
            w.write("  \"binsPerRing\": 24,\n");
            w.write("  \"coverage\": [" + countBins(0) + "," + countBins(1) + "," + countBins(2) + "],\n");
            w.write("  \"photos\": [\n");
            for (int i = 0; i < photos.size(); i++) {
                PhotoMeta p = photos.get(i);
                w.write(String.format(Locale.US,
                        "    {\"file\":\"%s\",\"timestamp\":%d,\"ring\":%d,\"bin\":%d,\"heading\":%.3f,\"yaw\":%.3f,\"pitch\":%.3f,\"roll\":%.3f,\"manual\":%s}%s\n",
                        p.file, p.timestamp, p.ring, p.bin, p.heading, p.yaw, p.pitch, p.roll,
                        p.manual ? "true" : "false", i == photos.size() - 1 ? "" : ","));
            }
            w.write("  ]\n}\n");
        }
    }

    private File zipSession() throws Exception {
        File zip = new File(sessionDir.getParentFile(), sessionDir.getName() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            addToZip(zos, sessionDir, sessionDir.getName());
        }
        return zip;
    }

    private void addToZip(ZipOutputStream zos, File file, String entryName) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) addToZip(zos, child, entryName + "/" + child.getName());
            }
            return;
        }
        zos.putNextEntry(new ZipEntry(entryName));
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) zos.write(buffer, 0, len);
        }
        zos.closeEntry();
    }

    private void shareZip(File zip) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", zip);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Enviar sessão 3D"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rotationSensor != null) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        if (gyroSensor != null) sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotation = new float[9];
            float[] orientation = new float[3];
            SensorManager.getRotationMatrixFromVector(rotation, event.values);

            // Android entrega a matriz que transforma coordenadas do aparelho para o mundo.
            // A câmera traseira olha aproximadamente no eixo -Z do aparelho. Transformando
            // esse vetor obtemos a direção para a qual a câmera realmente aponta, mesmo
            // com o telefone em pé, inclinado ou em landscape.
            float forwardEast = -rotation[2];
            float forwardNorth = -rotation[5];
            float forwardUp = -rotation[8];
            float horizontal = (float) Math.sqrt(forwardEast * forwardEast + forwardNorth * forwardNorth);

            if (horizontal > 0.0001f) {
                headingDeg = normalize360((float) Math.toDegrees(Math.atan2(forwardEast, forwardNorth)));
                pitchDeg = (float) Math.toDegrees(Math.atan2(forwardUp, horizontal));
            }

            SensorManager.getOrientation(rotation, orientation);
            rollDeg = normalize180((float) Math.toDegrees(orientation[2]));

            if (scanning && headingZeroReady) {
                relativeYawDeg = normalize360(headingDeg - headingZeroDeg);
            }

            runOnUiThread(this::updateUi);
            maybeAutoCapture();
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            gyroSpeed = (float) Math.sqrt(x * x + y * y + z * z);
            if (gyroSpeed > GYRO_STABLE_RAD_S) stableSince = 0L;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    private static class PhotoMeta {
        final String file;
        final long timestamp;
        final int ring;
        final int bin;
        final float heading;
        final float yaw;
        final float pitch;
        final float roll;
        final boolean manual;

        PhotoMeta(String file, long timestamp, int ring, int bin, float heading, float yaw,
                  float pitch, float roll, boolean manual) {
            this.file = file;
            this.timestamp = timestamp;
            this.ring = ring;
            this.bin = bin;
            this.heading = heading;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.manual = manual;
        }
    }
}

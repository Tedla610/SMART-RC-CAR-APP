package com.example.rccar;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "RCCAR";
    private static final int PERMISSION_REQUEST_CODE = 1;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private ConnectedThread connectedThread;
    private TextView statusText;
    private TextView speedLabel;
    private TextView steerLabel;
    private TextView sensorDataText;
    private Button connectButton;
    private SeekBar speedSlider;
    private SeekBar steerSlider;
    private boolean sendReady = true;

    private static final String HC06_NAME = "HC-06"; // Adjust if your HC-06 has a custom name
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        speedLabel = findViewById(R.id.speedLabel);
        steerLabel = findViewById(R.id.steerLabel);
        sensorDataText = findViewById(R.id.sensorDataText);
        connectButton = findViewById(R.id.connectButton);
        speedSlider = findViewById(R.id.speedSlider);
        steerSlider = findViewById(R.id.steerSlider);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth not enabled or not available");
            Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Log.d(TAG, "Bluetooth adapter initialized successfully");

        connectButton.setOnClickListener(v -> {
            if (bluetoothSocket == null || !bluetoothSocket.isConnected()) {
                if (checkAndRequestPermissions()) {
                    connectToHC06();
                }
            } else {
                disconnectFromHC06();
            }
        });

        speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pwm = 1000 + (progress * 10);
                speedLabel.setText("Speed: " + pwm + (pwm == 1500 ? " (Neutral)" : ""));
                if (fromUser) {
                    Log.d(TAG, "Speed slider moved: " + pwm);
                    sendPWMValue("E" + String.format("%04d", pwm));
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        steerSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pwm = 1000 + (progress * 10);
                steerLabel.setText("Steering: " + pwm + (pwm == 1500 ? " (Center)" : ""));
                if (fromUser) {
                    Log.d(TAG, "Steer slider moved: " + pwm);
                    sendPWMValue("S" + String.format("%04d", pwm));
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private boolean checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Requesting ACCESS_FINE_LOCATION");
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
            return false;
        }
        if (!isLocationEnabled()) {
            Log.d(TAG, "Location services disabled");
            Toast.makeText(this, "Please enable Location services for Bluetooth", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(intent);
            return false;
        }
        Log.d(TAG, "All permissions granted and location enabled");
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean locationGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(android.Manifest.permission.ACCESS_FINE_LOCATION) && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    locationGranted = false;
                    Log.e(TAG, "ACCESS_FINE_LOCATION denied");
                }
            }
            if (locationGranted && isLocationEnabled()) {
                Log.d(TAG, "Permissions granted, connecting to HC-06");
                connectToHC06();
            } else if (!locationGranted) {
                Toast.makeText(this, "Location permission required for Bluetooth", Toast.LENGTH_LONG).show();
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    Log.e(TAG, "Location permission permanently denied");
                    Toast.makeText(this, "Enable Location permission in app settings", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                } else {
                    checkAndRequestPermissions();
                }
            } else {
                Log.d(TAG, "Location services disabled");
                Toast.makeText(this, "Please enable Location services for Bluetooth", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivity(intent);
            }
        }
    }

    private boolean isLocationEnabled() {
        android.location.LocationManager locationManager = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        return locationManager != null && locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
    }

    private void connectToHC06() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Bluetooth permissions missing");
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        BluetoothDevice hc06Device = null;
        for (BluetoothDevice device : bondedDevices) {
            if (device.getName() != null && device.getName().equals(HC06_NAME)) {
                hc06Device = device;
                break;
            }
        }

        if (hc06Device == null) {
            Log.w(TAG, "HC-06 not found in paired devices");
            runOnUiThread(() -> {
                statusText.setText("HC-06 Not Found");
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            });
            return;
        }

        try {
            bluetoothSocket = hc06Device.createRfcommSocketToServiceRecord(SPP_UUID);
            Log.d(TAG, "Connecting to " + hc06Device.getName());
            runOnUiThread(() -> {
                statusText.setText("Connecting...");
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
                connectButton.setText("Disconnect");
            });

            new Thread(() -> {
                try {
                    bluetoothSocket.connect();
                    outputStream = bluetoothSocket.getOutputStream();
                    inputStream = bluetoothSocket.getInputStream();
                    Log.d(TAG, "Connected to HC-06");
                    runOnUiThread(() -> {
                        statusText.setText("Connected");
                        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
                    });

                    connectedThread = new ConnectedThread();
                    connectedThread.start();

                    sendPWMValue("E1500");
                    sendPWMValue("S1500");
                } catch (IOException e) {
                    Log.e(TAG, "Connection failed: " + e.getMessage());
                    try {
                        bluetoothSocket.close();
                    } catch (IOException closeException) {
                        Log.e(TAG, "Close failed: " + closeException.getMessage());
                    }
                    runOnUiThread(() -> {
                        statusText.setText("Connection Failed");
                        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                        connectButton.setText("Connect to HC-06");
                    });
                }
            }).start();
        } catch (IOException e) {
            Log.e(TAG, "Socket creation failed: " + e.getMessage());
            runOnUiThread(() -> {
                statusText.setText("Connection Failed");
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                connectButton.setText("Connect to HC-06");
            });
        }
    }

    private void disconnectFromHC06() {
        if (bluetoothSocket != null) {
            Log.d(TAG, "Disconnecting from HC-06");
            try {
                if (connectedThread != null) {
                    connectedThread.cancel();
                    connectedThread = null;
                }
                if (outputStream != null) {
                    outputStream.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
                bluetoothSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Close failed: " + e.getMessage());
            }
            bluetoothSocket = null;
            outputStream = null;
            inputStream = null;
            runOnUiThread(() -> {
                statusText.setText("Disconnected");
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                connectButton.setText("Connect to HC-06");
                sensorDataText.setText("T: --°C");
            });
        }
    }

    private void sendPWMValue(String command) {
        if (!sendReady) {
            Log.d(TAG, "Not ready to send: " + command);
            Toast.makeText(this, "Please wait before sending another command", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bluetoothSocket == null || !bluetoothSocket.isConnected() || outputStream == null) {
            Log.e(TAG, "Bluetooth not connected, cannot send: " + command);
            runOnUiThread(() -> {
                Toast.makeText(this, "Not connected to HC-06", Toast.LENGTH_SHORT).show();
                disconnectFromHC06();
            });
            sendReady = true;
            return;
        }

        sendReady = false;
        String value = command + "\n";
        try {
            outputStream.write(value.getBytes());
            outputStream.flush();
            Log.d(TAG, "Sent: " + value.trim() + " (" + Arrays.toString(value.getBytes()) + ")");
            sendReady = true;
        } catch (IOException e) {
            Log.e(TAG, "Write failed: " + e.getMessage());
            runOnUiThread(() -> {
                Toast.makeText(this, "Error sending command", Toast.LENGTH_SHORT).show();
                disconnectFromHC06();
            });
            sendReady = true;
        }
    }
    private class ConnectedThread extends Thread {
        private boolean running = true;

        public void cancel() {
            running = false;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[32];
            StringBuilder receivedDataBuffer = new StringBuilder();

            while (running) {
                try {
                    if (inputStream.available() > 0) {
                        int bytes = inputStream.read(buffer);
                        String receivedData = new String(buffer, 0, bytes);
                        Log.d(TAG, "Raw bytes: " + Arrays.toString(Arrays.copyOf(buffer, bytes)));
                        receivedDataBuffer.append(receivedData);

                        // Split on newline, keeping incomplete messages
                        String[] messages = receivedDataBuffer.toString().split("\n", -1);
                        for (int i = 0; i < messages.length - 1; i++) {
                            String message = messages[i];
                            if (!message.isEmpty()) {
                                Log.d(TAG, "Received message: " + message + " (length: " + message.length() + ")");
                                // Check for valid temperature message
                                if (message.startsWith("T:") && message.endsWith("C")) {
                                    runOnUiThread(() -> sensorDataText.setText(message));
                                    Log.d(TAG, "Displayed temperature: " + message);
                                } else {
                                    Log.d(TAG, "Ignored non-temperature data: " + message);
                                }
                            }
                        }

                        // Keep the last (potentially incomplete) message
                        receivedDataBuffer.setLength(0);
                        if (messages.length > 0) {
                            receivedDataBuffer.append(messages[messages.length - 1]);
                        }
                    } else {
                        Thread.sleep(10);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Read failed: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (running) {
                            Toast.makeText(MainActivity.this, "Connection lost", Toast.LENGTH_SHORT).show();
                            disconnectFromHC06();
                        }
                    });
                    break;
                } catch (InterruptedException e) {
                    Log.e(TAG, "Thread interrupted: " + e.getMessage());
                    break;
                }
            }
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectFromHC06();
    }
}
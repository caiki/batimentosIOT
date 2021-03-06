/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package herv.app.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import herv.app.R;
import herv.app.activities.MainActivity;
import herv.app.model.Heartbeat;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {

    private final static String TAG = BluetoothLeService.class.getSimpleName();
    public static boolean indicador = false;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String deviceAddress;
    private String userID;
    private BluetoothGatt mBluetoothGatt;
    private NotificationManager notificationMgr;

    private int mConnectionState = STATE_DISCONNECTED;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    private final static String INTENT_PREFIX = BluetoothLeService.class.getPackage().getName();
    public final static String ACTION_GATT_CONNECTED = INTENT_PREFIX+".ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED = INTENT_PREFIX+".ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED = INTENT_PREFIX+".ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE = INTENT_PREFIX+".ACTION_DATA_AVAILABLE";
    public final static String EXTRA_SERVICE_UUID = INTENT_PREFIX+".EXTRA_SERVICE_UUID";
    public final static String EXTRA_CHARACTERISTIC_UUID = INTENT_PREFIX+".EXTRA_CHARACTERISTIC_UUI";
    public final static String EXTRA_DATA = INTENT_PREFIX+".EXTRA_DATA";

    public final static UUID UUID_HRMEASURE = UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);

    public final static int NOTIFICATION_EX = 1;
    private static SimpleDateFormat formatDateDB = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static SimpleDateFormat formatDateFilename = new SimpleDateFormat("yyMMddHH");


    //region lifecycle management

    public int onStartCommand(Intent intent, int flags, int startId) {

        String address = intent.getExtras().getString("address");
        String userID = intent.getExtras().getString("user");
        Log.i(TAG, "Received start command with address " + address + " and user " + userID);

        // If we get killed, after returning from here, restart
        if (!getConnectedState()) {
            Log.i(TAG, "[onStartCommand] Trying to connect to address " + address );
            this.showForegroundNotification(getString(R.string.notification_disconnected), R.drawable.herv_logo_3);
            this.deviceAddress = address;
            this.userID = userID;
            this.connect(address);
        }
        //TODO test if the address is different from the one stored. If so, disconnect and connect to new server

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        notificationMgr.cancel(NOTIFICATION_EX);
    }

    /**
     * https://gist.github.com/kristopherjohnson/6211176
     **/
    private void showForegroundNotification(String contentText, int icon) {
        // Create intent that will bring our app to the front, as if it was tapped in launcher
        Intent showTaskIntent = new Intent(getApplicationContext(), MainActivity.class);
        showTaskIntent.setAction(Intent.ACTION_MAIN);
        showTaskIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        showTaskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent contentIntent = PendingIntent.getActivity(
                getApplicationContext(),
                0,
                showTaskIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(getApplicationContext())
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(icon)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent)
                .build();
        startForeground(NOTIFICATION_EX, notification);
    }

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        public BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    //endregion


    //region bluetooth management
    /** get a reference to BluetoothAdapter through BluetoothManager. */
    private BluetoothAdapter getBluetoothAdapter() {
        Log.i(TAG, "Initializing bluetooth adapter");
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return null;
            }
        }
        return mBluetoothManager.getAdapter();
    }
    //endregion


    //region gatt server connection management

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device with specified address
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public boolean connect(final String address) {

        Log.i(TAG, "Connecting to gatt server in bluetooth device with address " + address );

        if (deviceAddress == null) {
            Log.e(TAG, "Unspecified address.");
            return false;
        }

        if (this.mBluetoothAdapter == null) {
            BluetoothAdapter adapter = getBluetoothAdapter();
            if (adapter == null) {
                Log.e(TAG, "Unable to obtain BluetoothAdapter.");
                return false;
            }
            this.mBluetoothAdapter = adapter;
        }

        // try to reconnect to a previously connected gatt server
        if (mBluetoothGatt != null) {
            Log.i(TAG, "Previously existing gatt connection");
            // mBluetoothGatt.disconnect();
            // mBluetoothGatt = null;
            if (mBluetoothGatt.connect()) {
                Log.i(TAG, "Trying to use an existing gatt server connection.");
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                Log.i(TAG, "Existing connection lost");
                mBluetoothGatt = null; // unable to reconnect, try to create a new connection
                mConnectionState = STATE_DISCONNECTED;
                return false;
            }
        }

        // no previous connected gatt server, starting a new one
        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.e(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        Log.i(TAG, "Device found. Creating a new connection to its gatt server");
        // We want to directly connect to the device, so we are setting autoConnect to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        //TODO test if connection was successful
        Log.i(TAG, "Connected to device's gatt server");
        deviceAddress = address;
        mConnectionState = STATE_CONNECTING;

        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    public boolean getConnectedState() {
        return (mConnectionState == STATE_CONNECTED || mConnectionState == STATE_CONNECTING);
    }

    /**
     * Implements callback methods for GATT events (connection to bluetooth server, in this case,
     * polar H7 transmitter) that the app cares about.
     **/
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                Log.i(TAG, "Attempting to start service discovery:" + mBluetoothGatt.discoverServices());
                showForegroundNotification(getString(R.string.notification_connecting), R.drawable.herv_logo_3);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
                Log.i(TAG, "Trying to reconnect");
                showForegroundNotification(getString(R.string.notification_disconnected), R.drawable.herv_logo_3);
                int i = 0;
                //TODO move to a separate thread and let it sleep for a few seconds before trying again
                while(!connect(deviceAddress)) {
                    i++;
                    if (i%10 == 0) {
                        Log.i(TAG, "Tried " + i + " times...");
                    }
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered");
                BluetoothGattCharacteristic hr = findHRMCharacteristic(getSupportedGattServices());
                setCharacteristicNotification(hr, true);
                showForegroundNotification(getString(R.string.notification_running), R.drawable.herv_logo_3);
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "Could not discover services: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
    };
    //endregion


    //region characteristic management

    /**
     * Enables or disables notification on a given characteristic.
     * @param enabled  if true, enables notifications; if false, disables them
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {

        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is specific to Heart Rate Measurement
        if (UUID_HRMEASURE.equals(characteristic.getUuid())) {
            Log.i(TAG, "Setting up notification for heart rate measurement");
            UUID uuid = UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(uuid);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        } else {
            Log.i(TAG, "Setting up notification for non heart rate measurement characteristic");
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;
        return mBluetoothGatt.getServices();
    }


    /**
     * Searches a list of available gatt services and their characteristics to find the
     * heart rate measurement characteristic
     **/
    public BluetoothGattCharacteristic findHRMCharacteristic(List<BluetoothGattService> gattServices) {

        for (BluetoothGattService gattService : gattServices) {
            List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                if (UUID_HRMEASURE.equals(gattCharacteristic.getUuid())) {
                    return gattCharacteristic;
                }
            }
        }
        return null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    //endregion


    //region broadcast received characteristic

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    /**
     * Broadcasts data from a received updated characteristic
     */
    private void broadcastUpdate(final String action, final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        String data;
        if (UUID_HRMEASURE.equals(characteristic.getUuid())) {
            Heartbeat beat = extractFromHRMCharacteristic(characteristic);
            //System.out.println();
            data = beat.toScreenString();
            //TODO create a different service or at least thread to deal with file writing
            if (beat.getIntervals() != null ) {
                //control de lectura
                if (indicador){saveDataToCSV(beat);}
            }
        } else {
            data = extractFromGeneralCharacteristic(characteristic);
        }
        intent.putExtra(EXTRA_DATA, data);
        sendBroadcast(intent);
    }

    private void saveDataToCSV(Heartbeat beat) {
        String dt = formatDateFilename.format(Calendar.getInstance().getTime());
        StringBuilder filename = new StringBuilder();
        filename.append("rr");
        filename.append(dt);
        //filename.append("_");
        //filename.append(this.userID);
        filename.append(".csv");
        ScratchFileWriter writer = new ScratchFileWriter(this, filename.toString());
        writer.saveData(beat.toCSV());
    }

    //endregion


    //TODO move to a separate class to unclutter this
    //region read info from GATT characteristics

    /**
     * Reads HR and RR (IBI) intervals from the Heart Rate Measurement Characteristic
     * Parsing of the values is done according to:
     * http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml
     **/
    private Heartbeat extractFromHRMCharacteristic(BluetoothGattCharacteristic characteristic) {
        List<Integer> rr = extractBeatToBeatInterval(characteristic);
        Integer hr = extractHeartRate(characteristic);
        return new Heartbeat(rr, hr);
    }

    /**
     * Reads data from characteristics other than heart rate measurement, dumping in HEX format
     **/
    private String extractFromGeneralCharacteristic(BluetoothGattCharacteristic characteristic) {
        final byte[] data = characteristic.getValue();
        if (data != null && data.length > 0) {
            final StringBuilder stringBuilder = new StringBuilder(data.length);
            for (byte byteChar : data)
                stringBuilder.append(String.format("%02X ", byteChar));
            return (new String(data) + "\n" + stringBuilder.toString());
        }
        return "";
    }


    /**
     * Extracts RR (IBI) intervals from heart rate measurement characteristic
     * Copied from https://github.com/oerjanti/BLE-Heart-rate-variability-demo
     */
    private List<Integer> extractBeatToBeatInterval( BluetoothGattCharacteristic characteristic) {

        int flag = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        int format = -1;
        int energy = -1;
        int offset = 1; // This depends on hear rate value format and if there is energy data
        int rr_count = 0;

        if ((flag & 0x01) != 0) {
            format = BluetoothGattCharacteristic.FORMAT_UINT16;
            offset = 3;
        } else {
            format = BluetoothGattCharacteristic.FORMAT_UINT8;
            offset = 2;
        }
        if ((flag & 0x08) != 0) {
            // calories present
            energy = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, offset);
            offset += 2;
            Log.d(TAG, "Received energy: {}"+ energy);
        }
        if ((flag & 0x16) != 0){
            //Log.d(TAG, "RR length: "+ (characteristic.getValue()).length);
            rr_count = ((characteristic.getValue()).length - offset) / 2;
            Log.d(TAG, "rr_count: "+ rr_count);
            if (rr_count > 0) {
                List<Integer> beats = new ArrayList<Integer>(rr_count);
                for (int i = 0; i < rr_count; i++) {
                    beats.add(characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, offset));
                    offset += 2;
                    Log.d(TAG, "RR: " + beats.get(i));
                }
                return beats;
            }
        }
        Log.d(TAG, "No RR data on this update: ");
        return null;
    }

    /**
     * Extracts heart rate value from heart rate measurement characteristic
     * Modified from https://github.com/oerjanti/BLE-Heart-rate-variability-demo
     */
    private Integer extractHeartRate( BluetoothGattCharacteristic characteristic) {

        int flag = characteristic.getProperties();
        int format = -1;
        // Heart rate bit number format
        if ((flag & 0x01) != 0) {
            format = BluetoothGattCharacteristic.FORMAT_UINT16;
        } else {
            format = BluetoothGattCharacteristic.FORMAT_UINT8;
        }
        final int heartRate = characteristic.getIntValue(format, 1);
        Log.d(TAG, String.format("Received heart rate: %d", heartRate));
        return heartRate;
    }

    //endregion

}

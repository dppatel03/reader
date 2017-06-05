package com.educloud.usbreader;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.PersistableBundle;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity implements Runnable{

    private Context activityContext;
    private final String TAG = this.getClass().getSimpleName();
    private static final String ACTION_USB_PERMISSION = "com.educloud.usbreader.USB_PERMISSION";
    // These are the same as the XML filter
    private static final int VID = 2303;
    private static final int PID = 9;

    private UsbManager mUsbManager;
    private UsbDevice mDevice;
    private UsbDeviceConnection mConnection;
    private UsbEndpoint mEndpointIn;
    private PendingIntent mPermissionIntent;
    private boolean mRunning = false;

    private BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    // If usb device permission is granted
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            //Call method to set up device communication
                            setDevice(device);
                        }
                    } else {
                        Log.d(TAG, "permission denied for device " + device);
                        setDevice(null);
                        Toast.makeText(getApplicationContext(), "Permission denied for device", Toast.LENGTH_SHORT).show();
                    }
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    // call your method that cleans up and closes communication with the device
                    setDevice(null);
                    if(txtMessage != null){
                        txtMessage.setText("Device is disconnected");
                    }
                }
            }
        }
    };
    private TextView txtMessage, txtCardNo;
    private Button btnReset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        activityContext = this;
        Intent intent = getIntent();

        txtMessage = (TextView) findViewById(R.id.txtMessage);
        txtCardNo = (TextView) findViewById(R.id.txtCardNo);
        btnReset = (Button) findViewById(R.id.btnReset);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                txtCardNo.setText("");
            }
        });
        // Register broadcast for usb device
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        //Create the intent to fire with permission results
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter();
        //filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(mUsbReceiver, filter);

        String action = intent.getAction();

        /* Check if this was launched from a device attached intent, and
         * obtain the device instance if it was.  We do this in onStart()
         * because permissions dialogs may pop up as a result, which will
         * cause an infinite loop in onResume().
         */
        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        txtMessage.setText("Device is disconnected");
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            setDevice(device);
        } else {
            searchForDevice();
        }
    }

    /*
     * Get Interface and Endpoint and start communication
     */
    private void setDevice(UsbDevice device) {
        Log.d(TAG, "setDevice " + device);
        if (device == null) {
            //Cancel connections
            mConnection = null;
            mRunning = false;
            return;
        }

        //Verify the device has what we need
        if (device.getInterfaceCount() != 1) {
            Log.e(TAG, "Could not find interface");
            return;
        }
        // Get interface
        UsbInterface intf = device.getInterface(0);

        // Device should have one endpoint
        if (intf.getEndpointCount() != 1) {
            Log.e(TAG, "Could not find endpoint");
            return;
        }

        // Get endpoint should be of type interrupt
        UsbEndpoint ep = intf.getEndpoint(0);

        /**
         * UsbConstants.USB_ENDPOINT_XFER_INT = Interrupt endpoint type [3]
         * UsbConstants.USB_DIR_IN = [128] Used to signify direction of data for a UsbEndpoint is IN (device to host)
         */
        int endPointType = ep.getType();
        int endPointDirection = ep.getDirection();
        if (endPointType != UsbConstants.USB_ENDPOINT_XFER_INT
                || endPointDirection != UsbConstants.USB_DIR_IN) {
            Log.e(TAG, "Endpoint is not Interrupt IN");
            return;
        }

        mDevice = device;
        mEndpointIn = ep;
        if(mUsbManager != null) {
            // Opens the device so it can be used to send and receive data using UsbRequest.
            UsbDeviceConnection connection = mUsbManager.openDevice(device);

            /**
             * Claims exclusive access to a UsbInterface. This must be done before
             * sending or receiving data on any UsbEndpoints belonging to the interface.
             */
            if (connection != null && connection.claimInterface(intf, true)) { // true to disconnect kernel driver if necessary
                mConnection = connection;
                txtMessage.setText("Device is connected");
                //Start the polling thread
                mRunning = true;
                Thread thread = new Thread(null, this, "UsbHost");
                thread.start();
                Toast.makeText(getApplicationContext(), "Device has been connected, thread started", Toast.LENGTH_SHORT).show();
            } else {
                mConnection = null;
                mRunning = false;
            }
        }
    }

    private void searchForDevice() {

        //If we find our device already attached, connect to it
        HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();
        UsbDevice selectedDevice = null;
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == VID && device.getProductId() == PID) {
                selectedDevice = device;
                break;
            }
        }
        //Request a connection
        if (selectedDevice != null) {
            if (mUsbManager.hasPermission(selectedDevice)) {
                setDevice(selectedDevice);
            } else {
                mUsbManager.requestPermission(selectedDevice, mPermissionIntent);
            }
        }
    }

    @Override
    public void run() {
        int bufferMaxLength = mEndpointIn.getMaxPacketSize();
        ByteBuffer buffer = ByteBuffer.allocate(bufferMaxLength);

        /**
         * Represents an asynchronous request to communicate with a device through a UsbDeviceConnection.
         */
        UsbRequest request = new UsbRequest();

        /**
         * Initializes the request so it can read or write data on the given endpoint.
         * Whether the request allows reading or writing depends on the direction of the endpoint.
         */
        request.initialize(mConnection, mEndpointIn);

        while (mRunning) {
            try{
                /**
                 * Queues the request to send or receive data on its endpoint.
                 * 1) For OUT endpoints, the remaining bytes of the buffer will be sent on the endpoint.
                 * 2) For IN endpoints, the endpoint will attempt to fill the remaining bytes of the buffer
                 * return true if the queueing operation succeeded
                 */
                boolean operationStatus = request.queue(buffer, bufferMaxLength);
                Log.i(TAG, "run() -> Queueing: "+operationStatus);

                /**
                 * Waits for the result of a queue(ByteBuffer) operation.
                 * return a completed USB request, or null if an error occurred
                 */
                UsbRequest completedRequest = mConnection.requestWait();

                if(completedRequest == request){
                    // Get response data
                    byte[] data = buffer.array();
                    StringBuilder sb = new StringBuilder();
                    for(byte element : data){
                        sb.append(element);
                    }
                    final String response = sb.toString();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String str = txtCardNo.getText()+" "+response;
                            txtCardNo.setText(str);
                        }
                    });
                    buffer.clear();
                }

                Thread.sleep(25);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Log.i(TAG, "run() -> "+e.getMessage());
            } catch (Exception e){
                e.printStackTrace();
                Log.i(TAG, e.getMessage());
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String action = intent.getAction();
        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        txtMessage.setText("Device is disconnected");
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            setDevice(device);
        } else {
            searchForDevice();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public boolean isFinishing() {
        return super.isFinishing();
    }

    @Override
    protected void onStart() {
        super.onStart();

        //Keep the screen on while using the application
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStop() {
        try{
            // Unregister permission usb receiver.
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            unregisterReceiver(mUsbReceiver);
            mRunning = false;

        }catch (Exception e){
            e.printStackTrace();
            Log.i(TAG,"onStop(): "+e.getMessage());
        }

        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    public void onSaveInstanceState(Bundle outState, PersistableBundle outPersistentState) {
        super.onSaveInstanceState(outState, outPersistentState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}

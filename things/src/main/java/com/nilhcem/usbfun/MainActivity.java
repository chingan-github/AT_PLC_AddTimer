package com.nilhcem.usbfun;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends Activity {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int USB_VENDOR_ID  = 0x067B; //0x067B 是 usb->RS232 VENDOR_ID
    private static final int USB_PRODUCT_ID = 0x2303; //0x6001是 usb->RS232  PRODUCT_ID
    private static byte ETX=0x03;
    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialDevice;
    private String buffer = "";
    public int ReadType = 0; //0:讀 D Register  1:讀 M Register
    //
    Timer Read_PLC_timer =new Timer(); //宣告Timer

    //================================================================================================================================================以下是寫入 Cmd 到 PLC
   /*1.PLC & PI 透過 RS232 通訊
         a.讀取 Bit Data[use M Register]
            Send Cmd : 0x5 + "00FFBRAM000010" + 0xA + 0xD
            測試讀取範圍 : M0000 ~ M000F (16點)
        b.讀取 Word Data[use D Register]
            Send Cmd : 0x5 + "00FFWRAD000008" + 0xA + 0xD
            測試讀取範圍 : D0000 ~ M0007 (8點)
        */
    String Msg_Word_Rd_Cmd = "\u0005" + "00FFWRAD000010";//  //讀取Word D0000-D0007 Cmd
    String Msg_Bit_Rd_Cmd  = "\u0005" + "00FFBRAM001010"; //  //讀取Bit  M0000-M0015 Cmd

    //======================================================================================
    private UsbSerialInterface.UsbReadCallback callback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] data) {
            try {
                String dataUtf8 = new String(data, "UTF-8");
                buffer += dataUtf8;
                int index;
                //Log.i(TAG, "Test....Serial data received: "+ buffer);  //Test
                while ((index = buffer.indexOf(ETX)) != -1) {    /////// etx   '\n'
                    final String dataStr = buffer.substring(0, index + 1).trim();
                    buffer = buffer.length() == index ? "" : buffer.substring(index + 1);
                    //Log.i(TAG, "Test....收到結尾 0x3 "+ buffer);  //Test
                    //
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onSerialDataReceived(dataStr); //收到資料後的處理
                        }
                    });
                }
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "Error receiving USB data", e);
            }
        }
    };

    //======================================================================================
    private final BroadcastReceiver usbDetachedReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && device.getVendorId() == USB_VENDOR_ID && device.getProductId() == USB_PRODUCT_ID) {
                    Log.i(TAG, "USB device detached");
                    stopUsbConnection();
                }
            }
        }
    };

    @Override
    //====================================================================================
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = getSystemService(UsbManager.class);
        // Detach events are sent as a system-wide broadcast
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbDetachedReceiver, filter);
        //
        Log.i(TAG, "表示從 2秒 後，每隔 1000 毫秒執行讀PLC 1次");
        Read_PLC_timer.schedule(new  PLC_TimerTask(), 2000, 1000); ///表示從 2秒 後，每隔 1000 毫秒執行一次
    } //[End of] onCreate()

    @Override
    protected void onResume() {
        super.onResume();
        startUsbConnection();
    } //[End of]  onResume()

    @Override
    protected void onDestroy() {
        Read_PLC_timer.cancel(); //停止  Timer 指令
        super.onDestroy();
        unregisterReceiver(usbDetachedReceiver);
        stopUsbConnection();
    } //[End of]  onDestroy()  //結束程式

    private void startUsbConnection() {
        Map<String, UsbDevice> connectedDevices = usbManager.getDeviceList();

        if (!connectedDevices.isEmpty()) {
            for (UsbDevice device : connectedDevices.values()) {
                if (device.getVendorId() == USB_VENDOR_ID && device.getProductId() == USB_PRODUCT_ID) {
                    Log.i(TAG, "Device found: " + device.getDeviceName());
                    startSerialConnection(device);
                    return;
                }
            }
        }
        Log.w(TAG, "Could not start USB connection - No devices found");
    } //[End of]  startUsbConnection()

    private void startSerialConnection(UsbDevice device) {
        Log.i(TAG, "Ready to open USB device connection");
        connection = usbManager.openDevice(device);
        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection);
        if (serialDevice != null) {
            if (serialDevice.open()) {
                serialDevice.setBaudRate(9600);
                serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
                serialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
                serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
                serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                serialDevice.read(callback);
                Log.i(TAG, "Serial connection opened");
            } else {
                Log.w(TAG, "Cannot open serial connection");
            }
        } else {
            Log.w(TAG, "Could not create Usb Serial Device");
        }
    } //[End of]  startSerialConnection()

    private void onSerialDataReceived(String data) {
        // Add whatever you want here
        String Send_Out= "";
        Log.i(TAG, "Serial data received: " + data);  //接收後記錄資料
        Send_Out = Msg_Word_Rd_Cmd+ '\n'+'\r';
        //serialDevice.write(Send_Out.getBytes()); //由 RS232 送出讀取指令
        //serialDevice.write("Android Things\n".getBytes()); // Async-like operation now! :)  //這邊是寫入 Serial 範例
    } //[End of]  onSerialDataReceived()

    private void stopUsbConnection() {
        try {
            if (serialDevice != null) {
                serialDevice.close();
            }

            if (connection != null) {
                connection.close();
            }
        } finally {
            serialDevice = null;
            connection = null;
        }
    } //[End of]  stopUsbConnection()


    //=============================================================讀取 PLC 指令送出執行動作
    public class PLC_TimerTask extends TimerTask
    {
        public void run() {
            String Send_Out = "";
            //
            //0:讀 D Register  1:讀 M Register
            if(ReadType == 0)
              {
              Send_Out = Msg_Word_Rd_Cmd + '\n' + '\r';
              Log.i(TAG, "讀 D Reg: " + Send_Out);  //記錄資料 :讀 D Reg
              ReadType = 1; //下一次讀  M Register
              }
            else
              {
              Send_Out = Msg_Bit_Rd_Cmd + '\n' + '\r';
              Log.i(TAG, "讀 M Reg: " + Send_Out);  //記錄資料 :讀 M Reg
              ReadType = 0; //下一次讀 D Register
              }
            //
            serialDevice.write(Send_Out.getBytes()); //由 RS232 送出讀取指令
        }
    };
} //[End of]  public class MainActivity extends Activity

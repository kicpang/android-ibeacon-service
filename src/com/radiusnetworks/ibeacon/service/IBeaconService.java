package com.radiusnetworks.ibeacon.service;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.radiusnetworks.ibeacon.IBeacon;
import com.radiusnetworks.ibeacon.Region;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/*
 * Idea:  Use an IBeaconIntentProcessor extends IntentService to handle communication from IBeaconService
 * 
 *  Then the IBeaconService simply sends intents to the IBeaconIntentProcessor, which is declared in the application.xml so it can launch itself
 *  this IBeaconIntentProcessor has access to the IBeaconManager, so it can simply call its rangeNotifier or monitorNotifier
 *  in order for this to work, though, this must function even if the IBeaconManager has never been instantiated yet.  the rangeNotifier and monitorNotifier classes
 *  must be able to be created declaratively through xml.   so if the ranging/monitoring intent comes for the app even before it was ever launched, the IBeaconIntentProcessor
 *  will fire, bootstrap the IBeaconManager, along with its notifiers, and then fire them.  
 *  
 *  Now, as for launching an activity if necessary, this logic can be put in the notifiers.  if it detects an activity isn't running, it can launch one.
 * 
 */



/**
 * Issues:
 * 1. If two apps register ranges with the same id, they clobber eachother.  
 * 2. If an app goes away after staring monitoring or ranging, we will continue to make callbacks from the service
 * 3. Is sending so many intents efficient?
 * @author dyoung
 *
 * Differences from Apple's SDK:
 * 1. You can wildcard all fields in a region to get updates about ANY iBeacon
 * 2. Ranging updates don't come as reliably every second.
 * 3. The distance measurement algorithm is not exactly the same
 * 4. You can do ranging when the app is not in the foreground
 * 5. It requires Bluetooth Admin privilidges
 */

public class IBeaconService extends Service {
	public static final String TAG = "IBeaconService";

	private Map<Region, RangeState> _rangedRegionState = new HashMap<Region,RangeState>();
	private Map<Region, MonitorState> _monitoredRegionState = new HashMap<Region,MonitorState>();
	private BluetoothAdapter _bluetoothAdapter;
    private boolean _scanning;
    private HashSet<IBeacon> _trackedBeacons;
    private Handler _handler = new Handler();
    /*
     * The scan period is how long we wait between restarting the BLE advertisement scans
     * Each time we restart we only see the unique advertisements once (e.g. unique iBeacons)
     * So if we want updates, we have to restart.  iOS gets updates once per second, so ideally we
     * would restart scanning that often to get the same update rate.  The trouble is that when you 
     * restart scanning, it is not instantaneous, and you lose any iBeacon packets that were in the 
     * air during the restart.  So the more frequently you restart, the more packets you lose.  The
     * frequency is therefore a tradeoff.  Testing with 14 iBeacons, transmitting once per second,
     * here are the counts I got for various values of the SCAN_PERIOD:
     * 
     * Scan period     Avg iBeacons      % missed
     *    1s               6                 57
     *    2s               10                29
     *    3s               12                14
     *    5s               14                0
     *    
     * Also, because iBeacons transmit once per second, the scan period should not be an even multiple
     * of seconds, because then it may always miss a beacon that is syncronized with when it is stopping
     * scanning.
     * 
     */
    private static final long SCAN_PERIOD = 1100;
    private static final long BACKGROUND_SCAN_PERIOD = 30000;
    private static final long BACKGROUND_BETWEEN_SCAN_PERIOD = 5*60*1000;
    
    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class IBeaconBinder extends Binder {
        public IBeaconService getService() {
        	Log.i(TAG, "getService of IBeaconBinder called");        	
            // Return this instance of LocalService so clients can call public methods
            return IBeaconService.this;
        }
    }


    /** Command to the service to display a message */
    public static final int MSG_START_RANGING = 2;
    public static final int MSG_STOP_RANGING = 3;
    public static final int MSG_START_MONITORING = 4;
    public static final int MSG_STOP_MONITORING = 5;
    

    static class IncomingHandler extends Handler {
        private final WeakReference<IBeaconService> mService; 

        IncomingHandler(IBeaconService service) {
            mService = new WeakReference<IBeaconService>(service);
        }
        @Override
        public void handleMessage(Message msg)
        {
             IBeaconService service = mService.get();
             StartRMData startRMData = (StartRMData) msg.obj;

             if (service != null) {
                 switch (msg.what) {
                 case MSG_START_RANGING:
                 	Log.d(TAG, "start ranging received");
                 	service.startRangingBeaconsInRegion(startRMData.getRegionData(), new com.radiusnetworks.ibeacon.service.Callback(msg.replyTo, startRMData.getIntentActionForCallback()));                	
                    break;
                 case MSG_STOP_RANGING:
                 	Log.d(TAG, "stop ranging received");
                 	service.stopRangingBeaconsInRegion(startRMData.getRegionData());
                 	break;
                 case MSG_START_MONITORING:
                 	Log.d(TAG, "start monitoring received");
                 	service.startMonitoringBeaconsInRegion(startRMData.getRegionData(), new com.radiusnetworks.ibeacon.service.Callback(msg.replyTo, startRMData.getIntentActionForCallback()));
                 	break;
                 case MSG_STOP_MONITORING:
                 	Log.d(TAG, "stop monitoring received");
                 	service.stopMonitoringBeaconsInRegion(startRMData.getRegionData());
                 	break;

                 default:
                     super.handleMessage(msg);
                 }
             }
        }
    }
    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "binding");
        return mMessenger.getBinder();
    }   
    @Override
    public boolean onUnbind (Intent intent) {
    	Log.i(TAG, "unbind called");
    	
		return false;    	
    }
    
    
    @Override
    public void onCreate() {
    	Log.i(TAG, "onCreate of IBeaconService called");
		// Initializes Bluetooth adapter.
		final BluetoothManager bluetoothManager =
		        (BluetoothManager) this.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
		_bluetoothAdapter = bluetoothManager.getAdapter();    	
    }
    @Override
    public void onDestroy() {
    	Log.i(TAG, "onDestory called.  stopping scanning");
    	scanLeDevice(false);
    }
    
    private int ongoing_notification_id = 1;
    
    public void runInForeground(Class<? extends Activity> klass) {
    			
    	Intent notificationIntent = new Intent(this, klass);
    	PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
    	Notification notification = new Notification.Builder(this.getApplicationContext())
        .setContentTitle("Scanning for iBeacons")
        .setSmallIcon(android.R.drawable.star_on)
        .addAction(android.R.drawable.star_off, "this is the other title", pendingIntent)
        .build();
    	startForeground(ongoing_notification_id++, notification);
    }

    
    
    /* 
     * Returns true if the service is running, but no bound clients are in the foreground
     */
    private boolean isInBackground() {
    	return false;
    }

    /** methods for clients */

				
	// TODO: make it so that regions between apps do not collide
	public void startRangingBeaconsInRegion(Region region, Callback callback) {
		_rangedRegionState.put(region, new RangeState(callback));
		if (!_scanning) {
		    scanLeDevice(true); 					
		}
	}
	public void stopRangingBeaconsInRegion(Region region) {
		_rangedRegionState.remove(region);
		if (_scanning && _rangedRegionState.size() == 0 && _monitoredRegionState.size() == 0) {
			scanLeDevice(false); 							
		}
	}
	public void startMonitoringBeaconsInRegion(Region region, Callback callback) {
		_monitoredRegionState.put(region,  new MonitorState(callback));
		if (!_scanning) {
		    scanLeDevice(true); 					
		}
		
	}
	public void stopMonitoringBeaconsInRegion(Region region) {
		_monitoredRegionState.remove(region);

		if (_scanning && _rangedRegionState.size() == 0 && _monitoredRegionState.size() == 0) {
			scanLeDevice(false); 							
		}		
	}

    private void scanLeDevice(final boolean enable) {
    	if (_bluetoothAdapter == null) {
    		return;
    	}
        if (enable) {
            // Stops scanning after a pre-defined scan period.
        	
        	long scanPeriod = SCAN_PERIOD;
        	if (isInBackground()) {
        		scanPeriod = BACKGROUND_SCAN_PERIOD;
        	}
        	
            _handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                	// Don't restart scanning if you haven't seen any beacons yet -- there is no point, because the only purpose of restarting
                	// scanning is to clear out Android's refusal to forward updates of Advertisements it has already seen.
                	if (_scanning == true && _trackedBeacons.size() > 0) {
                    	Log.d(TAG, "Restarting scan.  Unique beacons seen last cycle: "+_trackedBeacons.size());
                        _bluetoothAdapter.stopLeScan(mLeScanCallback);
                        if (isInBackground()) {
                        	_handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                	scanLeDevice(true);                        	
                                }
                        	}, BACKGROUND_BETWEEN_SCAN_PERIOD);
                        }
                        else {
                        	scanLeDevice(true);                		                        		
                        }
                	}
                }
            }, scanPeriod);
            
            _scanning = true;
            _trackedBeacons = new HashSet<IBeacon>();
            _bluetoothAdapter.startLeScan(mLeScanCallback);
            Log.d(TAG, "Scan started");
        } else {
            _scanning = false;
            _bluetoothAdapter.stopLeScan(mLeScanCallback);
        }        
        processExpiredMonitors();
        processRangeData();
        Log.d(TAG, "Done with scan cycle");
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {

    	@Override
        public void onLeScan(final BluetoothDevice device, final int rssi,
                final byte[] scanRecord) {
    		new ScanProcessor().execute(new ScanData(device, rssi, scanRecord));

       }
    };	
    
    private class ScanData {
    	public ScanData(BluetoothDevice device, int rssi, byte[] scanRecord) {
			this.device = device;
			this.rssi = rssi;
			this.scanRecord = scanRecord;
		}
		public BluetoothDevice device;
    	public int rssi;
    	public byte[] scanRecord;
    }
    
    private void processRangeData() {
    	Iterator<Region> regionIterator = _rangedRegionState.keySet().iterator();
    	while (regionIterator.hasNext()) {
    		Region region = regionIterator.next();
    		RangeState rangeState = _rangedRegionState.get(region);
    		if (rangeState.getIBeacons().size() > 0) {
    			rangeState.getCallback().call(IBeaconService.this, "monitoringData", new RangingData(rangeState.getIBeacons(), region));    			
    		}    		
    		rangeState.clearIBeacons();
    	}

    }
    
    private void processExpiredMonitors() {
		  Iterator<Region> monitoredRegionIterator = _monitoredRegionState.keySet().iterator();
		  while (monitoredRegionIterator.hasNext()) {
			  Region region = monitoredRegionIterator.next();
			  MonitorState state = _monitoredRegionState.get(region);
			  if (state.isNewlyOutside()) {
				  Log.d(TAG, "found a monitor that expired: "+region);
				  state.getCallback().call(IBeaconService.this, "monitoringData", new MonitoringData(state.isInside(), region));
			  }     			  
		  }
    }
    
    private class ScanProcessor extends AsyncTask<ScanData, Void, Void> {

        @Override
        protected Void doInBackground(ScanData... params) {
        	ScanData scanData = params[0];

     	   IBeacon iBeacon = IBeacon.fromScanData(scanData.scanRecord, scanData.rssi);
     	   if (iBeacon != null) {
     		   _trackedBeacons.add(iBeacon);
         	   Log.d(TAG, "iBeacon detected :"+iBeacon.getProximityUuid()+" "+iBeacon.getMajor()+" "+iBeacon.getMinor()+" accuracy: "+iBeacon.getAccuracy()+" proximity: "+iBeacon.getProximity());            		   
 
         	   List<Region> matchedRegions = matchingRegions(iBeacon, _monitoredRegionState.keySet());
     		   Iterator<Region> matchedRegionIterator = matchedRegions.iterator();
     		   while (matchedRegionIterator.hasNext()) {
     			   Region region = matchedRegionIterator.next();
     			   MonitorState state = _monitoredRegionState.get(region);
     			   if (state.markInside()) { 
      				  state.getCallback().call(IBeaconService.this, "monitoringData", new MonitoringData(state.isInside(), region));
     			   }
     		   }
         		       		  
     		   matchedRegions = matchingRegions(iBeacon, _rangedRegionState.keySet());
     		   matchedRegionIterator = matchedRegions.iterator();
     		   while (matchedRegionIterator.hasNext()) {
     			   Region matchedRegion = matchedRegionIterator.next();
     			   List<IBeaconData> list = new ArrayList<IBeaconData>();
     			   Log.d(TAG, "Making iBeaconData from iBeacon: "+iBeacon);
     			   IBeaconData ibd = new IBeaconData(iBeacon);
     			   RangeState rangeState = _rangedRegionState.get(matchedRegion);
     			   rangeState.addIBeacon(iBeacon);     			   
     		   }

     	   }
     	   //I see a device: 00:02:72:C5:EC:33 with scan data: 02 01 1A 1A FF 4C 00 02 15 84 2A F9 C4 08 F5 11 E3 92 82 F2 3C 91 AE C0 5E D0 00 00 69 C5 0000000000000000000000000000000000000000000000000000000000000000
     	   //
     	   // 9: proximityUuid (16 bytes) 84 2A F9 C4 08 F5 11 E3 92 82 F2 3C 91 AE C0 5E
     	   // 25: major (2 bytes unsigned int)
     	   // 27: minor (2 bytes unsigned int)
     	   // 29: tx power (1 byte signed int)        	
        	return null;
        }      

        @Override
        protected void onPostExecute(Void result) {
        }

        @Override
        protected void onPreExecute() {
        }

        @Override
        protected void onProgressUpdate(Void... values) {
        }
    }   
    private List<Region> matchingRegions(IBeacon iBeacon, Collection<Region> regions) {
    	List<Region> matched = new ArrayList<Region>();
    	Iterator<Region> regionIterator = regions.iterator();
    	while (regionIterator.hasNext()) {
    		Region region = regionIterator.next();
    		if (region.matchesIBeacon(iBeacon)) {
    			matched.add(region);
    		}
    	}
    	return matched;    	
    }

}
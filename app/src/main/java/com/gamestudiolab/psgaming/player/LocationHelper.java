package com.gamestudiolab.psgaming.player;

import android.annotation.TargetApi;
import android.content.Context;
import android.location.GnssStatus;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.Iterator;

class LocationHelper implements LocationListener {
    private static final String TAG = LocationHelper.class.getSimpleName();
    private LocationManager mLocationManager;
    private boolean mLocationEnable;
    private GpsStatus.Listener mGpsStatusListener;
    private GnssStatus.Callback mGnssStatusCallback;
    private OnNmeaMessageListener mNmeaMessageListener;
    private GpsStatus.NmeaListener mNmeaListener;
    private float mAltitudeAboveSeaLevel = 0f;
    private float mHdop = 0f;

    LocationHelper(Context context) {
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        mLocationEnable = false;
    }

    void startLocationUpdates() {
        Log.d(TAG, "startLocationUpdates");
        if (!mLocationEnable) {
            boolean isGPSEnabled = false;
            boolean isNetworkEnabled = false;
            try {
                isGPSEnabled = mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                isNetworkEnabled = mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
                mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, this);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mGnssStatusCallback = new GnssStatus.Callback() {
                        @Override
                        public void onSatelliteStatusChanged(GnssStatus status) {
                            onSatelliteStatus(status);
                        }
                    };
                    mLocationManager.registerGnssStatusCallback(mGnssStatusCallback);
                    mNmeaMessageListener = new OnNmeaMessageListener() {
                        @Override
                        public void onNmeaMessage(String message, long timestamp) {
                            onNmea(message);
                        }
                    };
                    mLocationManager.addNmeaListener(mNmeaMessageListener);
                } else {
                    mGpsStatusListener = new GpsStatus.Listener() {
                        @Override
                        public void onGpsStatusChanged(int event) {
                            onGpsStatus(event);
                        }
                    };
                    mLocationManager.addGpsStatusListener(mGpsStatusListener);
                    mNmeaListener = (timestamp, nmea) -> onNmea(nmea);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        mLocationManager.addNmeaListener((OnNmeaMessageListener) mNmeaListener);
                    }
                }
                mLocationEnable = true;
            } catch (SecurityException e) {
                Log.e(TAG, "Cannot start location updates: " + e.toString());
            }
            if (!isGPSEnabled && !isNetworkEnabled) {
                Log.i(TAG, "No location provider found");
                // TODO: notify user
            }
        }
    }

    void stopLocationUpdates() {
        Log.d(TAG, "stopLocationUpdates");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (mGnssStatusCallback != null) {
                mLocationManager.unregisterGnssStatusCallback(mGnssStatusCallback);
                mGnssStatusCallback = null;
            }
            if (mNmeaMessageListener != null) {
                mLocationManager.removeNmeaListener(mNmeaMessageListener);
                mNmeaMessageListener = null;
            }
        } else {
            if (mGpsStatusListener != null) {
                mLocationManager.removeGpsStatusListener(mGpsStatusListener);
                mGpsStatusListener = null;
            }
            if (mNmeaListener != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mLocationManager.removeNmeaListener((OnNmeaMessageListener) mNmeaListener);
                }
                mNmeaListener = null;
            }
        }
        if (mLocationEnable) {
            mLocationEnable = false;
            mLocationManager.removeUpdates(this);
        }
    }


    /*
     * LocationListener
     */

    @Override
    public void onLocationChanged(Location location) {
        long time = location.getTime() / 1000; // ms to s !!
        float latitude = (float) location.getLatitude();
        float longitude = (float) location.getLongitude();
        float speed = location.getSpeed() * 3.6f; // m/s to km/h !!
        float bearing = location.getBearing();

        NativeApp.setGpsDataAndroid(time, mHdop, latitude, longitude, mAltitudeAboveSeaLevel, speed, bearing);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }


    @TargetApi(Build.VERSION_CODES.N)
    private void onSatelliteStatus(GnssStatus status) {
        short index = 0;
        for (short i = 0; i < status.getSatelliteCount(); i++) {
            if (status.getConstellationType(i) != GnssStatus.CONSTELLATION_GPS) {
                continue;
            }
            NativeApp.setSatInfoAndroid(index, (short) status.getSvid(i), (short) status.getElevationDegrees(i),
                    (short) status.getAzimuthDegrees(i), (short) status.getCn0DbHz(i), status.usedInFix(i) ? (short) 1 : (short) 0);
            index++;
            if (index == 24) {
                break;
            }
        }
    }

    private void onGpsStatus(int event) {
        switch (event) {
            case GpsStatus.GPS_EVENT_STARTED:
            case GpsStatus.GPS_EVENT_STOPPED:
            case GpsStatus.GPS_EVENT_FIRST_FIX:
                break;
            case GpsStatus.GPS_EVENT_SATELLITE_STATUS: {
                try {
                    GpsStatus gpsStatus = mLocationManager.getGpsStatus(null);
                    Iterable<GpsSatellite> satellites = gpsStatus.getSatellites();

                    short index = 0;
                    for (Iterator<GpsSatellite> iterator = satellites.iterator(); iterator.hasNext(); ) {
                        GpsSatellite satellite = iterator.next();
                        if (satellite.getPrn() > 37) {
                            continue;
                        }
                        NativeApp.setSatInfoAndroid(index, (short) satellite.getPrn(), (short) satellite.getElevation(),
                                (short) satellite.getAzimuth(), (short) satellite.getSnr(), satellite.usedInFix() ? (short) 1 : (short) 0);
                        index++;
                        if (index == 24) {
                            break;
                        }
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, e.toString());
                }
                break;
            }
        }
    }

    private void onNmea(String nmea) {
        String[] tokens = nmea.split(",");
        if (tokens.length < 10 || !tokens[0].equals("$GPGGA")) {
            return;
        }
        if (!tokens[8].isEmpty()) {
            mHdop = Float.valueOf(tokens[8]);
        }
        if (!tokens[9].isEmpty()) {
            mAltitudeAboveSeaLevel = Float.valueOf(tokens[9]);
        }
    }
}

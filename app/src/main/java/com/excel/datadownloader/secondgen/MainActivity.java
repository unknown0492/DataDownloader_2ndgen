package com.excel.datadownloader.secondgen;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.excel.excelclasslibrary.UtilSharedPreferences;
import com.excel.util.Constants;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    static final String TAG = "DataDownloader";

    String[] permissions = {
            // Manifest.permission.RECEIVE_BOOT_COMPLETED, // // Normal Permisison
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            // Manifest.permission.WRITE_SETTINGS, // // Special Permisison -> https://developer.android.com/reference/android/Manifest.permission.html#WRITE_SETTINGS
            "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION",
    };

    SharedPreferences spfs;

    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_main );

        spfs = (SharedPreferences) UtilSharedPreferences.createSharedPreference( this, Constants.PERMISSION_SPFS );

        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            if ( checkPermissions() ) {
                // permissions  granted.
                UtilSharedPreferences.editSharedPreference( spfs, Constants.IS_PERMISSION_GRANTED, Constants.PERMISSION_GRANTED_YES );
                finish();
            }
        }
        else{
            finish();
        }


    }

    @Override
    public void onRequestPermissionsResult( int requestCode, String permissions[], int[] grantResults ) {
        switch ( requestCode ) {
            case 10:
            {
                if( grantResults.length > 0 && grantResults[ 0 ] == PackageManager.PERMISSION_GRANTED ){
                    // permissions granted.
                    Log.d( TAG, grantResults.length + " Permissions granted : " );
                    UtilSharedPreferences.editSharedPreference( spfs, Constants.IS_PERMISSION_GRANTED, Constants.PERMISSION_GRANTED_YES );
                } else {
                    String permission = "";
                    for ( String per : permissions ) {
                        permission += "\n" + per;
                    }
                    // permissions list of don't granted permission
                    Log.d( TAG, "Permissions not granted : "+permission );
                    UtilSharedPreferences.editSharedPreference( spfs, Constants.IS_PERMISSION_GRANTED, Constants.PERMISSION_GRANTED_NO );
                }
                return;
            }
        }
    }

    public boolean checkPermissions() {
        int result;
        List<String> listPermissionsNeeded = new ArrayList<>();
        for ( String p:permissions ) {
            result = ContextCompat.checkSelfPermission( this, p );
            if ( result != PackageManager.PERMISSION_GRANTED ) {
                listPermissionsNeeded.add( p );
            }
        }
        if ( !listPermissionsNeeded.isEmpty() ) {
            ActivityCompat.requestPermissions( this, listPermissionsNeeded.toArray( new String[ listPermissionsNeeded.size() ] ), 10 );
            return false;
        }
        return true;
    }
}

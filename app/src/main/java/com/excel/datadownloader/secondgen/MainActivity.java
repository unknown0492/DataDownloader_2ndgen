package com.excel.datadownloader.secondgen;

import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.excel.configuration.Constants;
import com.excel.datadownloader.util.*;
import com.excel.excelclasslibrary.UtilSharedPreferences;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import static com.excel.datadownloader.util.Constants.IS_PERMISSION_GRANTED;
import static com.excel.datadownloader.util.Constants.PERMISSION_GRANTED_YES;
import static com.excel.datadownloader.util.Constants.PERMISSION_SPFS;

public class MainActivity extends Activity {

    static final String TAG = "DataDownloader";
    String[] permissions = {
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.INTERNET",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION"};
    SharedPreferences spfs;
    Context context = this;
    Receiver receiver;

    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_main );

        registerAllBroadcasts();

        spfs = UtilSharedPreferences.createSharedPreference( context, PERMISSION_SPFS );
        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            if ( checkPermissions() ) {
                // permissions  granted.
                UtilSharedPreferences.editSharedPreference( spfs, IS_PERMISSION_GRANTED, PERMISSION_GRANTED_YES );
                finish();
            }
        }
        else{
            finish();
        }
    }

    Vector<IntentFilter> intentFilterVector;

    private void registerAllBroadcasts(){
        receiver = new Receiver();
        intentFilterVector = new Vector<IntentFilter>();
        intentFilterVector.add( new IntentFilter( "connectivity_change" ) );
        intentFilterVector.add( new IntentFilter( "get_wallpapers" ) );
        intentFilterVector.add( new IntentFilter( "get_hotel_logo" ) );
        intentFilterVector.add( new IntentFilter( "get_tv_channels_file" ) );
        intentFilterVector.add( new IntentFilter( "download_preinstall_apps" ) );
        intentFilterVector.add( new IntentFilter( "get_ota_info" ) );
        intentFilterVector.add( new IntentFilter( "run_ota_upgrade" ) );
        intentFilterVector.add( new IntentFilter( "postpone_ota_upgrade" ) );
        //intentFilterVector.add( new IntentFilter( "" ) );


        Iterator<IntentFilter> iterator = intentFilterVector.iterator();
        while( iterator.hasNext() ){
            registerReceiver( receiver, iterator.next() );
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver( receiver );

    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions2, int[] grantResults){
        switch ( requestCode ) {
            case 10:
            {
                if( grantResults.length > 0 && grantResults[ 0 ] == PackageManager.PERMISSION_GRANTED ){
                    // permissions granted.
                    Log.d( TAG, grantResults.length + " Permissions granted : " );
                } else {
                    String permission = "";
                    for ( String per : permissions ) {
                        permission += "\n" + per;
                    }
                    // permissions list of don't granted permission
                    Log.d( TAG, "Permissions not granted : "+permission );
                }
                return;
            }
        }
    }

    private  boolean checkPermissions() {
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

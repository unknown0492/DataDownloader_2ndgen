package com.excel.datadownloader.secondgen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.excel.datadownloader.services.DownloadHotelLogoService;
import com.excel.datadownloader.services.DownloadWallpaperService;


public class Receiver extends BroadcastReceiver {

    final static String TAG = "Receiver";

    @Override
    public void onReceive( Context context, Intent intent ) {
        String action = intent.getAction();
        Log.d( TAG, "action : " + action );

        if( action.equals( "android.net.conn.CONNECTIVITY_CHANGE" ) || action.equals( "connectivity_changed" ) ){

            // 1. First time in order to receive broadcasts, the app should be started at least once
            startDataDownloader( context );

            // 2. Download Hotel Logo
            context.sendBroadcast( new Intent( "get_hotel_logo" ) );

        }
        else if( action.equals( "get_wallpapers" ) ){
            getWallpapers( context );
        }
        else if( action.equals( "get_hotel_logo" ) ){
            getHotelLogo( context );
        }

    }

    private void startDataDownloader( Context context ){
        // Start this app activity
        Intent in = new Intent( context, MainActivity.class );
        in.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
        context.startActivity( in );
    }

    private void getWallpapers( Context context ){
        Intent in = new Intent( context, DownloadWallpaperService.class );
        context.startService( in );
    }

    private void getHotelLogo( Context context ){
        Intent in = new Intent( context, DownloadHotelLogoService.class );
        context.startService( in );
    }
}

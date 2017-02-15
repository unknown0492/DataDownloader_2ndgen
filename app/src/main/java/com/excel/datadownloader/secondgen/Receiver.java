package com.excel.datadownloader.secondgen;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.excel.datadownloader.services.DownloadHotelLogoService;
import com.excel.datadownloader.services.DownloadOTAService;
import com.excel.datadownloader.services.DownloadPreinstallApks;
import com.excel.datadownloader.services.DownloadTvChannelsService;
import com.excel.datadownloader.services.DownloadWallpaperService;
import com.excel.excelclasslibrary.UtilShell;


public class Receiver extends BroadcastReceiver {

    final static String TAG = "Receiver";

    @Override
    public void onReceive( Context context, Intent intent ) {
        String action = intent.getAction();
        Log.d( TAG, "action : " + action );

        if( action.equals( "android.net.conn.CONNECTIVITY_CHANGE" ) || action.equals( "connectivity_changed" ) ){

            // 1. First time in order to receive broadcasts, the app should be started at least once
            startDataDownloader( context );

            // These broadcasts are to be FIRED only once per box reboot
            if( ! isConnectivityBroadcastFired() ) {

                // 2. Download Hotel Logo
                context.sendBroadcast(new Intent("get_hotel_logo"));

                // 3. Download TV Channels file
                context.sendBroadcast(new Intent("get_tv_channels_file"));

                // 4. Check for OTA
                context.sendBroadcast(new Intent( "get_ota_info" ) );

                setConnectivityBroadcastFired( true );
            }

        }
        else if( action.equals( "get_wallpapers" ) ){
            getWallpapers( context );
        }
        else if( action.equals( "get_hotel_logo" ) ){
            getHotelLogo( context );
        }
        else if( action.equals( "get_tv_channels_file" ) ){
            getTvChannelsFile( context );
        }
        else if( action.equals( "download_preinstall_apps" ) ){
            downloadPreinstallApks( context, intent );
        }
        else if( action.equals( "get_ota_info" ) ){
            getOTAInfo( context );
        }
        else if( action.equals( "run_ota_upgrade" ) ){
            runOTAUpgrade( context );
        }
        else if( action.equals( "postpone_ota_upgrade" ) ){
            postponeUpgrade( context );
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

    private void getTvChannelsFile( Context context ){
        Intent in = new Intent( context, DownloadTvChannelsService.class );
        context.startService( in );
    }

    private void downloadPreinstallApks( Context context, Intent intent ){
        Intent in = new Intent( context, DownloadPreinstallApks.class );
        in.putExtra( "json", intent.getStringExtra( "json" ) );
        context.startService( in );
    }

    private void getOTAInfo( Context context ){
        Intent in = new Intent( context, DownloadOTAService.class );
        context.startService( in );
    }

    private void runOTAUpgrade( Context context ){
        Log.d( TAG, "runOTAUpgrade()" );
        DownloadOTAService dos = new DownloadOTAService();
        dos.copyToCacheAndUpgrade();
    }

    private void postponeUpgrade( Context context ){
        Log.d( TAG, "postponeUpgrade()" );
        AlarmManager am = (AlarmManager) context.getSystemService( context.ALARM_SERVICE );
        Intent in = new Intent( "ota_download_complete" );
        in.putExtra( "show_prompt", true );
        PendingIntent pi = PendingIntent.getBroadcast( context, 0, in, 0 );
        am.cancel( pi );
        am.set( AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 20000, pi );
        //upgrade_postponed = true;
    }


    private void setConnectivityBroadcastFired( boolean is_it ){
        String s = (is_it)?"1":"0";
        Log.d( TAG, "setConnectivityBroadcastFired() : " + s );
        UtilShell.executeShellCommandWithOp( "setprop dd_br_fired " + s );
    }

    private boolean isConnectivityBroadcastFired(){
        String is_it = UtilShell.executeShellCommandWithOp( "getprop dd_br_fired" ).trim();
        return ( is_it.equals( "0" ) || is_it.equals( "" ) )?false:true;
    }


}

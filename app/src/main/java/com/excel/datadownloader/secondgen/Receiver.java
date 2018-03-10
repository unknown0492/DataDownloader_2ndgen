package com.excel.datadownloader.secondgen;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.excel.configuration.ConfigurationReader;
import com.excel.datadownloader.services.DownloadHotelLogoService;
import com.excel.datadownloader.services.DownloadOTAService;
import com.excel.datadownloader.services.DownloadPreinstallApks;
import com.excel.datadownloader.services.DownloadTvChannelsService;
import com.excel.datadownloader.services.DownloadWallpaperService;
import com.excel.excelclasslibrary.UtilSharedPreferences;
import com.excel.excelclasslibrary.UtilShell;

import static com.excel.util.Constants.IS_PERMISSION_GRANTED;
import static com.excel.util.Constants.PERMISSION_GRANTED_NO;
import static com.excel.util.Constants.PERMISSION_SPFS;

public class Receiver extends BroadcastReceiver {

    final static String TAG = "Receiver";
    Context main_context = null;
    ConfigurationReader configurationReader;
    SharedPreferences spfs;

    @Override
    public void onReceive( Context context, Intent intent ) {
        String action = intent.getAction();
        Log.d( TAG, "action : " + action );

        main_context = context;

        MainActivity ma = new MainActivity();

        /*// Check permissions before executing any broadcast, this is to prevent the app from hanging
        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            if ( ma.checkPermissions() ) {
                // permissions  granted.
                Log.d( TAG, "All permissions have been granted, just proceed !" );
            }
            else{
                startDataDownloader( context );
                return;
            }
        }*/

        // Check permissions before executing any broadcast, this is to prevent the app from hanging
        spfs = (SharedPreferences) UtilSharedPreferences.createSharedPreference( context, PERMISSION_SPFS );
        String is_permission_granted = UtilSharedPreferences.getSharedPreference( spfs, IS_PERMISSION_GRANTED, PERMISSION_GRANTED_NO ).toString().trim();
        Log.d( TAG, "Permission granted : "+is_permission_granted );

        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
            if( is_permission_granted.equals( "yes" ) ){
                Log.d( TAG, "All permissions have been granted, just proceed !" );
            }
            else{
                startDataDownloader( context );
                return;
            }
        }

        configurationReader = ConfigurationReader.reInstantiate();

        if( action.equals( "android.net.conn.CONNECTIVITY_CHANGE" ) || action.equals( "connectivity_changed" ) ){

            // 1. First time in order to receive broadcasts, the app should be started at least once
            startDataDownloader( context );

            // Execute these broadcasts only when the OTS IS COMPLETED
            String is_ots_completed = configurationReader.getIsOtsCompleted().trim();
            if( is_ots_completed.equals( "0" ) ) {
                Log.d( TAG, "OTS has not been completed, DataDownloader Broadcasts will not execute !" );
                return;
            }

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
        am.set( AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3600000, pi ); // 1 hour
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

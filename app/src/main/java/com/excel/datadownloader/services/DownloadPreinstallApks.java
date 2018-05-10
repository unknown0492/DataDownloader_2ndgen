package com.excel.datadownloader.services;

import android.app.DownloadManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.excel.configuration.ConfigurationReader;
import com.excel.configuration.PreinstallApps;
import com.excel.excelclasslibrary.RetryCounter;
import com.excel.excelclasslibrary.UtilShell;
import com.excel.excelclasslibrary.UtilURL;
import com.excel.util.MD5;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Vector;

/**
 * Created by Sohail on 04-11-2016.
 */

public class DownloadPreinstallApks extends Service {

    final static String TAG = "DownloadApks";
    Context context;
    RetryCounter retryCounter;
    DownloadManager downloadManager;
    private BroadcastReceiver receiverDownloadComplete;
    //long downloadReferences[];
    Vector<Long> downloadReferences;
    int counter = 0;
    ConfigurationReader configurationReader;
    Intent inn;
    String log = "";

    @Override
    public IBinder onBind( Intent intent ) {
        return null;
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId ) {
        Log.d( TAG, "DownloadPreinstallApks started" );
        context = this;
        configurationReader = ConfigurationReader.reInstantiate();
        retryCounter = new RetryCounter( "apks_download_count" );
        registerDownloadCompleteReceiver();

        inn = intent;
        downloadPreinstallApks();

        return START_STICKY;
    }

    private void downloadPreinstallApks(){

        /*new Thread( new Runnable(){

            @Override
            public void run(){
                String s = UtilNetwork.makeRequestForData( UtilURL.getWebserviceURL(), "POST",
                        UtilURL.getURLParamsFromPairs( new String[][]{
                                { "what_do_you_want", "get_preinstall_apps_info" },
                                { "mac_address", UtilNetwork.getMacAddress( context ) }
                        } ));

                if( s == null ){
                    Log.d( TAG, "Failed to retrieve preinstall apks" );
                    setRetryTimer();
                    return;
                }
                Log.d( TAG, "response : "+s );
                processResult( s );

            }

        } ).start();*/
        String s = inn.getStringExtra( "json" );
        Log.d( TAG, "json : " + s );
        processResult( s );



    }

    PreinstallApps papps[];
    PackageInfo pifo;
    private void processResult( String json ){
        papps = PreinstallApps.getPreinstallApps();
        for( int i = 0 ; i < papps.length ; i++ ){
            Log.d( TAG, "" + i + " : " + papps[ i ].getPackageName() );
        }
        downloadReferences = new Vector<Long>();
        downloadManager = (DownloadManager) getSystemService( Context.DOWNLOAD_SERVICE );

        // Create preinstall Directory if does not exist
        File file = new File( configurationReader.getPreinstallApksDirectoryPath( false ) );
        if( ! file.exists() )
            file.mkdirs();

        try{
            JSONArray jsonArray = new JSONArray( json );
            JSONObject jsonObject = jsonArray.getJSONObject( 0 );
            String info = jsonObject.getString( "info" );

            jsonArray = new JSONArray( info );
            for( int i = 0 ; i < jsonArray.length(); i++ ){
                jsonObject = jsonArray.getJSONObject( i );

                verifyAndDownloadApks( jsonObject, papps[ i ] );
                //Log.d( TAG, papps[ i ].getPackageName() + " - " + jsonObject.getString( "apk_url" ) );
            }
        }
        catch( Exception e ){
            e.printStackTrace();
        }
    }

    private void verifyAndDownloadApks( JSONObject jsonObject, PreinstallApps pa ){

        /**
         * Algorithm
         *
         * 1.   If the current package already exist inside /appstv_data/preinstall/ directory
         *      2.  Calculate the MD5 of the package of existing apk (old_md5)
         *      3.  If old_md5 != md5
         *          4.  Delete the existing apk
         *          5.  Download the new apk
         *          6.  Install the new apk
         * 7.   If the current package NOT exist inside /appstv_data/preinstall/ directory
         *      8.  If the current package exist inside /system/preinstall/ directory
         *          9.  Calculate the MD5 of the package of existing apk (old_md5)
         *              10. If old_md5 != md5
         *                  11.  Download the new apk
         *                  12.  Install the new apk
         *      13. If the current package NOT exist inside /system/preinstall/ directory
         *          14. Download the new apk
         *          15. Install the new apk
         *
         *
         * 1.   If the current package already exist inside /appstv_data/preinstall/ directory
         *      2. Calculate the MD5 of the package of existing apk (old_md5)
         * 3.   If the current package NOT exist inside /appstv_data/preinstall/ directory
         *      4.  If the current package exist inside /system/preinstall/ directory
         *          5.  Calculate the MD5 of the package of existing apk (old_md5)
         *
         * Note : inside the broadcast receiver, if download fails, set the retryTimer() to broadcast the entire preinstall_apps_info
         *
         */

        File package_name = new File( configurationReader.getPreinstallApksDirectoryPath( false ) + File.separator + pa.getPackageName() + ".apk" );
        File package_name_sdcard = new File( configurationReader.getPreinstallApksDirectoryPath( false ) + File.separator + pa.getPackageName() + ".apk" );

        String old_md5 = "";
        boolean need_old_md5 = false;
        //boolean delete_package = false;
        boolean is_package_from_sdcard = false;
        Log.d( TAG, "Testing for package_name : " + package_name );
        if( package_name.exists() ){
            need_old_md5 =  true;
            //delete_package = true;
            is_package_from_sdcard = true;
        }
        else{
            package_name = new File( configurationReader.getPreinstallApksDirectoryPath( true ) + File.separator + pa.getPackageName() + ".apk" );
            if( package_name.exists() ){ // packge_name exist inside system/preinstall/
                need_old_md5 =  true;
                is_package_from_sdcard = false;
                //delete_package = false;
            }
            else{
                need_old_md5 = false;
                // Download the New Apk
                try {
                    Log.i( TAG, "This seems to be a new app : " + package_name.getAbsolutePath() );
                    log += "New Apk on the CMS : "+package_name+"\n";
                    downloadSingleApk( pa.getPackageName() + ".apk", jsonObject.getString( "apk_url" ), package_name_sdcard.getAbsolutePath() );
                } catch (JSONException e) {
                    e.printStackTrace();
                }

            }
        }



        if( need_old_md5 ){
            try {
                old_md5 = MD5.getMD5Checksum( package_name );
                Log.i( TAG, "OLD MD5 calculated for " + package_name.getAbsolutePath() );
            } catch (Exception e) {
                e.printStackTrace();
            }

            String new_md5 = pa.getMD5();
            Log.i( TAG, String.format( "Old md5 %s, New md5 %s", old_md5, new_md5 ) );
            if( ! old_md5.equals( new_md5 ) ){
                if( is_package_from_sdcard ){
                    Log.e( TAG, package_name.getAbsolutePath() + " Package Deleted !" );
                    package_name.delete();
                    log += package_name + " deleted \n";
                }
                // Download the New Apk
                try {
                    downloadSingleApk( pa.getPackageName() + ".apk", jsonObject.getString( "apk_url" ), package_name_sdcard.getAbsolutePath() );

                } catch ( JSONException e ) {
                    e.printStackTrace();
                }
            }
            else{
                Log.i( TAG, "No need to download package : " + package_name.getAbsolutePath() );
                log += package_name + " is already up to date. No need to download it again !";
                if( ! ( new File( "/data/data/"+pa.getPackageName() ).exists() ) ) {
                    installApk( package_name.getAbsolutePath() );
                }
                String base_md5 = "";
                try {
                    pifo = (PackageInfo) getPackageManager().getPackageInfo(pa.getPackageName(), PackageManager.GET_META_DATA);
                    base_md5 = MD5.getMD5Checksum( new File( pifo.applicationInfo.sourceDir ) ).trim();
                    //Log.d( TAG, "AAAA : "+base_md5);
                    Log.d( TAG, "base-md5 : "+base_md5);
                }
                catch ( Exception e ){
                    e.printStackTrace();
                }

                if( ! base_md5.equals( new_md5 ) ) {
                    uninstallApk(package_name.getAbsolutePath());
                    Log.d(TAG, "Force Installing !");
                    installApk(package_name.getAbsolutePath());
                }
            }
        }



/*

        // for( int i = 0 ; i < papps.length ; i++ ){
            // Check if the package_name apk exist in /system/apk
            File package_name = new File( configurationReader.getPreinstallApksDirectoryPath( true ) + File.separator +
                    pa.getPackageName() + ".apk" );
            Log.d( TAG, "package_name : " + package_name );
            if( package_name.exists() ){
                // Check if the same apk exist in /mnt/sdcard/appstv_Data/preinstall/
                package_name = new File( configurationReader.getPreinstallApksDirectoryPath( false ) + File.separator +
                        pa.getPackageName() + ".apk" );
                Log.d( TAG, "package_name : " + package_name );
                if( package_name.exists() ){
                    // Compare the md5 of apk with the md5 of new apk
                    String old_md5 = null;
                    try {
                        old_md5 = MD5.getMD5Checksum( package_name );
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if( ! old_md5.equals( pa.getMD5() ) ){
                        // If md5 does not match, then delete the existing apk, download and install the new apk
                        package_name.delete();

                        try {
                            downloadSingleApk( pa.getPackageName() + ".apk", jsonObject.getString( "apk_url" ), package_name.getAbsolutePath() );
                        } catch ( JSONException e ) {
                            e.printStackTrace();
                        }
                    }

                }
                else{

                }
            }
            else{ // Package does not exist in /system/preinstall/
                // Check if the apk exist in /mnt/sdcard/appstv_data/preinstall/
                package_name = new File( configurationReader.getPreinstallApksDirectoryPath( false ) + File.separator +
                        pa.getPackageName() + ".apk" );
                Log.d( TAG, "package_name : " + package_name );
                if( package_name.exists() ){
                    // Compare the md5 of apk with the md5 of new apk
                    String old_md5 = null;
                    try {
                        old_md5 = MD5.getMD5Checksum( package_name );
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if( ! old_md5.equals( pa.getMD5() ) ){
                        // If md5 does not match, then delete the existing apk, download and install the new apk
                        package_name.delete();

                        try {
                            downloadSingleApk( pa.getPackageName() + ".apk", jsonObject.getString( "apk_url" ), package_name.getAbsolutePath() );
                        } catch ( JSONException e ) {
                            e.printStackTrace();
                        }
                    }

                }
                else{
                    Log.d( TAG, "4th section" );
                    try {
                        downloadSingleApk( pa.getPackageName() + ".apk", jsonObject.getString( "apk_url" ), package_name.getAbsolutePath() );
                    } catch ( JSONException e ) {
                        e.printStackTrace();
                    }
                }
            }
        //}
*/



        /*String md5 = "";
        String file_name = "";
        String file_path = "";
        ConfigurationReader configurationReader = ConfigurationReader.getInstance();
        File path = new File( configurationReader.getDigitalSignageDirectoryPath() );
        Log.d( TAG, "digital signage path : "+path.getAbsolutePath() );
        *//*File test = new File( "/storage/emulated/0/appstv_data1/graphics1/digital_signage1" );
        test.mkdirs();*//*
        downloadManager = (DownloadManager) getSystemService( Context.DOWNLOAD_SERVICE );

        if( ! path.exists() ){
            path.mkdirs();
        }

        for( int i = 0 ; i < wpi.length ; i++ ){

            md5 = wpi[ i ].getMD5();
            file_name = wpi[ i ].getFileName();
            file_path = wpi[ i ].getFilePath();

            File wallpaper = new File( path + File.separator + file_name );

            if( ! wallpaper.exists() ){
                // Download the wallpaper and save it with the File Name
                downloadSingleWallpaper( file_name, file_path, wallpaper.getAbsolutePath() );
            }
            else{   // If the wallpaper with the File Name exist, then check its MD5
                try {
                    if ( md5.equals( MD5.getMD5Checksum( wallpaper ) ) ){
                        // If md5 is the same, no need to download the wallpaper again, ignore and continue
                        Log.e( TAG, "Ignoring wallpaper : "+file_name );
                        continue;
                    }
                    // Delete the existing One
                    wallpaper.delete();
                    // Download the wallpaper and save it with the File Name
                    downloadSingleWallpaper( file_name, file_path, wallpaper.getAbsolutePath() );
                }
                catch ( Exception e ){
                    e.printStackTrace();
                    // Delete the existing One
                    wallpaper.delete();
                    // Download the wallpaper and save it with the File Name
                    downloadSingleWallpaper( file_name, file_path, wallpaper.getAbsolutePath() );
                }


            }

        }*/


    }



    private void downloadSingleApk( String file_name, String file_path, String file_save_path ){
        Log.d( TAG, "Downloading Apk : "+file_name+ " : " + UtilURL.getCMSRootPath() + file_path );
        Log.d( TAG, "File save path : "+file_save_path );
        Uri uri = Uri.parse( UtilURL.getCMSRootPath() + file_path );
        DownloadManager.Request request = new DownloadManager.Request( uri );
        request.setNotificationVisibility( DownloadManager.Request.VISIBILITY_HIDDEN );
        //request.setDestinationInExternalFilesDir( context, getExternalFilesDir( "Launcher" ).getAbsolutePath(), file_name );
        request.setDestinationUri( Uri.fromFile( new File( file_save_path ) ) );
        //downloadReferences[ counter++ ] = downloadManager.enqueue( request );
        long reff = Long.valueOf( downloadManager.enqueue( request ) );
        Log.i( TAG, "Download ref for "+ file_name +" is : " + reff );
        downloadReferences.add( reff );
    }

    private void  registerDownloadCompleteReceiver(){
        IntentFilter intentFilter = new IntentFilter( DownloadManager.ACTION_DOWNLOAD_COMPLETE );
        receiverDownloadComplete = new BroadcastReceiver() {

            @Override
            public void onReceive( Context context, Intent intent ) {
                Log.i( TAG, "inside onReceive() action : "+intent.getAction() );
                long reference = intent.getLongExtra( DownloadManager.EXTRA_DOWNLOAD_ID, -1 );
                Log.i( TAG, "current reference : " + reference );
                // String extraID = DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS;
                // long[] references = intent.getLongArrayExtra( extraID );
                for( int i = 0 ; i < downloadReferences.size() ; i++ ){
                    long ref = ( (Long)downloadReferences.get( i ) ).longValue();
                    Log.i( TAG, "refs : " + ref );
                //for( long ref : downloadReferences ){
                    if( ref == reference ){
                        //
                        DownloadManager.Query query = new DownloadManager.Query();
                        query.setFilterById( ref );
                        Cursor cursor = downloadManager.query( query );
                        cursor.moveToFirst();
                        int status = cursor.getInt( cursor.getColumnIndex( DownloadManager.COLUMN_STATUS ) );
                        int reason = cursor.getInt( cursor.getColumnIndex( DownloadManager.COLUMN_REASON ) );
                        String savedFilePath = cursor.getString( cursor.getColumnIndex( DownloadManager.COLUMN_LOCAL_FILENAME ) );
                        //savedFilePath = savedFilePath.substring( savedFilePath.lastIndexOf( "/" ) + 1, savedFilePath.length() );
                        Log.i( TAG, "Status Code : " + reason );
                        switch( status ){
                            case DownloadManager.STATUS_SUCCESSFUL:
                                Log.i( TAG, savedFilePath + " downloaded successfully !" );
                                uninstallApk( savedFilePath.substring( savedFilePath.lastIndexOf( "/" ) + 1, savedFilePath.length() ) );
                                installApk( savedFilePath );
                                break;
                            case DownloadManager.STATUS_FAILED:
                                Log.e( TAG, savedFilePath + " failed to download !" );
                                setRetryTimer();
                                break;
                            case DownloadManager.STATUS_PAUSED:
                                Log.e( TAG, savedFilePath + " download paused !" );
                                break;
                            case DownloadManager.STATUS_PENDING:
                                Log.e( TAG, savedFilePath + " download pending !" );
                                break;
                            case DownloadManager.STATUS_RUNNING:
                                Log.d( TAG, savedFilePath + " downloading !" );
                                break;

                        }
                        downloadReferences.remove( i );
                    }
                }
            }
        };
        registerReceiver( receiverDownloadComplete, intentFilter );
    }

    public void installApk( String file_path ){
        Log.d( TAG, "Installing apk : " + file_path );
        String status = UtilShell.executeShellCommandWithOp( "pm install -r " + file_path );
        //log += "Installing apk : "+ file_path + " , status : " +status+ " \n";
        Log.i( TAG, "Installing apk : "+ file_path + " , status : " +status );
    }

    public void uninstallApk( String package_name ){
        Log.d( TAG, "UnInstalling apk : " + package_name );
        String status = UtilShell.executeShellCommandWithOp( "pm uninstall " + package_name );
        Log.i( TAG, "UnInstall status : " + status );
    }

    private void setRetryTimer(){
        final long time = retryCounter.getRetryTime();

        Log.d( TAG, "time : " + time );

        new Handler( Looper.getMainLooper() ).postDelayed(new Runnable() {

            @Override
            public void run() {
                Log.d( TAG, "downloading Preinstall Apks after "+(time/1000)+" seconds !" );
                sendBroadcast( new Intent( "get_preinstall_apps_info" ) );

            }

        }, time );

    }


}

package com.excel.datadownloader.services;

import android.app.DownloadManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.excel.configuration.ConfigurationReader;
import com.excel.excelclasslibrary.RetryCounter;
import com.excel.excelclasslibrary.UtilNetwork;
import com.excel.excelclasslibrary.UtilShell;
import com.excel.excelclasslibrary.UtilURL;
import com.excel.util.Compress;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Created by Sohail on 04-11-2016.
 */

public class DownloadTvChannelsService extends Service {

    final static String TAG = "DownloadTvChannels";
    Context context;
    RetryCounter retryCounter;
    DownloadManager downloadManager;
    private BroadcastReceiver receiverDownloadComplete;
    long downloadReference;
    int counter = 0;

    @Override
    public IBinder onBind( Intent intent ) {
        return null;
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId ) {
        Log.d( TAG, "DownloadTvChannelsService started" );
        context = this;
        retryCounter = new RetryCounter( "tv_download_count" );
        registerDownloadCompleteReceiver();

        Log.d( TAG, "Starting....................." );


        // Intent LaunchIntent = getPackageManager().getLaunchIntentForPackage("com.android.providers.downloads.ui");
        //startActivity(LaunchIntent);


        downloadTvChannels();

        return START_STICKY;
    }

    private void downloadTvChannels(){
        new Thread(new Runnable(){

            @Override
            public void run(){
                String s = UtilNetwork.makeRequestForData( UtilURL.getWebserviceURL(), "POST",
                        UtilURL.getURLParamsFromPairs( new String[][]{
                                { "what_do_you_want", "get_tv_channels_file" },
                                { "mac_address", UtilNetwork.getMacAddress( context ) }
                        } ));

                if( s == null ){
                    Log.d( TAG, "Failed to retrieve Tv Channels file" );
                    setRetryTimer();
                    return;
                }
                Log.d( TAG, "response : "+s );
                processResult( s );


            }

        }).start();
    }

    private void processResult( String json ){
        try{
            JSONArray jsonArray = new JSONArray( json );
            JSONObject jsonObject = jsonArray.getJSONObject( 0 );
            String type = jsonObject.getString( "type" );
            String info = jsonObject.getString( "info" );

            if( type.equals( "error" ) ){
                Log.e( TAG, "Error : "+info );
                setRetryTimer();
                return;
            }
            else if( type.equals( "success" ) ){
                jsonObject = new JSONObject( info );
                String file_path = jsonObject.getString( "file_path" );
                String md5 = jsonObject.getString( "md5" );
                verifyAndDownloadTvChannels( md5, file_path );
            }

            retryCounter.reset();
        }
        catch( Exception e ){
            e.printStackTrace();
            setRetryTimer();
        }
    }

    private void verifyAndDownloadTvChannels( String md5, String file_path ){
        String file_name = "tv_channels.zip";
        ConfigurationReader configurationReader = ConfigurationReader.getInstance();
        File path = new File( configurationReader.getTvChannelsDirectoryPath() );
        Log.d( TAG, "TV Channels : "+path.getAbsolutePath() );
        downloadManager = (DownloadManager) getSystemService( Context.DOWNLOAD_SERVICE );

        if( ! path.exists() ){
            path.mkdirs();
        }

        File tv_channels_file = new File( path + File.separator + file_name );

        if( ! tv_channels_file.exists() ){
            // Download the TV channels File and save it with the File Name
            //downloadAndRestoreNewZip( file_name, file_path, tv_channels_file.getAbsolutePath() );
            downloadTvChannelsFile( file_name, file_path, tv_channels_file.getAbsolutePath() );
        }
        else{   // If the wallpaper with the File Name exist, then check its MD5
            try {
                /*if ( md5.equals( MD5.getMD5Checksum( tv_channels_file ) ) ){
                    // If md5 is the same, no need to download the wallpaper again, ignore and continue
                    Log.e( TAG, "Ignoring tv channels file download : "+file_path );
                    // restoreZIP();
                    return;
                }*/

                // Delete the existing One
                tv_channels_file.delete();

                // Download the TV channels File and save it with the File Name
                downloadTvChannelsFile( file_name, file_path, tv_channels_file.getAbsolutePath() );
            }
            catch ( Exception e ){
                e.printStackTrace();
                // Delete the existing One
                tv_channels_file.delete();
                // Download the TV channels File and save it with the File Name
                downloadTvChannelsFile( file_name, file_path, tv_channels_file.getAbsolutePath() );
            }
        }

    }



    private void downloadTvChannelsFile( String file_name, String file_path, String file_save_path ){
        Log.d( TAG, "Downloading TV Channels file : "+file_name );
        Uri uri = Uri.parse( UtilURL.getCMSRootPath() + file_path );
        DownloadManager.Request request = new DownloadManager.Request( uri );
        request.setNotificationVisibility( DownloadManager.Request.VISIBILITY_HIDDEN );
        //request.setDestinationInExternalFilesDir( context, getExternalFilesDir( "Launcher" ).getAbsolutePath(), file_name );
        request.setDestinationUri( Uri.fromFile( new File( file_save_path ) ) );
        downloadReference = downloadManager.enqueue( request );


    }

    /*private void downloadAndRestoreNewZip( String file_name, String file_path, String file_save_path ){
        downloadTvChannelsFile( file_name, file_path, file_save_path );
    }*/

    private void restoreZIP(){
        Log.d( TAG, "restoreZIP()" );
        ConfigurationReader configurationReader = ConfigurationReader.getInstance();

        String pid = UtilShell.executeShellCommandWithOp( "pidof com.android.dtv" ).trim();
        Log.d( TAG, "pid :"+pid+":" );

        // 1. Kill App
        UtilShell.executeShellCommandWithOp( "kill "+pid );

        // 2. Delete Existing Data
        UtilShell.executeShellCommandWithOp( "chmod -R 777 /data/hdtv" );
        UtilShell.executeShellCommandWithOp( "rm -r /data/hdtv/*" );
        //UtilShell.executeShellCommandWithOp( "mkdir /data/hdtv" );

        // 3. Kill App
        pid = UtilShell.executeShellCommandWithOp( "pidof com.android.dtv" ).trim();
        UtilShell.executeShellCommandWithOp( "kill "+pid );

        // 4. Unzip tv_channels.zip
        Compress.unZipIt( configurationReader.getTvChannelsDirectoryPath() + File.separator + "tv_channels.zip",
                configurationReader.getTvChannelsDirectoryPath() );
        Log.d( TAG, "tv_channels.zip extracted successfully" );

        // 5. Copy Extracted content into the /data/hdtv
        UtilShell.executeShellCommandWithOp( "chmod -R 777 /data/hdtv",
                //"rm -r /data/hdtv",
                "cp -r /mnt/sdcard/appstv_data/tv_channels/backup/hdtv/* /data/hdtv",
                "chmod -R 777 /data/hdtv" );

        UtilShell.executeShellCommandWithOp( "setprop is_tv_ch_restored 1" );
        Log.i( TAG, "setprop is_tv_ch_restored 1" );

        // 6. Kill App
        pid = UtilShell.executeShellCommandWithOp( "pidof com.android.dtv" ).trim();
        UtilShell.executeShellCommandWithOp( "kill "+pid );

    }

    private void  registerDownloadCompleteReceiver(){

        IntentFilter intentFilter = new IntentFilter( DownloadManager.ACTION_DOWNLOAD_COMPLETE );
        receiverDownloadComplete = new BroadcastReceiver() {

            @Override
            public void onReceive( Context context, Intent intent ) {
                Log.d( TAG, "registerDownloadCompleteReceiver() onReceive" );
                long reference = intent.getLongExtra( DownloadManager.EXTRA_DOWNLOAD_ID, -1 );
                long ref = downloadReference;
                //for( long ref : downloadReferences ){
                    if( ref == reference ){
                        //
                        DownloadManager.Query query = new DownloadManager.Query();
                        query.setFilterById( ref );
                        Cursor cursor = downloadManager.query( query );
                        cursor.moveToFirst();
                        int status = cursor.getInt( cursor.getColumnIndex( DownloadManager.COLUMN_STATUS ) );
                        String savedFilePath = cursor.getString( cursor.getColumnIndex( DownloadManager.COLUMN_LOCAL_FILENAME ) );
                        savedFilePath = savedFilePath.substring( savedFilePath.lastIndexOf( "/" ) + 1, savedFilePath.length() );
                        switch( status ){
                            case DownloadManager.STATUS_SUCCESSFUL:

                                //restoreZIP();

                                Log.i( TAG, savedFilePath + " downloaded successfully !" );
                                break;
                            case DownloadManager.STATUS_FAILED:
                                Log.e( TAG, savedFilePath + " failed to download !" );
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

                    }

            }
        };
        registerReceiver( receiverDownloadComplete, intentFilter );
    }


    private void setRetryTimer(){
        final long time = retryCounter.getRetryTime();

        Log.d( TAG, "time : " + time );

        new Handler( Looper.getMainLooper() ).postDelayed(new Runnable() {

            @Override
            public void run() {
                Log.d( TAG, "downloading Tv channels file after "+(time/1000)+" seconds !" );
                downloadTvChannels();

            }

        }, time );

    }

}

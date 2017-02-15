package com.excel.datadownloader.services;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.excel.configuration.ConfigurationReader;
import com.excel.customitems.CustomItems;
import com.excel.excelclasslibrary.RetryCounter;
import com.excel.excelclasslibrary.UtilNetwork;
import com.excel.excelclasslibrary.UtilShell;
import com.excel.excelclasslibrary.UtilURL;
import com.excel.util.MD5;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Created by Sohail on 04-11-2016.
 */

public class DownloadOTAService extends Service {

    final static String TAG = "DownloadOTA";
    Context context;
    RetryCounter retryCounter;
    DownloadManager downloadManager;
    private BroadcastReceiver receiverDownloadComplete;
    long downloadReference;
    int counter = 0;
    String new_firmware_md5 = "";
    ConfigurationReader configurationReader;
    String ota_type = "";
    int file_size = -1;
    boolean downloading = true;
    File ota_update_zip_file;

    // Script
    public static final String FIRMWARE_UPDATE_SCRIPT			= 	"echo 'boot-recovery ' > /cache/recovery/command\n" +
            "echo '--update_package=/cache/update.zip' >> /cache/recovery/command\n"+
            "reboot recovery";

    @Override
    public IBinder onBind( Intent intent ) {
        return null;
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId ) {
        Log.d( TAG, "DownloadOTAService started" );
        context = this;
        int myID = 1234;

        //This constructor is deprecated. Use Notification.Builder instead
        Notification.Builder notice = new Notification.Builder( context ).setContentTitle( "Test" );

        startForeground( myID, notice.getNotification() );


        configurationReader = ConfigurationReader.getInstance();
        retryCounter = new RetryCounter( "ota_download_count" );
        registerDownloadCompleteReceiver();

        downloadOTA();

        return START_STICKY;
    }

    private void downloadOTA(){
        new Thread(new Runnable(){

            @Override
            public void run(){
                String s = UtilNetwork.makeRequestForData( UtilURL.getWebserviceURL(), "POST",
                        UtilURL.getURLParamsFromPairs( new String[][]{
                                { "what_do_you_want", "get_ota_info" },
                                { "mac_address", UtilNetwork.getMacAddress( context ) }
                        } ));

                if( s == null ){
                    Log.d( TAG, "Failed to retrieve OTA info" );
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
                String ota_enabled = jsonObject.getString( "ota_enabled" );
                if( ota_enabled.equals( "0" ) ){
                    Log.i( TAG, "OTA has been disabled for this box !" );
                    return;
                }
                else if( ota_enabled.equals( "1" ) ){
                    String file_path = jsonObject.getString( "file_path" );
                    new_firmware_md5 = jsonObject.getString( "md5" );
                    String new_firmware_name = jsonObject.getString( "firmware_name" ).trim();
                    String firmware_name = configurationReader.getFirmwareName().trim();
                    ota_type = jsonObject.getString( "ota_type" );
                    file_size = jsonObject.getInt( "file_size" );

                    if( firmware_name.equals( new_firmware_name ) ){
                        Log.i( TAG, "No need to OTA, same firmware already exist on the box !" );
                        return;
                    }

                    verifyAndDownloadOTA( file_path );
                }
                else{
                    Log.e( TAG, "??? : " + info );
                    return;
                }


            }

            retryCounter.reset();
        }
        catch( Exception e ){
            e.printStackTrace();
            setRetryTimer();
        }
    }

    private void verifyAndDownloadOTA( String file_path ){
        String file_name = "update.zip";

        File path = new File( configurationReader.getFirmwareDirectoryPath() );
        Log.d( TAG, "Firmware Path : "+path.getAbsolutePath() );
        downloadManager = (DownloadManager) getSystemService( Context.DOWNLOAD_SERVICE );

        if( ! path.exists() ){
            path.mkdirs();
        }

        ota_update_zip_file = new File( path + File.separator + file_name );

        if( ! ota_update_zip_file.exists() ){
            // Download the OTA update.zip File and save it with the File Name
            downloadOTAZipFile( file_name, file_path, ota_update_zip_file.getAbsolutePath() );
        }
        else{
            try {
                // Delete the existing One
                ota_update_zip_file.delete();
                UtilShell.executeShellCommandWithOp( "rm " + ota_update_zip_file.getAbsolutePath() );

                // Download the OTA Zip File and save it with the File Name
                downloadOTAZipFile( file_name, file_path, ota_update_zip_file.getAbsolutePath() );
            }
            catch ( Exception e ){
                e.printStackTrace();
                // Delete the existing One
                ota_update_zip_file.delete();
                UtilShell.executeShellCommandWithOp( "rm " + ota_update_zip_file.getAbsolutePath() );
                // Download the OTA Zip File and save it with the File Name
                downloadOTAZipFile( file_name, file_path, ota_update_zip_file.getAbsolutePath() );
            }
        }

    }




    private void downloadOTAZipFile( String file_name, String file_path, String file_save_path ){
        Log.d( TAG, "Downloading OTA update.zip file : "+file_name );
        Uri uri = Uri.parse( UtilURL.getCMSRootPath() + file_path );
        DownloadManager.Request request = new DownloadManager.Request( uri );
        request.setNotificationVisibility( DownloadManager.Request.VISIBILITY_HIDDEN );
        request.setDestinationUri( Uri.fromFile( new File( file_save_path ) ) );
        downloadReference = downloadManager.enqueue( request );

        validateOTATypeAndStartOTA();


    }

    Handler handler = new Handler( Looper.getMainLooper() );

    private void validateOTATypeAndStartOTA(){
        Log.d( TAG, "OTA type : " + ota_type );

        if( ota_type.equals( "regular" ) ){
            // Show the Downloading Activity
            //Intent inn = new Intent( context, OTADownloadingActivity.class );
            Intent inn = new Intent( "show_ota_downloading" );
            //inn.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
            //inn.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            //inn.putExtra( "ota_zip_file_path", file_save_path );
            inn.putExtra( "file_size", file_size );
            sendBroadcast( inn );
            Log.d( TAG, "Show OTA Downloading Activity !" );
            //startActivity( inn );

            sendDownloadProgressToActivity();

        }
        else if( ota_type.equals( "silent" ) ){
            Log.d( TAG, "Silent OTA Downloading Started" );
            //showDownloadProgressInLogs();
            sendDownloadProgressToActivity();
        }
        else if( ota_type.equals( "regular-prompt" ) ){
            Intent inn = new Intent( "show_ota_downloading" );
            inn.putExtra( "file_size", file_size );
            sendBroadcast( inn );
            Log.d( TAG, "Show OTA Downloading Activity - regular prompt!" );

            sendDownloadProgressToActivity();
        }
    }

    private void sendDownloadProgressToActivity(){

        /*handler.post(new Runnable() {
            @Override
            public void run() {
                double progress = 0.0;
                while( downloading ){
                    DownloadManager.Query q = new DownloadManager.Query();
                    q.setFilterById( downloadReference );
                    Cursor cursor = downloadManager.query( q );
                    cursor.moveToFirst();

                    int bytes_downloaded = cursor.getInt( cursor
                            .getColumnIndex( DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR ) );
                    //int bytes_total = cursor.getInt( cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES ) );
                    try {
                        progress = ((double)bytes_downloaded/(double)file_size)*100;
                        Log.d( TAG, String.format( "Total %d.02f, Downloaded %d.02f", file_size, bytes_downloaded ) );
                    }
                    catch ( Exception e ){
                        e.printStackTrace();
                    }

                    Intent in = new Intent( "ota_progress_update" );
                    in.putExtra( "progress", progress );
                    //in.putExtra( "md5", new_firmware_md5 );
                    //LocalBroadcastManager.getInstance( context ).sendBroadcast( in );
                    context.sendBroadcast( in );

                    //CustomItems.showCustomToast( getBaseContext(), "success", "Downloading Update " +progress+"%", 2000 );
                   // Toast.makeText( getBaseContext(), "Downloading Update " +progress+"%", Toast.LENGTH_SHORT ).show();
                    Log.d( TAG, String.format( "Downloading Update : %.02f", progress )+"%" );

                    if( progress == 100.0 ){
                        downloading = false;
                        //break;
                    }

                    try {
                        Thread.sleep( 2000 );
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            }
        });*/

        new AsyncTask< Void, Void, Void >(){

            @Override
            protected Void doInBackground(Void... params) {
                new Thread(new Runnable() {
                    public void run() {

                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                double progress = 0;
                                downloading = true;
                                while( downloading ){
                                    DownloadManager.Query q = new DownloadManager.Query();
                                    q.setFilterById( downloadReference );
                                    Cursor cursor = downloadManager.query( q );
                                    cursor.moveToFirst();

                                    int bytes_downloaded = cursor.getInt( cursor
                                            .getColumnIndex( DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR ) );
                                    //int bytes_total = cursor.getInt( cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES ) );
                                    try {
                                        progress = Double.parseDouble( String.format( "%.2f",((double)bytes_downloaded/(double)file_size)*100 ) );
                                    }
                                    catch ( Exception e ){
                                        e.printStackTrace();
                                    }

                                    if( ota_type.equals( "regular" ) || ota_type.equals( "regular-prompt" ) ) {
                                        Intent in = new Intent( "ota_progress_update" );
                                        in.putExtra( "progress", progress );
                                        in.putExtra( "file_size", file_size );
                                        context.sendBroadcast( in );
                                    }
                                    /*else if( ota_type.equals( "silent" ) ){
                                        // Nothing special to do here
                                    }*/


                                    //CustomItems.showCustomToast( getBaseContext(), "success", "Downloading Update " +progress+"%", 2000 );
                                   // Toast.makeText( getBaseContext(), "Downloading Update " +progress+"%", Toast.LENGTH_SHORT ).show();
                                    Log.d( TAG, "Downloading Update " +progress+"%" );

                                    if( progress == 100.0 )
                                        downloading = false;

                                    try {
                                        Thread.sleep( 2000 );
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }

                            }
                        });




                        }
                }).start();
                return null;
            }

        }.execute();
    }

    private void showDownloadProgressInLogs(){
        new AsyncTask< Void, Void, Void >(){

            @Override
            protected Void doInBackground(Void... params) {
                new Thread(new Runnable() {
                    public void run() {

                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                double progress = 0;
                                while( downloading ){
                                    DownloadManager.Query q = new DownloadManager.Query();
                                    q.setFilterById( downloadReference );
                                    Cursor cursor = downloadManager.query( q );
                                    cursor.moveToFirst();

                                    int bytes_downloaded = cursor.getInt( cursor
                                            .getColumnIndex( DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR ) );
                                    //int bytes_total = cursor.getInt( cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES ) );
                                    try {
                                        progress = Double.parseDouble( String.format( "%.2f",((double)bytes_downloaded/(double)file_size)*100 ) );
                                    }
                                    catch ( Exception e ){
                                        e.printStackTrace();
                                    }

                                   Log.d( TAG, "Downloading Update " +progress+"%" );

                                    if( progress == 100.0 )
                                        downloading = false;

                                    try {
                                        Thread.sleep( 2000 );
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }

                            }
                        });




                    }
                }).start();
                return null;
            }

        }.execute();
    }

    private void  registerDownloadCompleteReceiver(){
        IntentFilter intentFilter = new IntentFilter( DownloadManager.ACTION_DOWNLOAD_COMPLETE );
        receiverDownloadComplete = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
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
                                Log.i( TAG, savedFilePath + " downloaded successfully !" );
                                downloading = false;
                                verifyDownload();
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
                Log.d( TAG, "downloading OTA update.zip after "+(time/1000)+" seconds !" );
                downloadOTA();

            }

        }, time );

    }


    private void verifyDownload(){

        try {
            String downloaded_md5 = MD5.getMD5Checksum( ota_update_zip_file );
            Log.d( TAG, String.format( "Original MD5 %s, Downloaded MD5 %s", new_firmware_md5, downloaded_md5 ) );

            if( ! new_firmware_md5.equals( downloaded_md5 ) ){
                CustomItems.showCustomToast( context, "error", "Download was not successful, Re-Downloading !", Toast.LENGTH_LONG );
                downloadOTA();
            }
            else{
                //LocalBroadcastManager.getInstance( context ).sendBroadcast( new Intent( "ota_download_complete" ) );

                if( ota_type.equals( "regular" ) ) {
                    Intent in = new Intent( "ota_download_complete" );
                    in.putExtra( "show_prompt", false );
                    context.sendBroadcast( in );
                    copyToCacheAndUpgrade();
                }
                else if( ota_type.equals( "silent" ) ){
                    copyToCacheAndUpgrade();
                }
                else if( ota_type.equals( "" ) ){

                }
                else if( ota_type.equals( "regular-prompt" ) ){
                    Intent in = new Intent( "ota_download_complete" );
                    in.putExtra( "show_prompt", true );
                    context.sendBroadcast( in );
                }


            }
        } catch ( Exception e ) {
            e.printStackTrace();
        }

    }

    public void copyToCacheAndUpgrade(){
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                createUpdateScript();

                copyFirmwareToCache();

                verifyCacheFirmwareCopy();
            }
        }, 2000 );
    }

    public void createUpdateScript(){
        configurationReader = ConfigurationReader.getInstance();

        // Create the script inside OTS
        File temp_file = new File( Environment.getExternalStorageDirectory() + File.separator + "up.sh" );
        if( temp_file.exists() )
            temp_file.delete();

        try {
            FileOutputStream fos = new FileOutputStream( temp_file );
            Log.d( TAG, "temp file created at : " + temp_file.getAbsolutePath());
            fos.write( FIRMWARE_UPDATE_SCRIPT.getBytes() );

            // Copy up.sh to /cache/
            UtilShell.executeShellCommandWithOp( "cp /mnt/sdcard/up.sh /cache/up.sh");

            // Set 777 permission for up.sh
            UtilShell.executeShellCommandWithOp( "chmod -R 777 /cache", "chmod 777 /cache/up.sh");

            fos.close();
        }
        catch ( Exception e ){
            e.printStackTrace();
        }
    }

    private void copyFirmwareToCache(){

        UtilShell.executeShellCommandWithOp( "chmod -R 777 /cache", "rm /cache/update.zip" );

        String s = UtilShell.executeShellCommandWithOp( "cp /mnt/sdcard/appstv_data/firmware/update.zip /cache/update.zip",
                "chmod 777 /cache/update.zip" );

        Log.d( TAG, "Firmware successfully copied to /cache : " + s );
    }

    private void verifyCacheFirmwareCopy(){

        try {
            String downloaded_md5 = MD5.getMD5Checksum( new File( "/cache/update.zip" ) );
            new_firmware_md5 = MD5.getMD5Checksum( new File( "/mnt/sdcard/appstv_data/firmware/update.zip" ) );

            Log.d( TAG, String.format( "Original MD5 %s, Downloaded MD5 %s", new_firmware_md5, downloaded_md5 ) );

            if( ! new_firmware_md5.equals( downloaded_md5 ) ){
                CustomItems.showCustomToast( context, "error", "There was an error. Trying Again !", Toast.LENGTH_LONG );
                copyFirmwareToCache();
            }
            else{
                Log.d( TAG, "All Good ! Box is Rebooting Now :)" );
                //UtilShell.executeShellCommandWithOp( "sh /cache/up.sh" );
            }
        } catch ( Exception e ) {
            e.printStackTrace();
        }

    }
}

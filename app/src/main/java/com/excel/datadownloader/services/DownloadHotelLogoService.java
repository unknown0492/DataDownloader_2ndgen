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
import com.excel.excelclasslibrary.UtilURL;
import com.excel.util.MD5;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Created by Sohail on 04-11-2016.
 */

public class DownloadHotelLogoService extends Service {

    final static String TAG = "DownloadHotelLogo";
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
        Log.d( TAG, "DownloadHotelLogoService started" );
        context = this;
        retryCounter = new RetryCounter( "logo_download_count" );
        registerDownloadCompleteReceiver();

        downloadHotelLogo();

        return START_STICKY;
    }

    private void downloadHotelLogo(){
        new Thread(new Runnable(){

            @Override
            public void run(){
                String s = UtilNetwork.makeRequestForData( UtilURL.getWebserviceURL(), "POST",
                        UtilURL.getURLParamsFromPairs( new String[][]{
                                { "what_do_you_want", "get_hotel_logo" },
                                { "mac_address", UtilNetwork.getMacAddress( context ) }
                        } ));

                if( s == null ){
                    Log.d( TAG, "Failed to retrieve Logo" );
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

                //setRetryTimer();  // because, error means that Hotel Logo Display has been TURNED OFF for this CMS
                return;
            }
            else if( type.equals( "success" ) ){
                jsonObject = new JSONObject( info );

                String show_logo = jsonObject.getString( "show_logo" );
                if( show_logo.equals( "0" ) ){
                    Log.e( TAG, "Error : Hotel Logo has not been configured yet for this box !" );
                    setRetryTimer();
                    return;
                }
                else{
                    verifyAndDownloadHotelLogo( jsonObject.getString( "md5" ), jsonObject.getString( "logo_path" ) );
                }

            }

            retryCounter.reset();
        }
        catch( Exception e ){
            e.printStackTrace();
            setRetryTimer();
        }
    }

    private void verifyAndDownloadHotelLogo( String md5, String logo_path ){
        String file_name = "hotel_logo.png";
        ConfigurationReader configurationReader = ConfigurationReader.getInstance();
        File path = new File( configurationReader.getHotelLogoDirectoryPath() );
        Log.d( TAG, "Hotel Logo path : "+path.getAbsolutePath() );
        /*File test = new File( "/storage/emulated/0/appstv_data1/graphics1/digital_signage1" );
        test.mkdirs();*/
        downloadManager = (DownloadManager) getSystemService( Context.DOWNLOAD_SERVICE );

        if( ! path.exists() ){
            path.mkdirs();
        }

        File hotel_logo = new File( path + File.separator + file_name );

        if( ! hotel_logo.exists() ){
            // Download the Logo and save it with the File Name
            downloadLogo( file_name, logo_path, hotel_logo.getAbsolutePath() );
        }
        else{   // If the wallpaper with the File Name exist, then check its MD5
            try {
                if ( md5.equals( MD5.getMD5Checksum( hotel_logo ) ) ){
                    // If md5 is the same, no need to download the wallpaper again, ignore and continue
                    Log.e( TAG, "Ignoring logo download : "+logo_path );
                    return;
                }
                // Delete the existing One
                hotel_logo.delete();
                // Download the wallpaper and save it with the File Name
                downloadLogo( file_name, logo_path, hotel_logo.getAbsolutePath() );
            }
            catch ( Exception e ){
                e.printStackTrace();
                // Delete the existing One
                hotel_logo.delete();
                // Download the wallpaper and save it with the File Name
                downloadLogo( file_name, logo_path, hotel_logo.getAbsolutePath() );
            }
        }

    }



    private void downloadLogo( String file_name, String file_path, String file_save_path ){
        Log.d( TAG, "Downloading hotel logo : "+file_name );
        Uri uri = Uri.parse( UtilURL.getCMSRootPath() + file_path );
        DownloadManager.Request request = new DownloadManager.Request( uri );
        request.setNotificationVisibility( DownloadManager.Request.VISIBILITY_HIDDEN );
        //request.setDestinationInExternalFilesDir( context, getExternalFilesDir( "Launcher" ).getAbsolutePath(), file_name );
        request.setDestinationUri( Uri.fromFile( new File( file_save_path ) ) );
        downloadReference = downloadManager.enqueue( request );
    }

    private void  registerDownloadCompleteReceiver(){
        IntentFilter intentFilter = new IntentFilter( DownloadManager.ACTION_DOWNLOAD_COMPLETE );
        receiverDownloadComplete = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
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
                Log.d( TAG, "downloading hotel logo after "+(time/1000)+" seconds !" );
                downloadHotelLogo();

            }

        }, time );

    }

}

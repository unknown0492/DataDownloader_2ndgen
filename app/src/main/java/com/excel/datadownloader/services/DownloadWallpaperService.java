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

public class DownloadWallpaperService extends Service {

    final static String TAG = "DownloadWallpaper";
    Context context;
    RetryCounter retryCounter;
    DownloadManager downloadManager;
    private BroadcastReceiver receiverDownloadComplete;
    long downloadReferences[];
    int counter = 0;

    @Override
    public IBinder onBind( Intent intent ) {
        return null;
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId ) {
        Log.d( TAG, "DownloadWallpaperService started" );
        context = this;
        retryCounter = new RetryCounter( "wallpaper_download_count" );
        registerDownloadCompleteReceiver();

        downloadWallpapers();

        return START_STICKY;
    }

    private void downloadWallpapers(){
        new Thread(new Runnable(){

            @Override
            public void run(){
                String s = UtilNetwork.makeRequestForData( UtilURL.getWebserviceURL(), "POST",
                        UtilURL.getURLParamsFromPairs( new String[][]{
                                { "what_do_you_want", "get_wallpapers" },
                                { "mac_address", UtilNetwork.getMacAddress( context ) }
                        } ));

                if( s == null ){
                    Log.d( TAG, "Failed to retrieve Wallpapers" );
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
                jsonArray = new JSONArray( info );
                WallpaperInfo wpi[] = new WallpaperInfo[ jsonArray.length() ];
                downloadReferences = new long[ jsonArray.length() ];
                for( int i = 0 ; i < jsonArray.length(); i++ ){
                    jsonObject = jsonArray.getJSONObject( i );
                    wpi[ i ] = new WallpaperInfo( jsonObject.getString( "file_name" ),
                            jsonObject.getString( "file_path" ),
                            jsonObject.getString( "md5" ) );
                    // Log.d( TAG, wpi[ i ].getFileName() + " - " + wpi[ i ].getFilePath() + " - " +wpi[ i ].getMD5() );
                }

                verifyAndDownloadWallpapers( wpi );

            }

            retryCounter.reset();
        }
        catch( Exception e ){
            e.printStackTrace();
            setRetryTimer();
        }
    }

    private void verifyAndDownloadWallpapers( WallpaperInfo[] wpi ){
        String md5 = "";
        String file_name = "";
        String file_path = "";
        ConfigurationReader configurationReader = ConfigurationReader.getInstance();
        File path = new File( configurationReader.getDigitalSignageDirectoryPath() );
        Log.d( TAG, "digital signage path : "+path.getAbsolutePath() );
        /*File test = new File( "/storage/emulated/0/appstv_data1/graphics1/digital_signage1" );
        test.mkdirs();*/
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

        }
        // After everything is downloaded, delete all the wallpapers from the directory whose names are not in the list of
        // wallpaper names received
        deleteUnwantedWallpapersFromTheBox( wpi );

        //unregisterReceiver( receiverDownloadComplete );
    }



    private void downloadSingleWallpaper( String file_name, String file_path, String file_save_path ){
        Log.d( TAG, "Downloading wallpaper : "+file_name );
        Uri uri = Uri.parse( UtilURL.getCMSRootPath() + file_path );
        DownloadManager.Request request = new DownloadManager.Request( uri );
        request.setNotificationVisibility( DownloadManager.Request.VISIBILITY_HIDDEN );
        //request.setDestinationInExternalFilesDir( context, getExternalFilesDir( "Launcher" ).getAbsolutePath(), file_name );
        request.setDestinationUri( Uri.fromFile( new File( file_save_path ) ) );
        downloadReferences[ counter++ ] = downloadManager.enqueue( request );
    }

    private void  registerDownloadCompleteReceiver(){
        IntentFilter intentFilter = new IntentFilter( DownloadManager.ACTION_DOWNLOAD_COMPLETE );
        receiverDownloadComplete = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                long reference = intent.getLongExtra( DownloadManager.EXTRA_DOWNLOAD_ID, -1 );
                // String extraID = DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS;
                // long[] references = intent.getLongArrayExtra( extraID );
                for( int i = 0 ; i < downloadReferences.length ; i++ ){
                    long ref = downloadReferences [ i ];
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
                        downloadReferences[ i ] = -1;
                    }
                }
            }
        };
        registerReceiver( receiverDownloadComplete, intentFilter );
    }

    private void deleteUnwantedWallpapersFromTheBox( WallpaperInfo[] wpi ){
        ConfigurationReader configurationReader = ConfigurationReader.getInstance();
        String digital_signage_dir = configurationReader.getDigitalSignageDirectoryPath();

        File f = new File( digital_signage_dir );
        File file[] = f.listFiles();

        for ( int i = 0; i < file.length; i++ ){
            if( file[ i ].isDirectory() )
                continue;
            // Log.d( "Files", "FileName : " + file[ i ].getName() );
            deleteFileIfNotInTheList( file[ i ].getName(), wpi );
        }

        /*for( int i = 0 ; i < wpi.length ; i++ ){

        }*/

    }

    private void deleteFileIfNotInTheList( String file_name, WallpaperInfo[] wpi ){
        int i = 0;
        ConfigurationReader configurationReader = ConfigurationReader.getInstance();
        String digital_signage_dir = configurationReader.getDigitalSignageDirectoryPath();
        File f = new File( digital_signage_dir );

        for ( i = 0; i < wpi.length; i++ ){
            /*if( ( new File( digital_signage_dir + File.separator + file_name ) ).exists() )
                break;*/
            if( file_name.equals( wpi[ i ].getFileName() ) )
                break;

        }
        if( i == wpi.length ){
            File file = new File( digital_signage_dir + File.separator + file_name );
            file.delete();
            Log.i( TAG, "Deleted : "+file.getAbsolutePath() );
        }

    }

    private void setRetryTimer(){
        final long time = retryCounter.getRetryTime();

        Log.d( TAG, "time : " + time );

        new Handler( Looper.getMainLooper() ).postDelayed(new Runnable() {

            @Override
            public void run() {
                Log.d( TAG, "downloading wallpapers config after "+(time/1000)+" seconds !" );
                /*GetLauncherConfig glc = new GetLauncherConfig();
                glc.execute( "" );*/
                downloadWallpapers();

            }

        }, time );

    }

    class WallpaperInfo{
        private String file_name;
        private String file_path;
        private String md5;

        public WallpaperInfo( String file_name, String file_path, String md5 ){
            this.setFileName(file_name);
            this.setFilePath(file_path);
            this.setMD5(md5);
        }


        public String getFileName() {
            return file_name;
        }

        public void setFileName(String file_name) {
            this.file_name = file_name;
        }

        public String getFilePath() {
            return file_path;
        }

        public void setFilePath(String file_path) {
            this.file_path = file_path;
        }

        public String getMD5() {
            return md5;
        }

        public void setMD5(String md5) {
            this.md5 = md5;
        }
    }
}

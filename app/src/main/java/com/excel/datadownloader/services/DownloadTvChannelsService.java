package com.excel.datadownloader.services;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.excel.configuration.ConfigurationReader;
import com.excel.datadownloader.secondgen.R;
import com.excel.excelclasslibrary.RetryCounter;
import com.excel.excelclasslibrary.UtilMisc;
import com.excel.excelclasslibrary.UtilNetwork;
import com.excel.excelclasslibrary.UtilShell;
import com.excel.excelclasslibrary.UtilURL;
import com.excel.util.Compress;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

import static com.excel.excelclasslibrary.Constants.APPSTVLAUNCHER_PACKAGE_NAME;
import static com.excel.excelclasslibrary.Constants.APPSTVLAUNCHER_RECEIVER_NAME;

public class DownloadTvChannelsService extends Service {
    static final String TAG = "DownloadTvChannels";
    Context context;
    int counter = 0;
    DownloadManager downloadManager;
    long downloadReference;
    private BroadcastReceiver receiverDownloadComplete;
    RetryCounter retryCounter;

    public IBinder onBind(Intent intent) {
        return null;
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d( TAG, "DownloadTvChannelsService started" );

        context = this;
        retryCounter = new RetryCounter("tv_download_count" );
        registerDownloadCompleteReceiver();

        downloadTvChannels();

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Bitmap icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher );

        NotificationManager notificationManager = (NotificationManager) getSystemService( Context.NOTIFICATION_SERVICE );
        NotificationCompat.Builder notificationBuilder;
        notificationBuilder = new NotificationCompat.Builder(this, "test" );
        notificationBuilder.setSmallIcon( R.drawable.ic_launcher );
        notificationManager.notify(0, notificationBuilder.build() );

        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            NotificationChannel channel = new NotificationChannel("test", TAG, NotificationManager.IMPORTANCE_HIGH );
            notificationManager.createNotificationChannel( channel );

            Notification notification = new Notification.Builder( getApplicationContext(), "test" ).build();
            startForeground(1, notification );
        }
        else {
            // startForeground(1, notification);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterReceiver( receiverDownloadComplete );
    }

    /* access modifiers changed from: private */
    public void downloadTvChannels() {

        new Thread(new Runnable() {

            public void run() {
                String s = UtilNetwork.makeRequestForData(
                        UtilURL.getWebserviceURL(),
                        "POST",
                        UtilURL.getURLParamsFromPairs( new String[][]{
                                new String[]{"what_do_you_want", "get_tv_channels_file"},
                                new String[]{"mac_address", UtilNetwork.getMacAddress( context ) } } ) );

                if ( s == null ) {
                    Log.d( TAG, "Failed to retrieve TV Channels File" );
                    setRetryTimer();
                    return;
                }
                Log.d( TAG, "response : " + s );
                processResult( s );
            }
        }).start();
    }

    /* access modifiers changed from: private */
    public void processResult( String json ) {
        try {
            JSONObject jsonObject = new JSONArray( json ).getJSONObject(0 );
            String type = jsonObject.getString("type");
            String info = jsonObject.getString("info");

            if ( type.equals( "error" ) ) {
                Log.e( TAG, "Error : " + info );
                setRetryTimer();
                return;
            }
            if ( type.equals( "success" ) ) {
                JSONObject jsonObject2 = new JSONObject( info );
                verifyAndDownloadTvChannels( jsonObject2.getString("md5" ), jsonObject2.getString("file_path" ) );
            }
            retryCounter.reset();
        }
        catch ( Exception e ) {
            e.printStackTrace();
            setRetryTimer();
        }
    }

    private void verifyAndDownloadTvChannels( String md5, String file_path ) {

        String file_name = "tv_channels.zip";
        File path = new File( ConfigurationReader.getInstance().getTvChannelsDirectoryPath() );
        Log.d( TAG, "TV Channels : " + path.getAbsolutePath() );
        downloadManager = (DownloadManager) getSystemService( DOWNLOAD_SERVICE );

        if ( !path.exists() ){
            path.mkdirs();
        }

        File tv_channels_file = new File( path + File.separator + file_name );
        if ( !tv_channels_file.exists() ){
            downloadTvChannelsFile( file_name, file_path, tv_channels_file.getAbsolutePath() );
            return;
        }
        try {
            tv_channels_file.delete();
            downloadTvChannelsFile( file_name, file_path, tv_channels_file.getAbsolutePath() );
        }
        catch ( Exception e ) {
            e.printStackTrace();
            tv_channels_file.delete();
            downloadTvChannelsFile( file_name, file_path, tv_channels_file.getAbsolutePath() );
        }
    }

    private void downloadTvChannelsFile( String file_name, String file_path, String file_save_path ){

        Log.d( TAG, "Downloading TV Channels file : " + file_name );
        Request request = new Request( Uri.parse(UtilURL.getCMSRootPath() + file_path ) );
        request.setNotificationVisibility( Request.VISIBILITY_HIDDEN );
        request.setDestinationUri( Uri.fromFile( new File( file_save_path ) ) );
        downloadReference = downloadManager.enqueue( request );

    }

    private void restoreZIP() {

        Log.d( TAG, "restoreZIP()" );
        ConfigurationReader configurationReader = ConfigurationReader.getInstance();
        String pid = UtilShell.executeShellCommandWithOp("pidof com.android.dtv").trim();
        Log.d( TAG, "pid :" + pid + ":" );

        UtilShell.executeShellCommandWithOp( "kill " + pid );
        UtilShell.executeShellCommandWithOp("chmod -R 777 /data/hdtv" );
        UtilShell.executeShellCommandWithOp("rm -r /data/hdtv/*" );

        pid = UtilShell.executeShellCommandWithOp("pidof com.android.dtv" ).trim();
        UtilShell.executeShellCommandWithOp( "kill " + pid );

        Compress.unZipIt( configurationReader.getTvChannelsDirectoryPath() + File.separator + "tv_channels.zip", configurationReader.getTvChannelsDirectoryPath());

        Log.d( TAG, "tv_channels.zip extracted successfully");

        UtilShell.executeShellCommandWithOp("chmod -R 777 /data/hdtv", "cp -r /mnt/sdcard/appstv_data/tv_channels/backup/hdtv/* /data/hdtv", "chmod -R 777 /data/hdtv");
        UtilShell.executeShellCommandWithOp("setprop is_tv_ch_restored 1");

        Log.i( TAG, "setprop is_tv_ch_restored 1");

        pid = UtilShell.executeShellCommandWithOp("pidof com.android.dtv").trim();
        UtilShell.executeShellCommandWithOp( "kill " + pid );
    }

    private void registerDownloadCompleteReceiver() {

        IntentFilter intentFilter = new IntentFilter("android.intent.action.DOWNLOAD_COMPLETE" );

        receiverDownloadComplete = new BroadcastReceiver() {

            public void onReceive( Context context, Intent intent ) {

                Log.d( TAG, "registerDownloadCompleteReceiver() onReceive" );

                long reference = intent.getLongExtra("extra_download_id", -1 );
                //long ref = downloadReference;

                if ( downloadReference == reference ) {

                    Query query = new Query();
                    query.setFilterById(downloadReference);
                    Cursor cursor = DownloadTvChannelsService.this.downloadManager.query(query);
                    cursor.moveToFirst();

                    String downloadedFileURI = cursor.getString( cursor.getColumnIndex( DownloadManager.COLUMN_LOCAL_URI ) );

                    int status = cursor.getInt( cursor.getColumnIndex( DownloadManager.COLUMN_STATUS ) );

                    File savedFile = new File( Uri.parse( downloadedFileURI ).getPath() );
                    String savedFilePath = savedFile.getAbsolutePath();//cursor.getString( cursor.getColumnIndex( DownloadManager.COLUMN_LOCAL_FILENAME ) );
                    String fileName = savedFilePath.substring( savedFilePath.lastIndexOf( "/" ) + 1, savedFilePath.length() );

                    switch( status ){
                        case DownloadManager.STATUS_SUCCESSFUL:
                            Log.i( TAG,  fileName + " downloaded successfully !" );
                            unregisterReceiver( receiverDownloadComplete );
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

    /* access modifiers changed from: private */
    public void setRetryTimer() {
        final long time = retryCounter.getRetryTime();
        Log.d( TAG, "time : " + time );

        new Handler( Looper.getMainLooper()).postDelayed(new Runnable() {

            public void run() {
                Log.d( TAG, "Downloading Tv channels file after " + (time / 1000) + " seconds !" );
                downloadTvChannels();
            }

        }, time );
    }
}

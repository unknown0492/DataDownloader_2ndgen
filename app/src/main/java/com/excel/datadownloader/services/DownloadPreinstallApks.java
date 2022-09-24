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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;


import androidx.core.app.NotificationCompat;

import com.excel.configuration.ConfigurationReader;
import com.excel.configuration.PreinstallApps;
import com.excel.datadownloader.secondgen.R;
import com.excel.excelclasslibrary.RetryCounter;
import com.excel.excelclasslibrary.UtilFile;
import com.excel.excelclasslibrary.UtilShell;
import com.excel.excelclasslibrary.UtilURL;
import com.excel.util.MD5;
import java.io.File;
import java.util.Vector;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DownloadPreinstallApks extends Service {

    static final String TAG = "DownloadApks";
    ConfigurationReader configurationReader;
    Context context;
    int counter = 0;
    DownloadManager downloadManager;
    Vector<Long> downloadReferences;
    Intent inn;
    String log = "";
    PreinstallApps[] papps;
    PackageInfo pifo;
    private BroadcastReceiver receiverDownloadComplete = null;
    RetryCounter retryCounter;

    public IBinder onBind(Intent intent) {
        return null;
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "DownloadPreinstallApks started");
        context = this;
        configurationReader = ConfigurationReader.reInstantiate();
        retryCounter = new RetryCounter("apks_download_count");
        registerDownloadCompleteReceiver();
        inn = intent;
        downloadPreinstallApks();

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Bitmap icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder notificationBuilder;
        notificationBuilder = new NotificationCompat.Builder(this, "test" );
        notificationBuilder.setSmallIcon( R.drawable.ic_launcher );
        notificationManager.notify(0, notificationBuilder.build());

        if ( android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O ) {
            NotificationChannel channel = new NotificationChannel( "test", TAG, NotificationManager.IMPORTANCE_LOW );
            notificationManager.createNotificationChannel( channel );

            Notification notification = new Notification.Builder( getApplicationContext(),"test" ).build();
            startForeground( 1, notification );
        }
        else{
            // startForeground(1, notification);
        }
    }

    private void downloadPreinstallApks() {
        String s = inn.getStringExtra("json" );
        if( s != null ) {
            Log.d( TAG, "json : " + s );
            new AsyncTask<Object, Void, Void>(){

                @Override
                protected Void doInBackground(Object... objects) {
                    processResult((String)objects[ 0 ]);
                    return null;
                }

            }.execute( s );
            // processResult(s);
        }
    }

    private void processResult( String json ) {
        this.papps = PreinstallApps.getPreinstallApps();
        for ( int i = 0; i < this.papps.length; i++ ) {
            Log.d( TAG, " " + i + " : " + papps[ i ].getPackageName() );
        }
        downloadReferences = new Vector<>();
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE );
        File file = new File( configurationReader.getPreinstallApksDirectoryPath(false ) );
        if ( !file.exists() ) {
            file.mkdirs();
        }
        try {
            JSONArray jsonArray = new JSONArray( new JSONArray( json ).getJSONObject(0 ).getString("info" ) );
            for ( int i2 = 0; i2 < jsonArray.length(); i2++ ) {
                /*new AsyncTask<Object, Void, Void>(){

                    @Override
                    protected Void doInBackground( Object... objs ) {
                        JSONArray ja = (JSONArray) objs[ 0 ];
                        int i = (int) objs[ 1 ];
                        try {
                            verifyAndDownloadApks( ja.getJSONObject( i ), papps[ i ] );
                        } catch ( JSONException e ) {
                            e.printStackTrace();
                        }
                        return null;
                    }

                }.execute( jsonArray, i2 );*/
                verifyAndDownloadApks( jsonArray.getJSONObject( i2 ), papps[ i2 ] );
            }
        }
        catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    private void verifyAndDownloadApks( JSONObject jsonObject, PreinstallApps pa ) {
        boolean need_old_md5;
        // File package_name = new File( configurationReader.getPreinstallApksDirectoryPath(false ) + File.separator + pa.getPackageName() + ".apk" );
        File package_name = new File( "/data/data" + File.separator + pa.getPackageName()  );
        File package_name_sdcard = new File( configurationReader.getPreinstallApksDirectoryPath(false) + File.separator + pa.getPackageName() + ".apk" );
        String old_md5 = "";
        boolean is_package_from_sdcard = false;
        Log.d( TAG, "Testing for package_name : " + package_name );

        if( package_name.exists() ){
            try {
                PackageManager pm = context.getPackageManager();
                PackageInfo pInfo = pm.getPackageInfo( pa.getPackageName(), 0 );
                String versionNameOld = pInfo.versionName;
                String versionNameNew = jsonObject.getString( "version" );
                Log.d( TAG, pa.getPackageName() + "; Old version: " + versionNameOld + ", New version: " + versionNameNew );

                if( !versionNameOld.equals( versionNameNew ) ){
                    // Download the New APK
                    Log.d( TAG, "This seems to be a new app : " + package_name.getAbsolutePath() );
                    Log.d( TAG, "New Apk on the CMS : " + pa.getPackageName() );

                    String appName = pa.getPackageName() + ".apk";
                    downloadSingleApk( appName, jsonObject.getString("apk_url" ), package_name_sdcard.getAbsolutePath() );
                }
                else{
                    Log.i( TAG, "No need to download the package: " + pa.getPackageName() );
                }

            }
            catch( Exception e ) {
                e.printStackTrace();
            }
        }
        else{
            Log.d( TAG, "New Apk on the CMS : " + pa.getPackageName() );

            String appName = pa.getPackageName() + ".apk";
            try {
                downloadSingleApk(appName, jsonObject.getString("apk_url"), package_name_sdcard.getAbsolutePath());
            }
            catch( Exception e ) {
                e.printStackTrace();
            }
        }
/*
        if( package_name.exists() ){
            need_old_md5 = true;
            is_package_from_sdcard = true;
        }
        else {
            package_name = new File( configurationReader.getPreinstallApksDirectoryPath(true ) + File.separator + pa.getPackageName() + ".apk" );
            if( package_name.exists() ){
                need_old_md5 = true;
                is_package_from_sdcard = false;
            }
            else{
                need_old_md5 = false;
                try {
                    Log.d( TAG, "This seems to be a new app : " + package_name.getAbsolutePath() );
                    Log.d( TAG, "New Apk on the CMS : " + pa.getPackageName() );

                    String appName = pa.getPackageName() + ".apk";
                    downloadSingleApk( appName, jsonObject.getString("apk_url" ), package_name_sdcard.getAbsolutePath());
                }
                catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        if ( need_old_md5 ){
            try{
                old_md5 = MD5.getMD5Checksum(package_name);
                Log.i( TAG, "OLD MD5 calculated for " + package_name.getAbsolutePath() );
            }
            catch ( Exception e2 ){
                e2.printStackTrace();
            }

            String new_md5 = pa.getMD5();
            Log.i( TAG, String.format( "Old md5 %s, New md5 %s", old_md5, new_md5 ) );

            if ( !old_md5.equals( new_md5 ) ) {

                if ( is_package_from_sdcard ) {

                    Log.e( TAG, package_name.getAbsolutePath() + " Package Deleted !" );
                    package_name.delete();
                }
                try {
                    downloadSingleApk( pa.getPackageName() + ".apk", jsonObject.getString("apk_url"), package_name_sdcard.getAbsolutePath());
                }
                catch ( JSONException e3 ){
                    e3.printStackTrace();
                }
            }
            else {

                Log.i( TAG, "No need to download package : " + package_name.getAbsolutePath() );
                Log.i( TAG, package_name + " is already up to date. No need to download it again !" );

                String installPath = "/data/data/" + pa.getPackageName();
                if ( !(new File( installPath )).exists() ) {
                    installApk( package_name.getAbsolutePath() );
                }
                String base_md5 = "";
                try {
                    pifo = getPackageManager().getPackageInfo( pa.getPackageName(), PackageManager.GET_META_DATA );
                    base_md5 = MD5.getMD5Checksum( new File( pifo.applicationInfo.sourceDir ) ).trim();
                    Log.d( TAG, "base-md5 : " + base_md5);
                }
                catch ( Exception e4 ) {
                    e4.printStackTrace();
                }
                if ( !base_md5.equals( new_md5 ) ){
                    uninstallApk( pa.getPackageName() );
                    Log.d( TAG, "Force Installing !" );
                    installApk( package_name.getAbsolutePath() );
                }
            }
        }*/
    }

    private void downloadSingleApk( String file_name, String file_path, String file_save_path ) {
        Log.d( TAG, "Downloading Apk : " + file_name );
        Log.d( TAG, "File save path : " + file_save_path );
        Request request = new Request( Uri.parse( UtilURL.getCMSRootPath() + file_path ) );
        request.setNotificationVisibility( Request.VISIBILITY_HIDDEN );
        // The below changes have been due to Android 10, as the DownloadManager cannot download file in custom directory
        // So, we have to download it in the Apps packge on external storage, then copy the files to dedicated directory
        File file_save_temp_path = new File( context.getExternalFilesDir( "preinstall" ).getAbsolutePath() + File.separator + file_name );
        file_save_temp_path.delete();
        request.setDestinationUri( Uri.fromFile( file_save_temp_path ) );
        long reff = downloadManager.enqueue( request );
        Log.i( TAG, "Download ref for " + file_name + " is : " + reff );
        downloadReferences.add( reff );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if( receiverDownloadComplete != null ) {
            unregisterReceiver(receiverDownloadComplete);
            receiverDownloadComplete = null;
        }
    }

    private void registerDownloadCompleteReceiver() {
        IntentFilter intentFilter = new IntentFilter("android.intent.action.DOWNLOAD_COMPLETE");

        receiverDownloadComplete = new BroadcastReceiver() {

            public void onReceive( Context context, Intent intent ) {

                Log.i( TAG, "inside onReceive() action : "+ intent.getAction() );

                long reference = intent.getLongExtra("extra_download_id", -1);
                Log.i( TAG, "current reference : " + reference );

                for ( int i = 0; i < downloadReferences.size(); i++ ) {

                    long ref = downloadReferences.get( i );
                    Log.i( TAG, "refs : " + ref );

                    if ( ref == reference ) {
                        Query query = new Query();
                        query.setFilterById( ref );
                        Cursor cursor = downloadManager.query( query );
                        cursor.moveToFirst();

                        int status = cursor.getInt( cursor.getColumnIndex( DownloadManager.COLUMN_STATUS ) ); //cursor.getInt( cursor.getColumnIndex( NotificationCompat.CATEGORY_STATUS ) );
                        int reason = cursor.getInt( cursor.getColumnIndex( DownloadManager.COLUMN_REASON ) );

                        String downloadedFileURI = cursor.getString( cursor.getColumnIndex( DownloadManager.COLUMN_LOCAL_URI ) );
                        File savedFile = new File( Uri.parse( downloadedFileURI ).getPath() );
                        String savedFilePath = savedFile.getAbsolutePath();
                        String fileName = savedFilePath.substring( savedFilePath.lastIndexOf( "/" ) + 1 );

                        Log.i( TAG, "Status Code : " + reason );

                        switch( status ){
                            case DownloadManager.STATUS_PAUSED:
                                Log.e( TAG, fileName + " download paused !" );
                                break;

                            case DownloadManager.STATUS_SUCCESSFUL:
                                Log.i( TAG, fileName + " downloaded successfully !" );
                                uninstallApk( fileName );
                                installApk( savedFilePath );
                                if( receiverDownloadComplete != null ) {
                                    unregisterReceiver(receiverDownloadComplete);
                                    receiverDownloadComplete = null;
                                }
                                // The below changes have been due to Android 10, as the DownloadManager cannot download file in custom directory
                                // So, we have to download it in the Apps package on external storage, then copy the files to dedicated directory
                                File file_save_temp_path = new File( context.getExternalFilesDir( "preinstall" ).getAbsolutePath() + File.separator + fileName );
                                Log.i( TAG, "APK Saved Path: " + file_save_temp_path.getAbsolutePath() );
                                ConfigurationReader configurationReader = ConfigurationReader.getInstance();
                                File path = new File( configurationReader.getPreinstallApksDirectoryPath(false) + File.separator + fileName );
                                //File wallpaper = new File( path + File.separator + fileName );
                                //UtilFile.copyFile( file_save_temp_path, wallpaper );
                                file_save_temp_path.delete();
                                break;

                            case DownloadManager.STATUS_PENDING:
                                Log.e( TAG, fileName + " download pending !" );
                                break;

                            case DownloadManager.STATUS_RUNNING:
                                Log.d( TAG, fileName + " downloading !" );
                                break;

                            case DownloadManager.STATUS_FAILED:
                                Log.e( TAG, fileName + " failed to download !" );
                                setRetryTimer();
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
        Log.i( TAG, "Installing apk : "  + file_path + " , status : " + status );

    }

    public void uninstallApk( String package_name ) {
        // The package_name may contain .apk in it, so process the file name first
        String fileName = "";

        if( package_name.contains( ".apk" ) ) {
            fileName = package_name.substring(0, package_name.lastIndexOf(".apk"));
        }

        Log.d( TAG, "UnInstalling apk : " + fileName );
        String status = UtilShell.executeShellCommandWithOp( "pm uninstall " + fileName );
        Log.i( TAG, "UnInstall status : " + status );
    }

    /* access modifiers changed from: private */
    public void setRetryTimer() {
        final long time = retryCounter.getRetryTime();
        Log.d( TAG, "time : " + time );
        new Handler( Looper.getMainLooper()).postDelayed(new Runnable() {

            public void run() {
                Log.d( TAG, "Downloading Preinstall Apks after " + (time / 1000) + " seconds !" );
                sendBroadcast( new Intent("get_preinstall_apps_info" ) );
            }
        }, time );
    }
}

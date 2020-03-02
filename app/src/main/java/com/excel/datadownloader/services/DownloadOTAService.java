package com.excel.datadownloader.services;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.excel.configuration.ConfigurationReader;
import com.excel.customitems.CustomItems;
import com.excel.datadownloader.secondgen.MainActivity;
import com.excel.datadownloader.secondgen.R;
import com.excel.excelclasslibrary.Constants;
import com.excel.excelclasslibrary.RetryCounter;
import com.excel.excelclasslibrary.UtilMisc;
import com.excel.excelclasslibrary.UtilNetwork;
import com.excel.excelclasslibrary.UtilShell;
import com.excel.excelclasslibrary.UtilURL;
import com.excel.util.MD5;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import static androidx.core.app.NotificationCompat.DEFAULT_VIBRATE;

public class DownloadOTAService extends Service {
    public static final String FIRMWARE_UPDATE_SCRIPT = "echo 'boot-recovery ' > /cache/recovery/command\necho '--update_package=/cache/update.zip' >> /cache/recovery/command\nreboot recovery";
    static final String TAG = "DownloadOTA";
    ConfigurationReader configurationReader;
    Context context;
    int counter = 0;
    DownloadManager downloadManager;
    long downloadReference;
    boolean downloading = true;
    int file_size = -1;
    Handler handler = new Handler(Looper.getMainLooper());
    String new_firmware_md5 = "";
    String ota_type = "";
    File ota_update_zip_file;
    private BroadcastReceiver receiverDownloadComplete;
    RetryCounter retryCounter;
    double total;

    private static final String CHANNEL_ID = "1250012";

    public IBinder onBind( Intent intent ){
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Bitmap icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);
        /*
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);

        NotificationCompat.Builder notificationBuilder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID);
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, TAG, NotificationManager.IMPORTANCE_DEFAULT);
            notificationChannel.enableVibration(true);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(notificationChannel);
        } else {
            notificationBuilder =  new NotificationCompat.Builder(this);
        }

        notificationBuilder
//                .setContentTitle(notification.getTitle())
                .setContentText(String.format("R.string.workfield_driver_refuse"))
                // .setDefaults(DEFAULT_SOUND | DEFAULT_VIBRATE)
                .setAutoCancel(true)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setContentIntent(pendingIntent)
                .setLargeIcon(icon)
                .setColor(Color.RED)
                .setSmallIcon(R.mipmap.ic_launcher);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(0, notificationBuilder.build());*/

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder notificationBuilder;
        notificationBuilder = new NotificationCompat.Builder(this, "test");
        notificationBuilder.setSmallIcon( R.drawable.ic_launcher );
        notificationManager.notify(0, notificationBuilder.build());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel( "test",TAG, NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel( channel );

            Notification notification = new Notification.Builder(getApplicationContext(),"test").build();
            startForeground(1, notification);
        }
        else {

            // startForeground(1, notification);
        }
    }

    public int onStartCommand(Intent intent, int flags, int startId ){
        Log.d( TAG, "DownloadOTAService started" );
        context = this;
        configurationReader = ConfigurationReader.getInstance();
        retryCounter = new RetryCounter("ota_download_count");
        registerDownloadCompleteReceiver();
        downloadOTA();

        return START_STICKY;
    }

    /* access modifiers changed from: private */
    public void downloadOTA() {
        new Thread( new Runnable() {
            public void run() {
                String s = UtilNetwork.makeRequestForData(UtilURL.getWebserviceURL(), "POST", UtilURL.getURLParamsFromPairs(new String[][]{new String[]{"what_do_you_want", "get_ota_info"}, new String[]{"mac_address", UtilNetwork.getMacAddress(DownloadOTAService.this.context)}}));
                if ( s == null ) {
                    Log.d( TAG, "Failed to retrieve OTA info" );
                    setRetryTimer();
                    return;
                }
                Log.d( TAG, "response : " + s );
                processResult( s );
            }
        }).start();
    }

    /* access modifiers changed from: private */
    public void processResult( String json ){
        try {
            try {
                JSONObject jsonObject = new JSONArray( json ).getJSONObject(0 );
                String type = jsonObject.getString("type" );
                String info = jsonObject.getString("info" );
                if( type.equals( "error" ) ) {
                    Log.e( TAG, "Error : " + info );
                    setRetryTimer();
                    return;
                }
                if ( type.equals( "success" ) ) {

                    JSONObject jsonObject2 = new JSONObject( info );
                    String ota_enabled = jsonObject2.getString("ota_enabled" );

                    if( ota_enabled.equals( "0" ) ){
                        Log.i( TAG, "OTA has been disabled for this box !" );
                        return;
                    }
                    else if( ota_enabled.equals( "1" ) ){

                        String file_path = jsonObject2.getString("file_path" );
                        new_firmware_md5 = jsonObject2.getString("md5" );

                        String new_firmware_name = jsonObject2.getString("firmware_name" ).trim();
                        String firmware_name = configurationReader.getFirmwareName().trim();

                        ota_type = jsonObject2.getString("ota_type" );
                        file_size = jsonObject2.getInt("file_size" );

                        double d = (double) file_size;

                        d = Double.valueOf( (d/1024.0d)/1024.0d );
                        total = Double.parseDouble( String.format( "%.2f", d ) );

                        if ( firmware_name.equals( new_firmware_name ) ) {
                            Log.i( TAG, "No need to OTA, same firmware already exist on the box !" );
                            return;
                        }
                        verifyAndDownloadOTA( file_path );
                    }
                    else {
                        Log.e( TAG, "info : " + info );
                        return;
                    }
                }
                retryCounter.reset();
            }
            catch ( Exception e ) {
                e.printStackTrace();
                setRetryTimer();
            }
        }
        catch ( Exception e ) {
            e.printStackTrace();
            setRetryTimer();
        }
    }

    private void verifyAndDownloadOTA( String file_path ){
        String file_name = "update.zip";
        File path = new File( configurationReader.getFirmwareDirectoryPath() );
        Log.d( TAG, "Firmware Path : " + path.getAbsolutePath() );

        downloadManager = (DownloadManager) getSystemService( DOWNLOAD_SERVICE );
        if ( !path.exists() ){
            path.mkdirs();
        }
        ota_update_zip_file = new File( path + File.separator + file_name );
        if ( !ota_update_zip_file.exists() ){
            downloadOTAZipFile( file_name, file_path, ota_update_zip_file.getAbsolutePath() );
            return;
        }
        try {
            ota_update_zip_file.delete();
            UtilShell.executeShellCommandWithOp( "rm " + ota_update_zip_file.getAbsolutePath() );
            downloadOTAZipFile( file_name, file_path, ota_update_zip_file.getAbsolutePath() );
        }
        catch ( Exception e ) {
            e.printStackTrace();

            ota_update_zip_file.delete();

            UtilShell.executeShellCommandWithOp( "rm " + ota_update_zip_file.getAbsolutePath() );
            downloadOTAZipFile( file_name, file_path, ota_update_zip_file.getAbsolutePath() );
        }
    }

    private void downloadOTAZipFile( String file_name, String file_path, String file_save_path ){
        Log.d( TAG, "Downloading OTA update.zip file : " + file_name );

        Request request = new Request( Uri.parse(UtilURL.getCMSRootPath() + file_path));
        // request.setNotificationVisibility( 2);
        request.setNotificationVisibility( NotificationCompat.VISIBILITY_PUBLIC );
        request.setDestinationUri( Uri.fromFile( new File( file_save_path ) ) );

        downloadReference = downloadManager.enqueue( request );
        validateOTATypeAndStartOTA();
    }

    private void validateOTATypeAndStartOTA() {

        Log.d( TAG, "OTA type : " + ota_type );
        if ( ota_type.equals( "regular" ) ) {

            // sendBroadcast( new Intent( "show_ota_downloading" ) );
            UtilMisc.sendExplicitExternalBroadcast( context, "show_ota_downloading", Constants.DISPLAYPROJECT_PACKAGE_NAME, Constants.DISPLAYPROJECT_RECEIVER_NAME );
            Log.d( TAG, "Show OTA Downloading Activity !" );
            sendDownloadProgressToActivity();                           // Sending this to DisplayProject app

        }
        else if ( ota_type.equals( "silent" ) || ota_type.equals( "silent-prompt" ) ){
            Log.d( TAG, "Silent OTA Downloading Started" );
            sendDownloadProgressToActivity();                           // Sending this to DisplayProject app
        }
        else if( ota_type.equals( "regular-prompt" ) ){
            // sendBroadcast( new Intent("show_ota_downloading" ) );
            UtilMisc.sendExplicitExternalBroadcast( context, "show_ota_downloading", Constants.DISPLAYPROJECT_PACKAGE_NAME, Constants.DISPLAYPROJECT_RECEIVER_NAME );
            Log.d( TAG, "Show OTA Downloading Activity - regular prompt!" );
            sendDownloadProgressToActivity();                           // Sending this to DisplayProject app
        }
    }

    private void sendDownloadProgressToActivity() {
        new AsyncTask<Void, Void, Void>() {
            /* access modifiers changed from: protected */
            public Void doInBackground(Void... params) {
                new Thread(new Runnable() {
                    public void run() {
                        DownloadOTAService.this.handler.post(new Runnable() {
                            public void run() {
                                double progress = 0.0d;
                                downloading = true;

                                while ( downloading ) {
                                    Query q = new Query();
                                    q.setFilterById(new long[]{downloadReference});
                                    Cursor cursor = downloadManager.query(q);
                                    cursor.moveToFirst();
                                    try {
                                        double bytesSoFar       = (double) cursor.getInt(cursor.getColumnIndex("bytes_so_far"));
                                        double fileSize         = (double) file_size;
                                        double progressInPercentage  = Double.valueOf( (bytesSoFar / fileSize) * 100.0d );
                                        progress = Double.parseDouble( String.format( "%.2f", progressInPercentage ) );
                                    }
                                    catch ( Exception e ) {
                                        e.printStackTrace();
                                    }
                                    if ( ota_type.equals( "regular" ) || ota_type.equals( "regular-prompt" ) ){
                                        Intent in = new Intent();
                                        in.putExtra( NotificationCompat.CATEGORY_PROGRESS, progress );
                                        in.putExtra("file_size", total );
                                        //DownloadOTAService.this.context.sendBroadcast(in);
                                        UtilMisc.sendExplicitExternalBroadcast( context, in, "ota_progress_update", Constants.DISPLAYPROJECT_PACKAGE_NAME, Constants.DISPLAYPROJECT_RECEIVER_NAME );
                                    }
                                    Log.d( TAG, "Downloading Update "+progress+"%");
                                    int p = (int)progress;
                                    if ( p == 100 ) {
                                        downloading = false;
                                    }
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException e2) {
                                        e2.printStackTrace();
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

    private void registerDownloadCompleteReceiver() {
        IntentFilter intentFilter = new IntentFilter("android.intent.action.DOWNLOAD_COMPLETE" );
        receiverDownloadComplete = new BroadcastReceiver() {

            public void onReceive( Context context, Intent intent ) {

                Log.d( TAG, "registerDownloadCompleteReceiver() onReceive" );
                long reference = intent.getLongExtra("extra_download_id", -1 );

                if ( downloadReference == reference ) {
                    Query query = new Query();
                    query.setFilterById( downloadReference );
                    Cursor cursor = downloadManager.query( query );
                    cursor.moveToFirst();
                    String downloadedFileURI = cursor.getString( cursor.getColumnIndex( DownloadManager.COLUMN_LOCAL_URI ) );
                    int status = cursor.getInt( cursor.getColumnIndex( NotificationCompat.CATEGORY_STATUS ) );
                    File savedFile = new File( Uri.parse( downloadedFileURI ).getPath() );
                    String savedFilePath = savedFile.getAbsolutePath();//cursor.getString( cursor.getColumnIndex( DownloadManager.COLUMN_LOCAL_FILENAME ) );
                    String fileName = savedFilePath.substring( savedFilePath.lastIndexOf("/" ) + 1 );

                    if( status == DownloadManager.STATUS_PAUSED ) {
                        Log.e( TAG, fileName + " download paused !");
                    }
                    else if( status == DownloadManager.STATUS_SUCCESSFUL ) {
                        Log.i( TAG, fileName + " downloaded successfully !");

                        downloading = false;
                        downloadReference = -1;
                        verifyDownload();
                    }
                    else if( status == DownloadManager.STATUS_PENDING ){
                        Log.e( TAG, fileName + " download pending !" );
                    }
                    else if( status == DownloadManager.STATUS_RUNNING ) {
                        Log.d(TAG, fileName + " downloading !");
                    }
                    else{
                        Log.e( TAG, fileName + " failed to download !" );
                        setRetryTimer();
                    }
                }
            }
        };
        registerReceiver( receiverDownloadComplete, intentFilter );
    }



    /* access modifiers changed from: private */
    public void setRetryTimer(){

        final long time = retryCounter.getRetryTime();
        Log.d( TAG, "time : "+time );

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                Log.d( TAG, "Downloading OTA update.zip after " + (time/1000) + " seconds !" );
                downloadOTA();
            }
        }, time);
    }

    /* access modifiers changed from: private */
    public void verifyDownload() {
        try {
            String downloaded_md5 = MD5.getMD5Checksum( ota_update_zip_file );
            Log.d( TAG, String.format( "Original MD5 %s, Downloaded MD5 %s", new Object[]{this.new_firmware_md5, downloaded_md5 } ));

            if ( !new_firmware_md5.equals( downloaded_md5 ) ) {
                CustomItems.showCustomToast( context, "error", "Download was not successful, Re-Downloading !", Toast.LENGTH_LONG );
                downloadOTA();
            }
            else if ( ota_type.equals( "regular" ) ) {
                Intent in = new Intent("ota_download_complete" );
                in.putExtra("show_prompt", false );
                // sendBroadcast(in);
                UtilMisc.sendExplicitExternalBroadcast( context, in, "ota_download_complete", Constants.DISPLAYPROJECT_PACKAGE_NAME, Constants.DISPLAYPROJECT_RECEIVER_NAME );
                copyToCacheAndUpgrade();
            }
            else if ( ota_type.equals("silent")) {
                copyToCacheAndUpgrade();
            }
            else if ( ota_type.equals("regular-prompt")) {
                Intent in2 = new Intent("ota_download_complete");
                in2.putExtra("show_prompt", true);
                // sendBroadcast(in2);
                UtilMisc.sendExplicitExternalBroadcast( context, in2, "ota_download_complete", Constants.DISPLAYPROJECT_PACKAGE_NAME, Constants.DISPLAYPROJECT_RECEIVER_NAME );
            }
            else if ( ota_type.equals("silent-prompt")) {
                Intent in3 = new Intent("ota_download_complete");
                in3.putExtra("show_prompt", true);
                // this.context.sendBroadcast(in3);
                UtilMisc.sendExplicitExternalBroadcast( context, in3, "ota_download_complete", Constants.DISPLAYPROJECT_PACKAGE_NAME, Constants.DISPLAYPROJECT_RECEIVER_NAME );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void copyToCacheAndUpgrade() {
        new Handler().postDelayed(new Runnable() {
            public void run() {
                createUpdateScript();
                copyFirmwareToCache();
                verifyCacheFirmwareCopy();
            }
        }, 2000);
    }

    public void createUpdateScript(){
        configurationReader = ConfigurationReader.getInstance();
        File temp_file = new File( Environment.getExternalStorageDirectory() + File.separator + "up.sh" );
        if ( temp_file.exists() ) {
            temp_file.delete();
        }
        try {
            FileOutputStream fos = new FileOutputStream( temp_file );
            Log.d( TAG, "temp file created at : " + temp_file.getAbsolutePath() );
            // fos.write( "echo 'boot-recovery ' > /cache/recovery/command\necho '--update_package=/cache/update.zip' >> /cache/recovery/command\nreboot recovery".getBytes());
            fos.write( FIRMWARE_UPDATE_SCRIPT.getBytes());
            UtilShell.executeShellCommandWithOp("cp /mnt/sdcard/up.sh /cache/up.sh");
            UtilShell.executeShellCommandWithOp("chmod -R 777 /cache", "chmod 777 /cache/up.sh");
            fos.close();
        }
        catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    /* access modifiers changed from: private */
    public void copyFirmwareToCache() {
        UtilShell.executeShellCommandWithOp("chmod -R 777 /cache", "rm /cache/update.zip");
        String s = UtilShell.executeShellCommandWithOp("cp /mnt/sdcard/appstv_data/firmware/update.zip /cache/update.zip", "chmod 777 /cache/update.zip");
        Log.d( TAG, "Firmware successfully copied to /cache : " + s );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterReceiver( receiverDownloadComplete );
    }

    /* access modifiers changed from: private */
    public void verifyCacheFirmwareCopy() {
        try {
            String downloaded_md5 = MD5.getMD5Checksum(new File("/cache/update.zip"));
            new_firmware_md5 = MD5.getMD5Checksum(new File("/mnt/sdcard/appstv_data/firmware/update.zip"));

            Log.d( TAG, String.format( "Original MD5 %s, Downloaded MD5 %s", new_firmware_md5, downloaded_md5 ));

            if ( !new_firmware_md5.equals( downloaded_md5 ) ) {
                CustomItems.showCustomToast( context, "error", "There was an error. Trying Again !", Toast.LENGTH_LONG );
                copyFirmwareToCache();
                return;
            }
            Log.d( TAG, "All Good ! Box is Rebooting Now :)" );
            UtilShell.executeShellCommandWithOp("sh /cache/up.sh" );

        }
        catch ( Exception e ) {
            e.printStackTrace();
        }
    }
}

package com.excel.datadownloader.secondgen;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.excel.configuration.ConfigurationReader;
import com.excel.customitems.CustomItems;
import com.excel.excelclasslibrary.UtilShell;
import com.excel.util.MD5;

import java.io.File;
import java.io.FileOutputStream;

public class OTADownloadingActivity extends Activity {

    TextView tv_progress, tv_total_size, tv_message, tv_download_complete;
    LinearLayout ll_progress;
    long progress, total;
    File ota_zip_file;
    long file_size;
    final static String TAG = "OTADownlaodingActivity";
    Handler handler = new Handler();
    Context context;
    BroadcastReceiver progressUpdateReceiver, downloadCompleteReceiver;
    ConfigurationReader configurationReader;
    String new_firmware_md5 = "";

    // Script
    public static final String FIRMWARE_UPDATE_SCRIPT			= 	"echo 'boot-recovery ' > /cache/recovery/command\n" +
            "echo '--update_package=/cache/update.zip' >> /cache/recovery/command\n"+
            "reboot recovery";

    boolean showing = true;

    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_otadownloading );

        context = this;

        init();


    }



    private void init(){
        initViews();

        registerBroadcasts();

        getData();

        calculateSizes();

        // startProgressTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d( TAG, "onPause()" );

        if( showing ) {
           /* Intent in = getIntent();
            startActivity( in );*/
            //startService( new Intent( this, DownloadOTAService.class ) );
            /*new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.d( TAG, "Running after 2 seconds" );
                    sendBroadcast( new Intent( "get_ota_info" ) );
                }
            }, 2000 );*/

        }




    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d( TAG, "onResume()" );

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d( TAG, "onDestroy()" );

    }

    private void initViews(){
        tv_message = (TextView) findViewById( R.id.tv_message );
        tv_progress = (TextView) findViewById( R.id.tv_progress );
        tv_total_size = (TextView) findViewById( R.id.tv_total_size );
        ll_progress = (LinearLayout) findViewById( R.id.ll_progress );
        tv_download_complete = (TextView) findViewById( R.id.tv_download_complete );
    }


    private void getData(){
        Intent in = getIntent();
        ota_zip_file = new File( in.getStringExtra( "ota_zip_file_path" ) );
        file_size = in.getLongExtra( "file_size", 0 );

        Log.d( TAG, "File Path : " + ota_zip_file.getAbsolutePath());
        Log.d( TAG, "File Size : " + file_size + " bytes" );

    }

    private void registerBroadcasts(){
        progressUpdateReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent ) {
                //Log.i( TAG, "Progress Updated !" );

                progress = (intent.getIntExtra( "progress", 0 )/1024)/1024;   // size in MB
                new_firmware_md5 = intent.getStringExtra( "md5" );
                Log.d( TAG, "Progress : " + progress + " MB" );

                tv_progress.setText( "" + progress + " MB" );

            }
        };
        LocalBroadcastManager.getInstance( context ).registerReceiver( progressUpdateReceiver, new IntentFilter( "ota_progress_update" ) );

        downloadCompleteReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent ) {
                Log.i( TAG, "Downloaded !" );

                tv_message.setVisibility( View.GONE );

                ll_progress.setVisibility( View.GONE );

                tv_download_complete.setVisibility( View.VISIBLE );

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        createUpdateScript();

                        copyFirmwareToCache();

                        verifyCacheFirmwareCopy();
                    }
                }, 2000 );



            }
        };
        LocalBroadcastManager.getInstance( context ).registerReceiver( downloadCompleteReceiver, new IntentFilter( "ota_download_complete" ) );
    }

    private void calculateSizes(){
        total    = (file_size/1024)/1024;
        progress = (ota_zip_file.length()/1024)/1024;   // size in MB
        tv_total_size.setText( "" + total + " MB" );
        Log.d( TAG, "Total Size : " + total + " MB" );
        Log.d( TAG, "Progress : " + progress + " MB" );
    }

    private void startProgressTimer(){
        new AsyncTask< Void, Void, Void >(){

            @Override
            protected Void doInBackground(Void... params) {
                new Thread(new Runnable() {
                    public void run() {
                        while( true ){

                            if( progress == total ){
                                Log.d( TAG, "Download Completed !" );
                                break;
                            }

                            handler.post(new Runnable() {
                                public void run() {
                                    progress = (ota_zip_file.length()/1024)/1024;   // size in MB
                                    Log.d( TAG, "Progress : " + progress + " MB" );

                                    tv_progress.setText( "" + progress + " MB" );

                                }
                            });



                            try {
                                Thread.sleep( 1000 );

                            } catch ( InterruptedException e ) {
                                e.printStackTrace();
                            }

                        }
                    }
                }).start();
                return null;
            }

        }.execute();
    }

    private void createUpdateScript(){
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
            Log.d( TAG, String.format( "Original MD5 %s, Downloaded MD5 %s", new_firmware_md5, downloaded_md5 ) );

            if( ! new_firmware_md5.equals( downloaded_md5 ) ){
                CustomItems.showCustomToast( context, "error", "There was an error. Trying Again !", Toast.LENGTH_LONG );
                copyFirmwareToCache();
            }
            else{
                Log.d( TAG, "All Good ! Box is Rebooting Now :)" );
                UtilShell.executeShellCommandWithOp( "sh /cache/up.sh" );
            }
        } catch ( Exception e ) {
            e.printStackTrace();
        }

    }

    @Override
    public boolean onKeyDown( int keyCode, KeyEvent event ) {
        return true;
    }
}

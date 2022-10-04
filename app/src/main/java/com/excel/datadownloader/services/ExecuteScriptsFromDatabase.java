package com.excel.datadownloader.services;

import static com.excel.excelclasslibrary.Constants.APPSTVLAUNCHER_PACKAGE_NAME;
import static com.excel.excelclasslibrary.Constants.APPSTVLAUNCHER_RECEIVER_NAME;

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
import com.excel.excelclasslibrary.UtilFile;
import com.excel.excelclasslibrary.UtilMisc;
import com.excel.excelclasslibrary.UtilNetwork;
import com.excel.excelclasslibrary.UtilShell;
import com.excel.excelclasslibrary.UtilURL;
import com.excel.util.MD5;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Created by Sohail on 04-11-2016.
 */

public class ExecuteScriptsFromDatabase extends Service {

    final static String TAG = "ExecuteScriptsFromDB";
    Context context = this;
    RetryCounter retryCounter;
    DownloadManager downloadManager;
    private BroadcastReceiver receiverDownloadComplete = null;
    long downloadReference;
    int counter = 0;

    @Override
    public IBinder onBind( Intent intent ) {
        return null;
    }

    @Override
    public int onStartCommand( Intent intent, int flags, int startId ) {
        Log.d( TAG, "ExecuteScriptsFromDatabase started" );
        context = this;
        retryCounter = new RetryCounter( "scripts_download_count" );

        downloadScriptsForExecution();

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    private void downloadScriptsForExecution(){
        new Thread(new Runnable(){

            @Override
            public void run(){
                String s = UtilNetwork.makeRequestForData( UtilURL.getWebserviceURL(), "POST",
                        UtilURL.getURLParamsFromPairs( new String[][]{
                                { "what_do_you_want", "get_scripts_from_db" },
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

                return;
            }
            else if( type.equals( "success" ) ){
                jsonObject = new JSONObject( info );

                executeScriptsFromDB( jsonObject.getString( "scripts" ) );

            }

            retryCounter.reset();
        }
        catch( Exception e ){
            e.printStackTrace();
            setRetryTimer();
        }
    }

    private void executeScriptsFromDB( String scripts ){

        Log.d( TAG, "scripts: " + scripts );

        scripts = scripts.trim();
        if( scripts.equals( "" ) ){
            Log.i( TAG, "Scripts have not been set for execution for this room !" );
            return;
        }

        String[] scripts_m = scripts.split( "[|]" );
        String op = "";

        for( int i = 0 ; i < scripts_m.length ; i++ ){
            Log.i( TAG, "script" + i + ": " + scripts_m[ 0 ] );
            op += UtilShell.executeShellCommandWithOp( scripts_m[ 0 ] ) + "\n";
            Log.i( TAG, "Output for script" + i + ": " + op );
        }

        uploadScriptExecutionResult( scripts, op );

    }

    private void uploadScriptExecutionResult( String scripts, String result ){

        new Thread(new Runnable(){

            @Override
            public void run(){
                String s = UtilNetwork.makeRequestForData( UtilURL.getWebserviceURL(), "POST",
                        UtilURL.getURLParamsFromPairs( new String[][]{
                                { "what_do_you_want", "upload_script_execution_result" },
                                { "scripts", scripts },
                                { "result", result },
                                { "mac_address", UtilNetwork.getMacAddress( context ) }
                        } ));

                if( s == null ){
                    Log.d( TAG, "Failed to upload the result of script execution!" );
                    return;
                }
                Log.d( TAG, "response : "+s );
            }

        }).start();
    }



    @Override
    public void onDestroy() {
        super.onDestroy();

        if( receiverDownloadComplete != null ) {
            unregisterReceiver( receiverDownloadComplete );
            receiverDownloadComplete = null;
        }
    }

    private void setRetryTimer(){
        final long time = retryCounter.getRetryTime();

        Log.d( TAG, "time : " + time );

        new Handler( Looper.getMainLooper() ).postDelayed(new Runnable() {

            @Override
            public void run() {
                Log.d( TAG, "downloading scripts from db after "+(time/1000)+" seconds !" );
                downloadScriptsForExecution();

            }

        }, time );

    }

}

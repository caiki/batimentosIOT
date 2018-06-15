/**
 * Lifegrep scratch writer
 * Heartbeats and daily events are written in real time to a json scratch file in internal storage.
 * After sometime, these events/heartbeats are uploaded to a server and the scratch cleaned.
 */
package herv.app.services;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Timer;

import herv.app.activities.HeartbeatFragment;


public class ScratchFileWriter {

    Context context;
    String filename, dirname;
    final BlockingQueue<Integer> queue = new LinkedBlockingQueue<Integer>();

    public ScratchFileWriter(Context context, String filename) {
        this.context = context;
        //this.filename = context.getFilesDir().getPath() + "/" + filename;
        this.dirname = "HeRV";
        this.filename = Environment.getExternalStorageDirectory().getPath() + "/" + dirname + "/" + filename;
        checkFolder();
    }

    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    public void checkFolder() {
        String folder_main = "HeRV";
        File f = new File(Environment.getExternalStorageDirectory(), folder_main);
        if(!f.exists())
        {
            f.mkdir();
        }
    }

/*
    public int genRandom(final int minimo, final int maximo){
        TimerTask task = new TimerTask() {
            public void run() {
                try {
                    queue.put((int)Math.floor(Math.random()*(maximo-minimo+1)+(minimo)));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                // or use whatever method you chose to generate the number...
            }
        };
        Timer timer = new Timer();
        timer.schedule(task, 0, 1000);
        return queue.remove();
    }
*/
    public void saveData(String data) {
        java.io.FileWriter fw = null;
        BufferedWriter bw = null;
        try {
            //TODO URGENT open file only once and close at the end
            fw = new java.io.FileWriter(filename, true);
            bw = new BufferedWriter(fw);
            bw.newLine();
            bw.write(data);
            String[] parts = data.split(",");
            String datatime = parts[0]; // 123
            String beat = parts[1]; // 654321
            //Integer beat = genRandom(45,70);
            if(!beat.toString().equals("stop"))
            {
                new CargarDatos().execute("http://uspio.pythonanywhere.com/AgregarHeart_ajax/?FechaTiempo="+datatime+"&Beat="+beat.toString());
            }
            else{
                System.out.println("Aqui paro");
            }
            //System.out.println(datatime +"-/-"+ beat);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (bw != null)
                    bw.close();
                if (fw != null)
                    fw.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    public class CargarDatos extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... urls) {

            // params comes from the execute() call: params[0] is the url.
            try {
                return downloadUrl(urls[0]);
            } catch (IOException e) {
                return "Unable to retrieve web page. URL may be invalid.";
            }
        }
        // onPostExecute displays the results of the AsyncTask.
        @Override
        protected void onPostExecute(String result) {

            //Toast.makeText(getActivityContext(), "Atualizando dados", Toast.LENGTH_LONG).show();

        }
    }

    private String downloadUrl(String myurl) throws IOException {
        Log.i("URL",""+myurl);
        myurl = myurl.replace(" ","%20");
        InputStream is = null;
        // Only display the first 500 characters of the retrieved
        // web page content.
        int len = 500;

        try {
            URL url = new URL(myurl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(10000 /* milliseconds */);
            conn.setConnectTimeout(15000 /* milliseconds */);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            // Starts the query
            conn.connect();
            int response = conn.getResponseCode();
            Log.d("respuesta", "The response is: " + response);
            is = conn.getInputStream();

            // Convert the InputStream into a string
            String contentAsString = readIt(is, len);
            return contentAsString;

            // Makes sure that the InputStream is closed after the app is
            // finished using it.
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    public String readIt(InputStream stream, int len) throws IOException, UnsupportedEncodingException {
        Reader reader = null;
        reader = new InputStreamReader(stream, "UTF-8");
        char[] buffer = new char[len];
        reader.read(buffer);
        return new String(buffer);
    }

    public String getData() {
        try {
            File f = new File(filename);
            FileInputStream inStream = new FileInputStream(f);
            int size = inStream.available();
            byte[] buffer = new byte[size];
            inStream.read(buffer);
            inStream.close();
            return new String(buffer);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    public void eraseContents() {
        java.io.FileWriter fw = null;
        try {
            fw = new java.io.FileWriter(filename);
            fw.write("");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (fw != null)
                    fw.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }



}

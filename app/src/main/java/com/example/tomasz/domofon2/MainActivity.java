package com.example.tomasz.domofon2;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.AsyncTask;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;

public class MainActivity extends AppCompatActivity {

    /**
     * Przyciski GUI
     */
    private Button startButton,stopButton,connectButton,openButton;
    /**
     * Pole tekstowe
     */
    private EditText editIp;
    /**
     * TextView wyświetlający komunikaty dla użytkownika.
     */
    private TextView textDebug,textView;
    /**
     * IP serwera w formie stringa
     */
    private volatile String IPSERVER;
    /**
     * Zmienna wskazująca stan prowadzonej rozmowy.
     */
    private volatile boolean call=false;
    /**
     * Zmienna wskazująca stan ustanowienia połączenia.
     */
    private volatile boolean connectionIsSet = false;
    /**
     * Zmienna sterująca głównymi pętlami programu
     */
    private volatile boolean status = true;
    /**
     * Numer portu do którego wysyłany jest dźwięk.
     */
    private int outPort=50005;
    /**
     * Częstotliwość próbkowania.
     */
    private int sampleRate = 16000;
    /**
     * Numer portu służącego do odbierania i nadawania komend.
     */
    private int commandPort = 50004;
    /**
     * Numer portu przyjmującego dane z serwera.
     */
    private int incomingPort =50006;
    /**
     * Konfiguracja kanałów. Domyślnie MONO.
     */
    private volatile int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    /**
     * Kodowanie dźwięku. Domyślnie 16 bitów.
     */
    private volatile int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    /**
     * Minimalny rozmiar bufora nagrywania i odtwarzania dźwięku. Dla 16kHz wynosi on 1280.
     */
    private volatile int minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
    /**
     * Tablica odebranego bufora danych.
     */
    private volatile byte[] receiveData = new byte[minBufSize];
    /**
     * Bufor danych linii wejściowej(mikrofon).
     */
    private volatile byte[] buffer;
    /**
     * Socket odbierający komendy.
     */
    private volatile ServerSocket commandServer;
    /**
     * Wyjściowy strumień danych.
     */
    private volatile PrintWriter out;
    /**
     * Wątek obsługujący wysyłanie dźwięku.
     */
    private Thread streamOutThread;
    /**
     * Wątek obsługujący odbiór dźwięku.
     */
    private Thread streamInThread;
    /**
     * Wątek obsługujący odbiór komend.
     */
    private Thread commandThread;
    /**
     * Linia wejścia audio.
     */
    private volatile AudioRecord recorder;
    /**
     * Dzwonek powiadamiający o przychodzącym połączeniu.
     */
    private volatile Ringtone ringTone;
    /**
     * Asynchroniczny wątek wysyłający komendy.
     */
    private volatile commandSender commandTCP;
    /**
     * Asynchroniczny wątek odbierający komendy.
     */
    private volatile commandRecive commandReciver;
    /**
     * Socket odbierający pakiety dźwiękowe z serwera
     */
    private volatile DatagramSocket serverSocketIn;
    /**
     * Socket wysyłający pakiety dźwiękowe do serwera.
     */
    private volatile DatagramSocket serverSocketOut;
    /**
     * Linia wyjścia audio.
     */
    private volatile AudioTrack speaker;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        /**
         * Utworzenie GUI
         */
        setContentView(R.layout.activity_main);
        openButton = (Button) findViewById(R.id.open_button);
        startButton = (Button) findViewById (R.id.start_button);
        stopButton = (Button) findViewById (R.id.stop_button);
        connectButton = (Button) findViewById (R.id.connect_button);
        editIp = (EditText) findViewById(R.id.editText);
        textDebug = (TextView) findViewById(R.id.textDebug);
        textView = (TextView) findViewById(R.id.textView);
        startButton.setOnClickListener (startListener);
        stopButton.setOnClickListener (stopListener);
        connectButton.setOnClickListener(connectListener);
        openButton.setOnClickListener(openListener);
        /**
         * Dodatkowe inicjalizacje.
         */
        commandTCP = new commandSender();
        ringTone = RingtoneManager.getRingtone(getApplicationContext(),RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        /**
         * Inicjalizacja wątku odpowiadającego za odbiór komend
         */
        commandThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        if (commandReciver != null)
                        {
                            //Zawsze czeka na wykonanie zadania.
                            if (commandReciver.getStatus() == AsyncTask.Status.FINISHED) {
                                commandReciver = new commandRecive();
                                commandReciver.execute();
                            }
                        }
                        else
                        {
                            commandReciver = new commandRecive();
                            commandReciver.execute();
                        }
                        try {
                            /**
                             * Wysyłanie i odbieranie danych nie jest możliwe równolegle.
                             * Należy utworzyć lukę w czasie nasłuchiwania w czasie której zostaną wysłane komendy.
                             */
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    catch (Exception e)
                    {
                        Log.d("CR","Command receive fail");
                    }
                }
            }
        });
        commandThread.start();
        streamInThread = new Thread(new Runnable() {
            @Override
            public void run() {
                voiceReceive();
            }
        });
        streamOutThread = new Thread(new Runnable() {
            @Override
            public void run() {
                voiceSend();
            }
        });
    }

    /**
     * Metoda wysyłająca pakiety dźwięku uzyskane z linii wejścia.
     * Strumień danych z mikrofonu jest buforowany i wysyłany w pakietach do serwera.
     */
    public void voiceSend()
    {
        try {
            serverSocketOut = new DatagramSocket();
            serverSocketOut.setSoTimeout(1000);
            Log.d("VS", "Output Thread: Socket Created");
            byte[] buffer = new byte[minBufSize];
            Log.d("VS", "Output Thread: Buffer created of size " + minBufSize);
            DatagramPacket packet;
            final InetAddress destination = InetAddress.getByName(IPSERVER);
            Log.d("VS", "Output Thread: Address retrieved");
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBufSize * 10);
            Log.d("VS", "Output Thread: Recorder initialized");
            recorder.startRecording();
            while (status == true) {
                //reading data from MIC into buffer
                minBufSize = recorder.read(buffer, 0, buffer.length);
                packet = new DatagramPacket(buffer, buffer.length, destination, outPort);
                serverSocketOut.send(packet);
            }
            recorder.release();
            serverSocketOut.close();
        } catch (UnknownHostException e) {
            Log.e("VS", "Zły adres");
        } catch (IOException e) {
            Log.e("VS", "IOException");
        } catch (Exception e) {
            editIp.setText("Zły Adres IP");
            Log.e("VS", e.getMessage());
        }
    }

    /**
     * Metoda odbierająca pakiety dźwięku z serwera.
     * Pakiety zostają zamienione na pojedyńcze dźwięki odtwarzane przez linie wyjścia.
     */
    public void voiceReceive()
    {
        try {
            serverSocketIn = new DatagramSocket(incomingPort);
            serverSocketIn.setSoTimeout(1000);
            Log.d("VS", "Input Thread: Socket Created");
            DatagramPacket receivePacket = new DatagramPacket(receiveData,receiveData.length);
            int minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            Log.d("VS", "Input Thread: Packet Size: "+minBufSize);
            speaker = new AudioTrack(AudioManager.STREAM_VOICE_CALL,sampleRate,channelConfig,audioFormat,minBufSize,AudioTrack.MODE_STREAM);
            speaker.play();
            Log.d("VS", "Input Thread: Record Start");
            while (status == true)
            {
                serverSocketIn.receive(receivePacket);
                buffer= receivePacket.getData();
                speaker.write(buffer, 0, minBufSize);
            }
            serverSocketIn.close();
            speaker.release();

        }
        catch (Exception e)
        {
            Log.e("VS", "Input Thread FATAL ERROR");
        }
    }

    /**
     * Asynchroniczny wątek obsługujący odbiór komend.
     * Komendy odbierane przez client:
     * "HELLO ANDROID" - potwierdzenie nawiązania połączenia.
     * "CALL INCOMING" - informacja o nadchodzącym połączeniu domofonowym
     * "HEADPHONE UP" - informacja o podniesieniu słuchawki
     * "HEADPHONE DOWN" - informacja o opuszczeniu słuchawki
     * "CALL END" - informacja o zakończeniu połączenia
     *
     */
    public class commandRecive extends AsyncTask<String,Void,String>{
        String message = null;
        /**
         * Metoda wykonująca zadanie.
         * @param params ip serwera w postaci stringa oraz komenda
         * @return treść komendy
         */
        @Override
        protected String doInBackground(String... params) {
            try
            {
                commandServer = new ServerSocket(commandPort);
                commandServer.setSoTimeout(900);
                Socket client = commandServer.accept();
                Log.d("VS", "Command Thread: Socket Created");
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                message = in.readLine();
                commandServer.close();

            }
            catch(Exception e){
                message = "";
                try {
                    commandServer.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                Log.d("VS","Bląd odbioru");
                e.printStackTrace();
            }
            Log.d("VS", "IP: " + IPSERVER);
            return message;
        }

        /**
         * Po zakończeniu nasłuchiwania w zależności od treści otrzymanej wiadomości następuje jej analiza.
         * @param s
         */
        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            if (message.equals("HELLO ANDROID"))
            {
                textDebug.setText("Połączono!");
                connectionIsSet = true;
            }
            if(message.equals("CALL INCOMING"))
            {
                startButton.setVisibility(View.VISIBLE);
                if(!ringTone.isPlaying() && call==false)
                {
                    ringTone.play();
                }
                textDebug.setText("Przyszło połączenie!");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(20000);
                            if(call==false)
                            {
                                ringTone.stop();
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }).start();
            }
            if(message.equals("HEADPHONE UP"))
            {
                textDebug.setText("Podniesiono słuchawkę.");
            }
            if(message.equals("HEADPHONE DOWN"))
            {
                textDebug.setText("Opuszczono słuchawkę.");
            }
            if(message.equals("CALL END"))
            {
                callEnd();
            }
            message=null;
        }
    }

    /**
     * Asynchroniczny wątek wysyłający komendy do serwera.
     * Parametry potrzebne do wysłania komendy to:
     * IP SERWERA
     * TRESC KOMENDY
     * Komendy przyjmowane przez serwer:
     * "HELLO SERVER" - komenda oznaczająca prośbę o nawiązanie połączenia i zapisanie adresu IP clienta.
     * "OPEN DOOR" - komenda wywołująca otwarcie drzwi przez domofon
     * "CALL END" - komenda oznaczająca rozłączenie połączenia przez clienta.
     * "CALL START" - komenda oznaczająca rozpoczęcie połączenia przez clienta.
     */
    public class commandSender extends AsyncTask<String,Void,String>{

        /**
         * Metoda wykonująca zadanie wątku.
         * @param params ip serwera w postaci stringa oraz komenda
         * @return treść komendy
         */
        @Override
        protected String doInBackground(String... params) {
            String message = null;
            try{
                Socket command = new Socket(params[0],commandPort);
                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(command.getOutputStream())));
                out.println(params[1]);
                out.flush();
                command.close();
            }
            catch(Exception e){
                message = "Problem z połączeniem";
                Log.e("VS","Connection fail");
            }
            Log.d("VS", "IP: " + IPSERVER);
            return message;
        }

        /**
         * Potwierdzenie zakończenia procesu wysyłania.
         * @param s
         */
        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            textDebug.setText("Tryb gotowości");
        }
    }

    /**
     * Metoda wywołująca commandSender w celu wysłania wiadomości
     * @link commandSender
     * @param msg
     */
    public void commandSend(String msg)
    {
        if(commandTCP.getStatus()!= AsyncTask.Status.RUNNING) {
            commandTCP = new commandSender();
            commandTCP.execute(IPSERVER, msg);
        }
        else
        {
            textDebug.setText("Poczekaj.");
        }
    }

    /**
     * Metoda przesyłająca IP klienta do serwera.
     */
    public void connect()
    {
        startButton.setVisibility(View.VISIBLE);
        connectButton.setVisibility(View.GONE);
        editIp.setVisibility(View.GONE);
        textView.setText("Oczekuj na połączenie");

        status=true;
        IPSERVER = editIp.getText().toString();
        if(commandTCP.getStatus() == AsyncTask.Status.RUNNING)
        {
            textDebug.setText("Poczekaj.");
        }
        else
        {
            commandSend("HELLO SERVER");
            textDebug.setText("Komunikowanie się z serwerem");

        }
        connectionIsSet = true;
    }

    /**
     * Metoda zamykająca stream audio oraz wysyłająca informację o tym do serwera.
     */
    public void callEnd() {
        stopButton.setVisibility(View.GONE);
        textView.setText("Oczekuj na połączenie");
        call = false;
        startButton.setVisibility(View.VISIBLE);
        openButton.setVisibility(View.INVISIBLE);

        if(streamInThread.isAlive() || streamOutThread.isAlive())
        {
            commandSend("CALL END");
            status=false;
            try {
                streamOutThread.interrupt();
                streamInThread.interrupt();
            } catch (Exception e) {
                textDebug.setText("Błąd połączenia");
            }
            try {
                streamInThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        voiceReceive();
                    }
                });
                //Wysylanie dzwieku
                streamOutThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        voiceSend();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
            Log.d("VS", "Recorder released");
        }
    }

    /**
     * Metoda wysyłająca komendę otwarcia drzwi do serwera.
     */
    public void openDoor()
    {
        if(connectionIsSet) {
            commandSend("OPEN DOOR");
            textDebug.setText("Otwarto Drzwi");
        }
    }

    /**
     * Metoda rozpoczynająca stream audio.
     */
    public void callStart() {
        textView.setText("Możesz rozmawiać!");
        stopButton.setVisibility(View.VISIBLE);

        if(ringTone!=null)
        {
            ringTone.stop();
        }
        startButton.setVisibility(View.INVISIBLE);
        openButton.setVisibility(View.VISIBLE);
        IPSERVER = editIp.getText().toString();
        status = true;
        call = true;
        commandSend("CALL START");
        if(!streamInThread.isAlive() || !streamOutThread.isAlive())
        {
            if(!streamInThread.isAlive())
            {
                streamInThread.start();
            }

            streamOutThread.start();
        }
    }

    /**
     * Przycisk połączenia TCP/IP.
     */
    private final View.OnClickListener connectListener = new View.OnClickListener() {

        @Override
        public void onClick(View arg0) {
            connect();
        }

    };
    /**
     * Przycisk zatrzymania połączenia audio.
     */
    private final View.OnClickListener stopListener = new View.OnClickListener() {
        @Override
        public void onClick(View arg0) {
            callEnd();
        }

    };
    /**
     * Przycisk rozpoczęcia połączenia audio.
     */
    private final View.OnClickListener startListener = new View.OnClickListener() {
        @Override
        public void onClick(View arg0) {
            callStart();
        }
    };
    /**
     * Przycisk otwarcia drzwi.
     */
    private final View.OnClickListener openListener = new View.OnClickListener() {
        @Override
        public void onClick(View arg0) {
            openDoor();
        }
    };
}

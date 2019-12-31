package com.tech.playinsdk.webrtc;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class GenymotionActivity extends AppCompatActivity implements View.OnTouchListener {

    PeerConnectionFactory peerConnectionFactory;
    PeerConnection peerConnection;
    SurfaceViewRenderer remoteView;

    private ArrayBlockingQueue<String> sendQueue = new ArrayBlockingQueue<>(200);
    private Thread mWriteThread;
    public class WriteThread extends Thread {
        @Override
        public void run() {
            try {
                while (!isInterrupted()) {
                    String sendStr = sendQueue.take();
                    client.send(sendStr);
                }
            } catch (Exception e) {
                Log.e("TAG", "WriteThread send error： " + e);
            }
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getAction();
        int mode = -1;
        if (action == MotionEvent.ACTION_DOWN) {
            mode = 0;
        } else if (action == MotionEvent.ACTION_MOVE) {
            mode = 2;
        } else if (action == MotionEvent.ACTION_UP) {
            mode = 1;
        }
        try {
            JSONObject obj = new JSONObject()
                    .putOpt("type", "MULTI_TOUCH")
                    .putOpt("mode", mode)
                    .putOpt("nb", event.getPointerCount());
            JSONArray points = new JSONArray();
            for (int i = 0; i < event.getPointerCount(); i++) {
                points.put(new JSONObject()
                        .putOpt("x", getXcoordinate(event.getX(i)))
                        .putOpt("y", getYcoordinate(event.getY(i))));
            }
            obj.putOpt("points", points);
            sendQueue.offer(obj.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return true;
    }

    private int destWidth = 360;
    private int destHeight = 640;
    private float getXcoordinate(float srcX) {
        return srcX * destWidth / remoteView.getWidth();
    }
    private float getYcoordinate(float srcY) {
        return srcY * destHeight / remoteView.getHeight();

    }

    private class MyWebSocketClient extends WebSocketClient {

        public MyWebSocketClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            Log.e("TAG", "onOpen");
        }

        @Override
        public void onMessage(String message) {
            Log.e("TAG", "onMessage " + message);
            try {
                JSONObject obj = new JSONObject(message);
                if (obj.optString("code").equals("SUCCESS")) {
                    sendToken();
                } else if (obj.optString("connection").equals("SUCCESS")) {
                    sendOffer();
                } else if (obj.optString("type").equals("answer")) {
                    receiveAnswer(message);
                } else if (obj.has("candidate")){
                    onIceCandidateReceived(message);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            Log.e("TAG", "onClose " + code + " " + reason + " " + remote);
        }

        @Override
        public void onError(Exception ex) {
            Log.e("TAG", "onError " + ex.toString());
        }
    }

    private void initWebSocketClient(WebSocketClient webSocketClient) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }
                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            }, new SecureRandom());
            SSLSocketFactory factory = sslContext.getSocketFactory();
            webSocketClient.setSocketFactory(factory);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private MyWebSocketClient client;

    private void sendToken() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("type", "token");
            obj.put("token", "i-0176bcb6ccb14bece");
            client.send(obj.toString());
            Log.e("TAG", "发送token成功");
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_genymotion);
        init();

        client = new MyWebSocketClient(URI.create("wss://52.68.173.154"));
        client.setTcpNoDelay(true);
        initWebSocketClient(client);
        client.connect();

        mWriteThread = new WriteThread();
        mWriteThread.start();
    }

    @Override
    protected void onDestroy() {
        if (null != mWriteThread) {
            mWriteThread.interrupt();
        }
        super.onDestroy();
    }

    MediaStream mediaStream;
    EglBase.Context eglBaseContext;
    private void init() {
        eglBaseContext = EglBase.create().getEglBaseContext();

        remoteView = findViewById(R.id.remoteView);
        remoteView.setMirror(false);
        remoteView.setOnTouchListener(this);
        remoteView.setEnableHardwareScaler(true);
        remoteView.init(eglBaseContext, new RendererCommon.RendererEvents() {
            @Override
            public void onFirstFrameRendered() {
                Log.e("TAG", "onFirstFrameRendered");
            }

            @Override
            public void onFrameResolutionChanged(int i, int i1, int i2) {
                Log.e("TAG", "onFrameResolutionChanged " + i + " -- " + i1 + " -- " + i2);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT, RendererCommon.ScalingType.SCALE_ASPECT_FIT);

                    }
                });

            }
        });


        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(this)
                .createInitializationOptions());
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();

        DefaultVideoEncoderFactory defaultVideoEncoderFactory = new DefaultVideoEncoderFactory(eglBaseContext, true, true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory = new DefaultVideoDecoderFactory(eglBaseContext);

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();

        VideoSource videoSource = peerConnectionFactory.createVideoSource(true);
        VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
        mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream");
        mediaStream.addTrack(videoTrack);

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.genymotion.com:3478").createIceServer());
        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("localconnection") {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                sendIceCandidate(iceCandidate);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                super.onAddStream(mediaStream);
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
//                AudioTrack remoteAudioTrack = mediaStream.audioTracks.get(0);
                Log.e("TAG", "mediaStream ------------> " + mediaStream.audioTracks);
                runOnUiThread(() -> {
                    remoteVideoTrack.addSink(remoteView);
                });
            }
        });
        peerConnection.setAudioPlayout(true);
        peerConnection.addStream(mediaStream);
    }

    private void sendOffer() {
        peerConnection.createOffer(new SdpAdapter("local offer sdp") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new SdpAdapter("local set local"), sessionDescription);
                try {
                    JSONObject descObj = new JSONObject();
                    descObj.put("type", sessionDescription.type.canonicalForm());
                    descObj.put("sdp", sessionDescription.description);
                    Log.e("TAG", "发送offer " + descObj.toString());
                    client.send(descObj.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, new MediaConstraints());
    }

    private void receiveAnswer(String message) {
        Log.e("TAG", "receiveAnswer  --------->  " + message);
        try {
            JSONObject obj = new JSONObject(message);
            peerConnection.setRemoteDescription(new SdpAdapter("localSetRemote"),
                    new SessionDescription(SessionDescription.Type.ANSWER, obj.optString("sdp")));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendIceCandidate(IceCandidate iceCandidate) {
        try {
            JSONObject jo = new JSONObject();
            jo.put("sdpMid", iceCandidate.sdpMid);
            jo.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            jo.put("candidate", iceCandidate.sdp);
            Log.e("TAG", "sendIceCandidate ==============>  " + jo.toString());
            client.send(jo.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private  void onIceCandidateReceived(String message) {
        try {
            JSONObject data = new JSONObject(message);
            Log.e("TAG", "onIceCandidateReceived ---> " + data.toString());
            peerConnection.addIceCandidate(new IceCandidate(
                    data.optString("sdpMid"),
                    data.optInt("sdpMLineIndex"),
                    data.optString("candidate")
            ));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}

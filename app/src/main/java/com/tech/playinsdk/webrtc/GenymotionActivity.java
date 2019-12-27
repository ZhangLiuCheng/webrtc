package com.tech.playinsdk.webrtc;

import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.Camera1Enumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
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

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class GenymotionActivity extends AppCompatActivity {

    PeerConnectionFactory peerConnectionFactory;
    PeerConnection peerConnection;
    SurfaceViewRenderer remoteView;

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

        client = new MyWebSocketClient(URI.create("wss://13.231.195.158"));
        initWebSocketClient(client);
        client.connect();
    }

    MediaStream mediaStream;

    private void init() {
        EglBase.Context eglBaseContext = EglBase.create().getEglBaseContext();

        remoteView = findViewById(R.id.remoteView);
        remoteView.setMirror(false);
        remoteView.init(eglBaseContext, null);

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(this)
                .createInitializationOptions());
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory =
                new DefaultVideoEncoderFactory(eglBaseContext, true, true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory =
                new DefaultVideoDecoderFactory(eglBaseContext);
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
                runOnUiThread(() -> {
                    remoteVideoTrack.addSink(remoteView);
                });
            }
        });
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

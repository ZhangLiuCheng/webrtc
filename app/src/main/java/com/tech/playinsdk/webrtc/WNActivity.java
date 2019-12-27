package com.tech.playinsdk.webrtc;

import android.Manifest;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.tbruyelle.rxpermissions2.RxPermissions;

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

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;

public class WNActivity extends AppCompatActivity {

    final RxPermissions rxPermissions = new RxPermissions(this);

    PeerConnectionFactory peerConnectionFactory;
    PeerConnection peerConnection;
    SurfaceViewRenderer localView;
    SurfaceViewRenderer remoteView;
    MediaStream mediaStream;

    private String registerName;
    private String callName;

    private Socket mSocket;

    private void initSocket() {
        try {
            mSocket = IO.socket("https://playin.live/");
            mSocket.on(Socket.EVENT_CONNECT, args -> {
                Log.e("TAG", "EVENT_CONNECT ");
            }).on("call", args -> {
                Log.e("TAG", "call " + args[0]);
            }).on("answer", args -> {
                receiveAnswer((JSONObject) args[0]);
            }).on("offer", args -> {
                receiveOffer((JSONObject)args[0]);
            }).on("candidate", args -> {
               JSONObject obj = (JSONObject) args[0];
               if (obj.optJSONObject("candidate") == null) {
                   return;
               }
                onIceCandidateReceived(obj.optJSONObject("candidate"));
            }).on(Socket.EVENT_DISCONNECT, args -> {
                Log.e("TAG", "onMessageAnswer ===========> EVENT_DISCONNECT ");
            }).on(Socket.EVENT_ERROR, args -> {
                Log.e("TAG", "onMessageAnswer ===========> EVENT_ERROR " + args[0].toString());
            });
            mSocket.connect();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mSocket.off();
        mSocket.disconnect();
    }

    public void webrtcRegister(View view) {
        if (mSocket.connected()) {
            EditText rnEt = findViewById(R.id.registerName);
            registerName = rnEt.getText().toString();
            if (TextUtils.isEmpty(registerName)) {
                Toast.makeText(this, "请输入registerName", Toast.LENGTH_SHORT).show();
                return;
            }
            mSocket.emit("register",rnEt.getText().toString());
        }
    }

    public void webrtcCall(View view) {
        if (mSocket.connected()) {
            EditText rnEt = findViewById(R.id.callName);
            callName = rnEt.getText().toString();
            if (TextUtils.isEmpty(callName)) {
                Toast.makeText(this, "请输入callName", Toast.LENGTH_SHORT).show();
                return;
            }
            createOff();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wn);
        initSocket();
        rxPermissions
                .request(Manifest.permission.CAMERA)
                .subscribe(granted -> {
                    if (granted) {
                        init();
                    } else {
                        // Oups permission denied
                    }
                });

    }

    private void init() {
        EglBase.Context eglBaseContext = EglBase.create().getEglBaseContext();
        // create PeerConnectionFactory
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

        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        // create VideoCapturer
        VideoCapturer videoCapturer = createCameraCapturer(true);
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
        videoCapturer.startCapture(480, 640, 30);

        localView = findViewById(R.id.localView);
        localView.setMirror(true);
        localView.init(eglBaseContext, null);

        VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
        videoTrack.addSink(localView);
        mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream");
        mediaStream.addTrack(videoTrack);


        remoteView = findViewById(R.id.remoteView);
        remoteView.setMirror(false);
        remoteView.init(eglBaseContext, null);
        call();
    }


    private void call() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
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

    private VideoCapturer createCameraCapturer(boolean isFront) {
        Camera1Enumerator enumerator = new Camera1Enumerator(false);
        final String[] deviceNames = enumerator.getDeviceNames();
        for (String deviceName : deviceNames) {
            if (isFront ? enumerator.isFrontFacing(deviceName) : enumerator.isBackFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    private void createOff() {
        peerConnection.createOffer(new SdpAdapter("local offer sdp") {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new SdpAdapter("local set local"), sessionDescription);
                JSONObject rootObj = new JSONObject();
                try {
                    rootObj.put("call_from", registerName);
                    rootObj.put("call_to", callName);

                    JSONObject descObj = new JSONObject();
                    descObj.put("type", sessionDescription.type.canonicalForm());
                    descObj.put("sdp", sessionDescription.description);
                    rootObj.put("desc", descObj);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                Log.e("TAG", "发送offer " + rootObj.toString());
                mSocket.emit("offer",rootObj);
            }
        }, new MediaConstraints());
    }

    private void receiveAnswer(JSONObject obj) {
        Log.e("TAG", "receiveAnswer  --------->  " + obj.toString());
        peerConnection.setRemoteDescription(new SdpAdapter("localSetRemote"),
                new SessionDescription(SessionDescription.Type.ANSWER, obj.optJSONObject("desc").optString("sdp")));
    }

    private void receiveOffer(JSONObject obj) {
        runOnUiThread(() -> {
            callName = obj.optString("call_from");
            String desc = obj.optJSONObject("desc").optString("sdp");
            Log.e("TAG", "receiveOffer " + obj.toString());

            peerConnection.setRemoteDescription(new SdpAdapter("localSetRemote"), new SessionDescription(SessionDescription.Type.OFFER, desc));
            peerConnection.createAnswer(new SdpAdapter("localAnswerSdp") {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    super.onCreateSuccess(sdp);
                    peerConnection.setLocalDescription(new SdpAdapter("localSetLocal"), sdp);
                    JSONObject rootObj = new JSONObject();
                    try {
                        JSONObject sdpObj = new JSONObject();
                        sdpObj.put("type", sdp.type.canonicalForm());
                        sdpObj.put("sdp", sdp.description);
                        rootObj.put("desc", sdpObj);

                        rootObj.put("answer_from", registerName);
                        rootObj.put("answer_to", callName);
                        mSocket.emit("answer", rootObj);
                        Log.e("TAG", "发送answer成功" + rootObj.toString());

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }, new MediaConstraints());
        });
    }

    public void sendIceCandidate(IceCandidate iceCandidate) {
        try {
            JSONObject rootObj = new JSONObject();
            rootObj.put("call_to", callName);
            JSONObject jo = new JSONObject();
            jo.put("sdpMid", iceCandidate.sdpMid);
            jo.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            jo.put("candidate", iceCandidate.sdp);
            rootObj.put("candidate", jo);
            Log.e("TAG", "sendIceCandidate ==============>  " + rootObj.toString());
            mSocket.emit("candidate", rootObj);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private  void onIceCandidateReceived(JSONObject data) {
        Log.e("TAG", "onIceCandidateReceived ---> " + data.toString());
        peerConnection.addIceCandidate(new IceCandidate(
                data.optString("sdpMid"),
                data.optInt("sdpMLineIndex"),
                data.optString("candidate")
        ));
    }
}

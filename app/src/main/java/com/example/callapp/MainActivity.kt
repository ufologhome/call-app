package com.example.callapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.webrtc.*
import java.net.URI

class MainActivity : AppCompatActivity() {

    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private lateinit var ws: WebSocketClient

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissions()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        findViewById<Button>(R.id.btnCall).setOnClickListener {
            startWebSocket()
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            1
        )
    }

    private fun startWebSocket() {
        ws = object : WebSocketClient(URI("ws://192.168.0.150:8765")) {

            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d("WS", "Connected")
                runOnUiThread { createPeerConnection(true) }
            }

            override fun onMessage(message: String) {
                Log.d("WS", "Message: $message")
                handleSignal(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d("WS", "Closed")
            }

            override fun onError(ex: Exception?) {
                Log.e("WS", "Error", ex)
            }
        }
        ws.connect()
    }

    private fun createPeerConnection(isCaller: Boolean) {
        val config = PeerConnection.RTCConfiguration(iceServers)

        peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                ws.send("ICE|${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}")
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("audio", audioSource)
        val stream = factory.createLocalMediaStream("stream")
        stream.addTrack(audioTrack)
        peerConnection?.addStream(stream)

        if (isCaller) {
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    peerConnection?.setLocalDescription(this, desc)
                    ws.send("OFFER|${desc.description}")
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, MediaConstraints())
        }
    }

    private fun handleSignal(msg: String) {
        val parts = msg.split("|")
        when (parts[0]) {
            "OFFER" -> {
                createPeerConnection(false)
                val sdp = SessionDescription(SessionDescription.Type.OFFER, parts[1])
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        peerConnection?.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription) {
                                peerConnection?.setLocalDescription(this, desc)
                                ws.send("ANSWER|${desc.description}")
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, MediaConstraints())
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }

            "ANSWER" -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, parts[1])
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }

            "ICE" -> {
                val candidate = IceCandidate(parts[1], parts[2].toInt(), parts[3])
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }
}

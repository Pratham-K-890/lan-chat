package com.lanchat.android.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lanchat.android.databinding.ActivityVoiceCallBinding
import com.lanchat.android.network.ChatRepository
import com.lanchat.android.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class VoiceCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER_IP      = "peer_ip"
        const val EXTRA_PEER_PORT    = "peer_port"
        const val EXTRA_CALL_ID      = "call_id"
        const val EXTRA_IS_INITIATOR = "is_initiator"

        // Use different local ports for initiator vs receiver to avoid port conflicts
        // Initiator (HOST) listens on 9091, sends to peer's 9092
        // Receiver (CALLEE) listens on 9092, sends to peer's 9091
        private const val PORT_INITIATOR = 9091
        private const val PORT_RECEIVER  = 9092

        private const val SAMPLE_RATE      = 16000
        private const val CHANNEL_IN       = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT      = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING         = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_PACKET = 1280
    }

    private lateinit var binding: ActivityVoiceCallBinding
    private val vm: ChatViewModel by viewModels()
    private lateinit var audioManager: AudioManager

    private var peerIp      = ""
    private var callId      = ""
    private var isInitiator = false

    // Local port this device listens on for incoming UDP audio
    private var myUdpPort   = PORT_INITIATOR
    // Remote port the peer listens on (we send TO this)
    private var peerUdpPort = PORT_RECEIVER

    private var udpSocket:   DatagramSocket? = null
    private var audioRecord: AudioRecord?    = null
    private var audioTrack:  AudioTrack?     = null

    private var running     = false
    private var isMuted     = false
    private var callStartMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerIp      = intent.getStringExtra(EXTRA_PEER_IP)   ?: ""
        callId      = intent.getStringExtra(EXTRA_CALL_ID)   ?: ""
        isInitiator = intent.getBooleanExtra(EXTRA_IS_INITIATOR, false)

        // Initiator listens on 9091, sends to peer's 9092
        // Receiver listens on 9092, sends to peer's 9091
        if (isInitiator) {
            myUdpPort   = PORT_INITIATOR
            peerUdpPort = PORT_RECEIVER
        } else {
            myUdpPort   = PORT_RECEIVER
            peerUdpPort = PORT_INITIATOR
        }

        binding.tvCallStatus.text = if (peerIp.isNotEmpty()) peerIp else "Connecting..."
        binding.tvPeerIp.text     = if (isInitiator) "Calling..." else "Incoming call"
        binding.tvSessionId.text  = callId.take(8).uppercase()
        binding.tvFreq.text       = "16kHz · PCM · Mono"
        binding.tvLCh.text        = "[ ---- ]"
        binding.tvRCh.text        = "[ ---- ]"
        binding.tvCallTime.text   = "00:00"

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        setupButtons()
        observeCallEvents()

        if (hasMicPermission()) startAudio()
        else { toast("Microphone permission required"); finish() }
    }

    override fun onDestroy() {
        stopAudio()
        audioManager.mode = AudioManager.MODE_NORMAL
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun observeCallEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.messages.collect { event ->
                    when (event) {
                        is ChatRepository.ChatEvent.VoiceCallRejected -> {
                            toast("Call declined")
                            stopAudio()
                            finish()
                        }
                        is ChatRepository.ChatEvent.VoiceCallEnded -> {
                            toast("Call ended")
                            stopAudio()
                            finish()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnHangup.setOnClickListener {
            vm.hangup(callId)
            stopAudio()
            finish()
        }
        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            binding.btnMute.text = if (isMuted) "UNMUTE" else "MUTE"
        }
        binding.btnSpeaker.setOnClickListener {
            val on = audioManager.isSpeakerphoneOn
            audioManager.isSpeakerphoneOn = !on
            binding.btnSpeaker.text = if (!on) "EARPIECE" else "SPEAKER"
        }
    }

    private fun startAudio() {
        // Bind our UDP socket to myUdpPort
        udpSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(myUdpPort))
                soTimeout = 20
            }
        } catch (e: Exception) {
            toast("Cannot open UDP port $myUdpPort: ${e.message}")
            finish(); return
        }

        running     = true
        callStartMs = System.currentTimeMillis()
        binding.tvPeerIp.text = "Connected · port $myUdpPort"

        startCapture()
        startPlayback()
        startTimer()
        startVisualizer()
    }

    private fun stopAudio() {
        running = false
        try { udpSocket?.close() }                           catch (_: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release() }  catch (_: Exception) {}
        try { audioTrack?.stop();  audioTrack?.release() }   catch (_: Exception) {}
        udpSocket   = null
        audioRecord = null
        audioTrack  = null
    }

    // ── Capture: mic → UDP send ───────────────────────────────────────

    private fun startCapture() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (minBuf <= 0) { toast("AudioRecord not supported"); return }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING,
            maxOf(minBuf, BYTES_PER_PACKET * 2)
        )

        if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
            toast("Microphone init failed"); finish(); return
        }
        audioRecord!!.startRecording()

        Thread({
            val buf  = ByteArray(BYTES_PER_PACKET)
            val peer = try { InetAddress.getByName(peerIp) } catch (_: Exception) { null }

            if (peer == null) {
                handler.post { toast("Invalid peer IP: $peerIp") }
                return@Thread
            }

            while (running) {
                val read = try { audioRecord?.read(buf, 0, BYTES_PER_PACKET) ?: -1 }
                           catch (_: Exception) { -1 }
                if (read > 0 && !isMuted) {
                    try {
                        // Send to peer's listening port
                        udpSocket?.send(DatagramPacket(buf, read, peer, peerUdpPort))
                    } catch (_: Exception) {}
                }
            }
        }, "voice-capture").also { it.isDaemon = true; it.start() }
    }

    // ── Playback: UDP receive → speaker ──────────────────────────────

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        if (minBuf <= 0) { toast("AudioTrack not supported"); return }

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, BYTES_PER_PACKET * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack!!.play()

        Thread({
            // Receive buffer — slightly larger than send buffer
            val recvBuf = ByteArray(BYTES_PER_PACKET + 64)
            val packet  = DatagramPacket(recvBuf, recvBuf.size)

            while (running) {
                try {
                    udpSocket?.receive(packet)
                    // Write directly to AudioTrack — no jitter buffer needed for LAN
                    val len = packet.length
                    if (len > 0) {
                        audioTrack?.write(recvBuf, 0, len)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal — no data in this 20ms window, keep looping
                } catch (_: Exception) {
                    // Socket closed or other error — exit loop
                    break
                }
            }
        }, "voice-playback").also { it.isDaemon = true; it.start() }
    }

    private fun startTimer() {
        val r = object : Runnable {
            override fun run() {
                if (!running) return
                val s = ((System.currentTimeMillis() - callStartMs) / 1000).toInt()
                binding.tvCallTime.text = "%02d:%02d".format(s / 60, s % 60)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(r)
    }

    private fun startVisualizer() {
        val r = object : Runnable {
            override fun run() {
                if (!running) return
                binding.tvLCh.text = buildBar()
                binding.tvRCh.text = buildBar()
                handler.postDelayed(this, 150)
            }
        }
        handler.post(r)
    }

    private fun buildBar(): String {
        val n = (2..14).random()
        return "|".repeat(n) + ".".repeat(16 - n)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

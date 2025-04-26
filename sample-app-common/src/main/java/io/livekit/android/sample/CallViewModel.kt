/*
 * Copyright 2023-2025 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.sample

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.github.ajalt.timberkt.Timber
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalScreencastVideoTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.room.track.video.CameraCapturerUtils
import io.livekit.android.sample.model.StressTest
import io.livekit.android.sample.service.ForegroundService
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.CameraXHelper
import livekit.org.webrtc.RTCStatsReport

@OptIn(ExperimentalCamera2Interop::class)
class CallViewModel(
    val url: String,
    val token: String,
    application: Application,
    val e2ee: Boolean = false,
    val e2eeKey: String? = "",
    val audioProcessorOptions: AudioProcessorOptions? = null,
    val stressTest: StressTest = StressTest.None,
) : AndroidViewModel(application) {

    private fun getE2EEOptions(): E2EEOptions? {
        var e2eeOptions: E2EEOptions? = null
        if (e2ee && e2eeKey != null) {
            e2eeOptions = E2EEOptions()
        }
        e2eeOptions?.keyProvider?.setSharedKey(e2eeKey!!)
        return e2eeOptions
    }

    private fun getRoomOptions(): RoomOptions {
        return RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            e2eeOptions = getE2EEOptions(),
        )
    }

    val room = LiveKit.create(
        appContext = application,
        options = getRoomOptions(),
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(audioProcessorOptions = audioProcessorOptions),
        ),
    )

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null
    val audioHandler = room.audioHandler as AudioSwitchHandler

    val participants = room::remoteParticipants.flow
        .map { remoteParticipants ->
            listOf<Participant>(room.localParticipant) +
                remoteParticipants
                    .keys
                    .sortedBy { it.value }
                    .mapNotNull { remoteParticipants[it] }
        }

    private val mutableError = MutableStateFlow<Throwable?>(null)
    val error = mutableError.hide()

    private val mutablePrimarySpeaker = MutableStateFlow<Participant?>(null)
    val primarySpeaker: StateFlow<Participant?> = mutablePrimarySpeaker

    val activeSpeakers = room::activeSpeakers.flow

    private var localScreencastTrack: LocalScreencastVideoTrack? = null

    // Controls
    private val mutableMicEnabled = MutableLiveData(true)
    val micEnabled = mutableMicEnabled.hide()

    private val mutableCameraEnabled = MutableLiveData(true)
    val cameraEnabled = mutableCameraEnabled.hide()

    private val mutableScreencastEnabled = MutableLiveData(false)
    val screenshareEnabled = mutableScreencastEnabled.hide()

    private val mutableEnhancedNsEnabled = MutableLiveData(false)
    val enhancedNsEnabled = mutableEnhancedNsEnabled.hide()

    private val mutableEnableAudioProcessor = MutableLiveData(true)
    val enableAudioProcessor = mutableEnableAudioProcessor.hide()

    // Emits a string whenever a data message is received.
    private val mutableDataReceived = MutableSharedFlow<String>()
    val dataReceived = mutableDataReceived

    // Whether other participants are allowed to subscribe to this participant's tracks.
    private val mutablePermissionAllowed = MutableStateFlow(true)
    val permissionAllowed = mutablePermissionAllowed.hide()

    init {

        CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get()).let {
            if (it.isSupported(application)) {
                CameraCapturerUtils.registerCameraProvider(it)
                cameraProvider = it
            }
        }

        viewModelScope.launch {
            // Collect any errors.
            launch {
                error.collect { Timber.e(it) }
            }

            // Handle any changes in speakers.
            launch {
                combine(participants, activeSpeakers) { participants, speakers -> participants to speakers }
                    .collect { (participantsList, speakers) ->
                        handlePrimarySpeaker(
                            participantsList,
                            speakers,
                            room,
                        )
                    }
            }

            // Handle room events.
            launch {
                room.events.collect {
                    when (it) {
                        is RoomEvent.FailedToConnect -> mutableError.value = it.error
                        is RoomEvent.DataReceived -> {
                            val identity = it.participant?.identity ?: "server"
                            val message = it.data.toString(Charsets.UTF_8)
                            mutableDataReceived.emit("$identity: $message")
                        }

                        else -> {
                            Timber.e { "Room event: $it" }
                        }
                    }
                }
            }

            when (stressTest) {
                is StressTest.SwitchRoom -> launch { stressTest.execute() }
                is StressTest.None -> connectToRoom()
            }
        }

        // Start a foreground service to keep the call from being interrupted if the
        // app goes into the background.
        val foregroundServiceIntent = Intent(application, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(foregroundServiceIntent)
        } else {
            application.startService(foregroundServiceIntent)
        }
    }

    private suspend fun collectTrackStats(event: RoomEvent.TrackSubscribed) {
        val pub = event.publication
        while (true) {
            delay(10000)
            if (pub.subscribed) {
                val statsReport = pub.track?.getRTCStats() ?: continue
                Timber.e { "stats for ${pub.sid}:" }

                for (entry in statsReport.statsMap) {
                    Timber.e { "${entry.key} = ${entry.value}" }
                }
            }
        }
    }
    data class TrackStats(
//        val trackId: String,
        val kind: String,
        val codec: CodecInfo?,
//        val bitrate: Double,
//        val packetsLost: Int,
        val packetSend: Long,
        val sourceKind: MediaSourceInfo?,
        val remoteRTP: RemoteInboundRtp?,
        val timestamp: Long
    )

    private val _trackStats = MutableStateFlow<List<TrackStats>>(emptyList())
    val trackStats: StateFlow<List<TrackStats>> = _trackStats.asStateFlow()

    private val _chartLine = MutableStateFlow<List<Pair<Long , Long>>>(emptyList())
    val chartLine: StateFlow<List<Pair<Long, Long>>> = _chartLine.asStateFlow()

    private val _candidate = MutableStateFlow<List<IceCandidatePair>>(emptyList())
    val candidate = _candidate.asStateFlow()


    fun subscribeWebRTCStats2() {
        viewModelScope.launch(IO) {
            while (true) {
                delay(2_000)
                room.getSubscriberRTCStats() { report ->
                    Log.d("mohsen", "$report")
                    val t = extractTrackStatsWithCodecInBound(report)
                    val t2 = extractCandidateConnections(report)
                    Log.d("mohsen", "$t2")
                }
            }
        }
    }

    fun subscribeWebRTCStats(){
        viewModelScope.launch(IO) {
            while (true){
                delay(2_000)
                room.getPublisherRTCStats { report ->
                    Log.d("mohsen", "$report")
                    val newStats = extractTrackStatsWithCodecOutBound(report)
                    Log.d("mohsen", "$newStats")
                    val t = extractCandidateConnections(report)

                    _candidate.update {
                        t
                    }
//                    val newStats = mutableListOf<TrackStats>()
//                    for (stat in report.statsMap.values) {
//                        Log.d("mohsen" ,"$stat")
//
//
//
//                        if (stat.type == "inbound-rtp" ) {
////                            val trackId = stat.members["trackId"]?.toString() ?: continue
//                            val kind = stat.members["kind"]?.toString() ?: "unknown"
////                            val bitrate = stat.members["bitrateMean"]?.toString()?.toDoubleOrNull() ?: 0.0
////                            val packetsLost = stat.members["packetsLost"]?.toString()?.toIntOrNull() ?: 0
//                            val timestamp: Long = stat.timestampUs.toLong() / 1000
////                            newStats.add(TrackStats(trackId, kind, bitrate, packetsLost, timestamp))
//                            newStats.add(TrackStats( kind, timestamp))
//                        }
//                    }
//                    Log.d("mohsen" ,"$newStats")

                    val newList: List<Pair<Long, Long>> = newStats.filter { it.kind == "audio" }.map {
                        it.packetSend to it.timestamp
                    }

//                    _chartLine.update {
//                            (listOf( 0L to 0L) + it.drop(1) + newList).takeLast(10)
//                    }
                    Log.d("mohsen_" , "${_chartLine.value}")

                    _trackStats.value = newStats
//                }

//                room.getPublisherRTCStats {
//
                }
            }
        }
    }

//    private fun generateMockBarData(
//        size: Int, useColor: Boolean = false, hasNegative: Boolean = true
//    ): List<BarData> {
//        val years = listOf("2021", "2022", "2023", "2024", "2025", "2026", "2027")
//        val colors = listOf(
//            Color.Red,
//            Color.Green,
//            Color.Blue,
//            Color.Yellow,
//            Color.DarkGray,
//            Color.Magenta,
//            Color.Cyan
//        )
//
//        val number = if (hasNegative) -10 else 0
//        val data = List(size) {
//            BarData(
//                yValue = Random.nextFloat() * 20 + number, // Random value between -10 and 10
//                xValue = years[it % years.size],
//                barColor = if (useColor) colors[it % colors.size].asSolidChartColor() else Color.Unspecified.asSolidChartColor()
//            )
//        }.toMutableList()
//
//        // Ensure one value is always 0
//        if (data.isNotEmpty()) {
//            val zeroIndex = Random.nextInt(size)
//            data[zeroIndex] = data[zeroIndex].copy(yValue = 0f)
//        }
//
//        return data
//    }

    private fun extractCandidateConnections(report: RTCStatsReport): List<IceCandidatePair> {
        val candidatesMap = report.statsMap
            .filterValues { it.type == "local-candidate" || it.type == "remote-candidate" }
            .mapValues { (_, stat) ->
                IceCandidate(
                    id = stat.id,
                    candidateType = stat.members["candidateType"]?.toString(),
                    ip = stat.members["ip"]?.toString(),
                    port = stat.members["port"]?.toString()?.toIntOrNull(),
                    protocol = stat.members["protocol"]?.toString(),
                    relayProtocol = stat.members["relayProtocol"]?.toString(),
                    priority = stat.members["priority"]?.toString()?.toLongOrNull()
                )
            }

        val candidatePairs: List<IceCandidatePair> = report.statsMap
            .filterValues { it.type == "candidate-pair" }
            .mapNotNull { (_, stat) ->
                val id = stat.id
                val localId = stat.members["localCandidateId"]?.toString()
                val remoteId = stat.members["remoteCandidateId"]?.toString()
                val state = stat.members["state"]?.toString()
                val nominated = stat.members["nominated"]?.toString()?.toBoolean()
                val totalRtt = stat.members["totalRoundTripTime"]?.toString()?.toDoubleOrNull()
                val currentRtt = stat.members["currentRoundTripTime"]?.toString()?.toDoubleOrNull()
                val bitrate = stat.members["availableOutgoingBitrate"]?.toString()?.toDoubleOrNull()
                val reqRecv = stat.members["requestsReceived"]?.toString()?.toLongOrNull()
                val reqSent = stat.members["requestsSent"]?.toString()?.toLongOrNull()
                val resRecv = stat.members["responsesReceived"]?.toString()?.toLongOrNull()
                val resSent = stat.members["responsesSent"]?.toString()?.toLongOrNull()
                val timestamp = stat.timestampUs

                IceCandidatePair(
                    id = id,
                    localCandidate = localId?.let { candidatesMap[it] },
                    remoteCandidate = remoteId?.let { candidatesMap[it] },
                    state = state,
                    nominated = nominated,
                    totalRoundTripTime = totalRtt,
                    currentRoundTripTime = currentRtt,
                    availableOutgoingBitrate = bitrate,
                    requestsReceived = reqRecv,
                    requestsSent = reqSent,
                    responsesReceived = resRecv,
                    responsesSent = resSent,
                    timestamp = timestamp
                )
            }

        return candidatePairs
    }
    private fun extractTrackStatsWithCodecOutBound(report: RTCStatsReport): List<TrackStats> {
        val codecMap: Map<String, CodecInfo> = report.statsMap
            .filterValues { it.type == "codec" }
            .mapNotNull { (_, stat) ->
                val id = stat.id
                val mimeType = stat.members["mimeType"]?.toString()
                val payloadType = stat.members["payloadType"]?.toString()?.toIntOrNull()
                val clockRate = stat.members["clockRate"]?.toString()?.toIntOrNull()
                val sdpFmtpLine = stat.members["sdpFmtpLine"]?.toString()
                val channels = stat.members["channels"]?.toString()?.toIntOrNull()

                val kind = mimeType?.substringBefore('/') // e.g., "audio" or "video"

                when (kind) {
                    "audio" -> id to CodecInfo.Audio(
                        id = id,
                        mimeType = mimeType,
                        payloadType = payloadType,
                        clockRate = clockRate,
                        channels = channels,
                        sdpFmtpLine = sdpFmtpLine
                    )
                    "video" -> id to CodecInfo.Video(
                        id = id,
                        mimeType = mimeType,
                        payloadType = payloadType,
                        clockRate = clockRate,
                        sdpFmtpLine = sdpFmtpLine
                    )
                    else -> null // ignore unknown codec types
                }
            }.toMap()


        val mediaSourceMap: Map<String, MediaSourceInfo> = report.statsMap
            .filterValues { it.type == "media-source" }
            .mapNotNull { (_, stat) ->
                val id = stat.id
                val kind = stat.members["kind"]?.toString() ?: return@mapNotNull null
                val trackIdentifier = stat.members["trackIdentifier"]?.toString()

                when (kind) {
                    "audio" -> {
                        val audioLevel = stat.members["audioLevel"]?.toString()?.toDoubleOrNull()
                        val totalAudioEnergy = stat.members["totalAudioEnergy"]?.toString()?.toDoubleOrNull()
                        val totalSamplesDuration = stat.members["totalSamplesDuration"]?.toString()?.toDoubleOrNull()
                        val echoReturnLoss = stat.members["echoReturnLoss"]?.toString()?.toDoubleOrNull()
                        val echoReturnLossEnhancement = stat.members["echoReturnLossEnhancement"]?.toString()?.toDoubleOrNull()

                        id to MediaSourceInfo.Audio(
                            id = id,
                            trackIdentifier = trackIdentifier,
                            audioLevel = audioLevel,
                            totalAudioEnergy = totalAudioEnergy,
                            totalSamplesDuration = totalSamplesDuration,
                            echoReturnLoss = echoReturnLoss,
                            echoReturnLossEnhancement = echoReturnLossEnhancement
                        )
                    }

                    "video" -> {
                        val width = stat.members["width"]?.toString()?.toIntOrNull()
                        val height = stat.members["height"]?.toString()?.toIntOrNull()
                        val fps = stat.members["framesPerSecond"]?.toString()?.toDoubleOrNull()

                        id to MediaSourceInfo.Video(
                            id = id,
                            trackIdentifier = trackIdentifier,
                            width = width,
                            height = height,
                            framesPerSecond = fps
                        )
                    }

                    else -> null // skip unknown types
                }
            }.toMap()

        val remoteInboundRtpMap: Map<String, RemoteInboundRtp> = report.statsMap
            .filterValues { it.type == "remote-inbound-rtp" }
            .mapNotNull { (_, stat) ->
                val id = stat.id
                val kind = stat.members["kind"]?.toString() ?: return@mapNotNull null
                val ssrc = stat.members["ssrc"]?.toString()?.toLongOrNull()
                val transportId = stat.members["transportId"]?.toString()
                val codecId = stat.members["codecId"]?.toString()
                val jitter = stat.members["jitter"]?.toString()?.toDoubleOrNull()
                val roundTripTime = stat.members["roundTripTime"]?.toString()?.toDoubleOrNull()
                val packetsLost = stat.members["packetsLost"]?.toString()?.toLongOrNull()
                val fractionLost = stat.members["fractionLost"]?.toString()?.toDoubleOrNull()
                val timestamp = stat.timestampUs

                when (kind) {
                    "audio" -> id to RemoteInboundRtp.Audio(
                        id, kind, ssrc, transportId, codecId, jitter,
                        roundTripTime, packetsLost, fractionLost, timestamp
                    )
                    "video" -> id to RemoteInboundRtp.Video(
                        id, kind, ssrc, transportId, codecId, jitter,
                        roundTripTime, packetsLost, fractionLost, timestamp
                    )
                    else -> null
                }
            }.toMap()



        // Step 2: Build list of TrackStats from inbound-rtp
        return report.statsMap.values
            .filter { it.type == "outbound-rtp" }
            .map {
                val kind = it.members["kind"]?.toString() ?: "unknown"
                val timestamp: Long = it.timestampUs.toLong() / 1000

                val codecId = it.members["codecId"]?.toString()
                val codec = codecMap[codecId]

                val packetSend = it.members["packetsSent"]?.toString()?.toLong() ?: 0

                val mediaSourceId = it.members["mediaSourceId"]
                val sourceKind = mediaSourceMap[mediaSourceId]

                val remoteRTPId = it.members["remoteId"]
                val remoteRTP = remoteInboundRtpMap[remoteRTPId]

                TrackStats(
                    kind,
                    codec,
                    packetSend,
                    sourceKind,
                    remoteRTP,
                    timestamp
                )
            }
    }

    private fun extractTrackStatsWithCodecInBound(report: RTCStatsReport): List<TrackStats> {
        val codecMap: Map<String, CodecInfo> = report.statsMap
            .filterValues { it.type == "codec" }
            .mapNotNull { (_, stat) ->
                val id = stat.id
                val mimeType = stat.members["mimeType"]?.toString()
                val payloadType = stat.members["payloadType"]?.toString()?.toIntOrNull()
                val clockRate = stat.members["clockRate"]?.toString()?.toIntOrNull()
                val sdpFmtpLine = stat.members["sdpFmtpLine"]?.toString()
                val channels = stat.members["channels"]?.toString()?.toIntOrNull()

                val kind = mimeType?.substringBefore('/') // e.g., "audio" or "video"

                when (kind) {
                    "audio" -> id to CodecInfo.Audio(
                        id = id,
                        mimeType = mimeType,
                        payloadType = payloadType,
                        clockRate = clockRate,
                        channels = channels,
                        sdpFmtpLine = sdpFmtpLine
                    )
                    "video" -> id to CodecInfo.Video(
                        id = id,
                        mimeType = mimeType,
                        payloadType = payloadType,
                        clockRate = clockRate,
                        sdpFmtpLine = sdpFmtpLine
                    )
                    else -> null // ignore unknown codec types
                }
            }.toMap()


        val mediaSourceMap: Map<String, MediaSourceInfo> = report.statsMap
            .filterValues { it.type == "media-source" }
            .mapNotNull { (_, stat) ->
                val id = stat.id
                val kind = stat.members["kind"]?.toString() ?: return@mapNotNull null
                val trackIdentifier = stat.members["trackIdentifier"]?.toString()

                when (kind) {
                    "audio" -> {
                        val audioLevel = stat.members["audioLevel"]?.toString()?.toDoubleOrNull()
                        val totalAudioEnergy = stat.members["totalAudioEnergy"]?.toString()?.toDoubleOrNull()
                        val totalSamplesDuration = stat.members["totalSamplesDuration"]?.toString()?.toDoubleOrNull()
                        val echoReturnLoss = stat.members["echoReturnLoss"]?.toString()?.toDoubleOrNull()
                        val echoReturnLossEnhancement = stat.members["echoReturnLossEnhancement"]?.toString()?.toDoubleOrNull()

                        id to MediaSourceInfo.Audio(
                            id = id,
                            trackIdentifier = trackIdentifier,
                            audioLevel = audioLevel,
                            totalAudioEnergy = totalAudioEnergy,
                            totalSamplesDuration = totalSamplesDuration,
                            echoReturnLoss = echoReturnLoss,
                            echoReturnLossEnhancement = echoReturnLossEnhancement
                        )
                    }

                    "video" -> {
                        val width = stat.members["width"]?.toString()?.toIntOrNull()
                        val height = stat.members["height"]?.toString()?.toIntOrNull()
                        val fps = stat.members["framesPerSecond"]?.toString()?.toDoubleOrNull()

                        id to MediaSourceInfo.Video(
                            id = id,
                            trackIdentifier = trackIdentifier,
                            width = width,
                            height = height,
                            framesPerSecond = fps
                        )
                    }

                    else -> null // skip unknown types
                }
            }.toMap()

        val remoteInboundRtpMap: Map<String, RemoteInboundRtp> = report.statsMap
            .filterValues { it.type == "remote-outbound-rtp" }
            .mapNotNull { (_, stat) ->
                val id = stat.id
                val kind = stat.members["kind"]?.toString() ?: return@mapNotNull null
                val ssrc = stat.members["ssrc"]?.toString()?.toLongOrNull()
                val transportId = stat.members["transportId"]?.toString()
                val codecId = stat.members["codecId"]?.toString()
                val jitter = stat.members["jitter"]?.toString()?.toDoubleOrNull()
                val roundTripTime = stat.members["roundTripTime"]?.toString()?.toDoubleOrNull()
                val packetsLost = stat.members["packetsLost"]?.toString()?.toLongOrNull()
                val fractionLost = stat.members["fractionLost"]?.toString()?.toDoubleOrNull()
                val timestamp = stat.timestampUs

                when (kind) {
                    "audio" -> id to RemoteInboundRtp.Audio(
                        id, kind, ssrc, transportId, codecId, jitter,
                        roundTripTime, packetsLost, fractionLost, timestamp
                    )
                    "video" -> id to RemoteInboundRtp.Video(
                        id, kind, ssrc, transportId, codecId, jitter,
                        roundTripTime, packetsLost, fractionLost, timestamp
                    )
                    else -> null
                }
            }.toMap()



        // Step 2: Build list of TrackStats from inbound-rtp
        return report.statsMap.values
            .filter { it.type == "inbound-rtp" }
            .map {
                val kind = it.members["kind"]?.toString() ?: "unknown"
                val timestamp: Long = it.timestampUs.toLong() / 1000

                val codecId = it.members["codecId"]?.toString()
                val codec = codecMap[codecId]

                val packetSend = it.members["packetsSent"]?.toString()?.toLong() ?: 0

                val mediaSourceId = it.members["mediaSourceId"]
                val sourceKind = mediaSourceMap[mediaSourceId]

                val remoteRTPId = it.members["remoteId"]
                val remoteRTP = remoteInboundRtpMap[remoteRTPId]

                TrackStats(
                    kind,
                    codec,
                    packetSend,
                    sourceKind,
                    remoteRTP,
                    timestamp
                )
            }
    }

//    private fun extractTrackStatsWithCodec(report: RTCStatsReport): List<TrackStats> {
//        // Step 1: Build a codec map: codecId -> codecName
//        val codecMap = report.statsMap
//            .filterValues { it.type == "codec" }
//            .mapNotNull { (_, stat) ->
//                val codecId = stat.id
//                val codecName = stat.members["mimeType"]?.toString()
//                if (codecId != null && codecName != null) codecId to codecName else null
//            }
//            .toMap()
//
//        // Step 2: Build list of TrackStats from inbound-rtp
//        return report.statsMap.values
//            .filter { it.type == "inbound-rtp" }
//            .map {
//                val kind = it.members["kind"]?.toString() ?: "unknown"
//                val timestamp: Long = it.timestampUs.toLong() / 1000
//                val codecId = it.members["codecId"]?.toString()
//                val codec = codecMap[codecId] ?: "unknown"
//                TrackStats(kind, codec, timestamp)
//            }
//    }


    fun toggleEnhancedNs(enabled: Boolean? = null) {
        if (enabled != null) {
            mutableEnableAudioProcessor.postValue(enabled)
            room.audioProcessingController.setBypassForCapturePostProcessing(!enabled)
            return
        }

        if (room.audioProcessorIsEnabled) {
            if (enableAudioProcessor.value == true) {
                room.audioProcessingController.setBypassForCapturePostProcessing(true)
                mutableEnableAudioProcessor.postValue(false)
            } else {
                room.audioProcessingController.setBypassForCapturePostProcessing(false)
                mutableEnableAudioProcessor.postValue(true)
            }
        }
    }

    private suspend fun connectToRoom() {
        try {
            room.e2eeOptions = getE2EEOptions()
            room.connect(
                url = url,
                token = token,
            )

            mutableEnhancedNsEnabled.postValue(room.audioProcessorIsEnabled)
            mutableEnableAudioProcessor.postValue(true)

            // Create and publish audio/video tracks
            val localParticipant = room.localParticipant
            localParticipant.setMicrophoneEnabled(true)
            mutableMicEnabled.postValue(localParticipant.isMicrophoneEnabled())

            localParticipant.setCameraEnabled(true)
            mutableCameraEnabled.postValue(localParticipant.isCameraEnabled())

            // Update the speaker
            handlePrimarySpeaker(emptyList(), emptyList(), room)
        } catch (e: Throwable) {
            mutableError.value = e
        }
    }

    private fun handlePrimarySpeaker(participantsList: List<Participant>, speakers: List<Participant>, room: Room?) {
        var speaker = mutablePrimarySpeaker.value

        // If speaker is local participant (due to defaults),
        // attempt to find another remote speaker to replace with.
        if (speaker is LocalParticipant) {
            val remoteSpeaker = participantsList
                .filterIsInstance<RemoteParticipant>() // Try not to display local participant as speaker.
                .firstOrNull()

            if (remoteSpeaker != null) {
                speaker = remoteSpeaker
            }
        }

        // If previous primary speaker leaves
        if (!participantsList.contains(speaker)) {
            // Default to another person in room, or local participant.
            speaker = participantsList.filterIsInstance<RemoteParticipant>()
                .firstOrNull()
                ?: room?.localParticipant
        }

        if (speakers.isNotEmpty() && !speakers.contains(speaker)) {
            val remoteSpeaker = speakers
                .filterIsInstance<RemoteParticipant>() // Try not to display local participant as speaker.
                .firstOrNull()

            if (remoteSpeaker != null) {
                speaker = remoteSpeaker
            }
        }

        mutablePrimarySpeaker.value = speaker
    }

    /**
     * Start a screen capture with the result intent from
     * [MediaProjectionManager.createScreenCaptureIntent]
     */
    fun startScreenCapture(mediaProjectionPermissionResultData: Intent) {
        val localParticipant = room.localParticipant
        viewModelScope.launch {
            localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(mediaProjectionPermissionResultData))
            val screencastTrack = localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalScreencastVideoTrack
            this@CallViewModel.localScreencastTrack = screencastTrack
            mutableScreencastEnabled.postValue(screencastTrack?.enabled)
        }
    }

    fun stopScreenCapture() {
        viewModelScope.launch {
            localScreencastTrack?.let { localScreencastVideoTrack ->
                localScreencastVideoTrack.stop()
                room.localParticipant.unpublishTrack(localScreencastVideoTrack)
                mutableScreencastEnabled.postValue(localScreencastTrack?.enabled ?: false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        // Make sure to release any resources associated with LiveKit
        room.disconnect()
        room.release()

        // Clean up foreground service
        val application = getApplication<Application>()
        val foregroundServiceIntent = Intent(application, ForegroundService::class.java)
        application.stopService(foregroundServiceIntent)
        cameraProvider?.let {
            CameraCapturerUtils.unregisterCameraProvider(it)
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        viewModelScope.launch {
            room.localParticipant.setMicrophoneEnabled(enabled)
            mutableMicEnabled.postValue(enabled)
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        viewModelScope.launch {
            room.localParticipant.setCameraEnabled(enabled)
            mutableCameraEnabled.postValue(enabled)
        }
    }

    fun flipCamera() {
        val videoTrack = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
            ?.track as? LocalVideoTrack
            ?: return

        val newPosition = when (videoTrack.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }

        videoTrack.switchCamera(position = newPosition)
    }

    fun dismissError() {
        mutableError.value = null
    }

    fun sendData(message: String) {
        viewModelScope.launch {
            room.localParticipant.publishData(message.toByteArray(Charsets.UTF_8))
        }
    }

    fun toggleSubscriptionPermissions() {
        mutablePermissionAllowed.value = !mutablePermissionAllowed.value
        room.localParticipant.setTrackSubscriptionPermissions(mutablePermissionAllowed.value)
    }

    // Debug functions
    fun simulateMigration() {
        room.sendSimulateScenario(Room.SimulateScenario.MIGRATION)
    }

    fun simulateNodeFailure() {
        room.sendSimulateScenario(Room.SimulateScenario.NODE_FAILURE)
    }

    fun simulateServerLeaveFullReconnect() {
        room.sendSimulateScenario(Room.SimulateScenario.SERVER_LEAVE_FULL_RECONNECT)
    }

    fun updateAttribute(key: String, value: String) {
        room.localParticipant.updateAttributes(mapOf(key to value))
    }

    fun reconnect() {
        Timber.e { "Reconnecting." }
        mutablePrimarySpeaker.value = null
        room.disconnect()
        viewModelScope.launch {
            connectToRoom()
        }
    }

    private suspend fun StressTest.SwitchRoom.execute() = coroutineScope {
        launch {
            while (isActive) {
                delay(2000)
                dumpReferenceTables()
            }
        }

        while (isActive) {
            Timber.d { "Stress test -> connect to first room" }
            launch { quickConnectToRoom(firstToken) }
            delay(200)
            room.disconnect()
            delay(50)
            Timber.d { "Stress test -> connect to second room" }
            launch { quickConnectToRoom(secondToken) }
            delay(200)
            room.disconnect()
            delay(50)
        }
    }

    private suspend fun quickConnectToRoom(token: String) {
        try {
            room.connect(
                url = url,
                token = token,
            )
        } catch (e: Throwable) {
            Timber.e(e) { "Failed to connect to room" }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun dumpReferenceTables() {
        try {
            val cls = Class.forName("android.os.Debug")
            val method = cls.getDeclaredMethod("dumpReferenceTables")
            val con = cls.getDeclaredConstructor().apply {
                isAccessible = true
            }
            method.invoke(con.newInstance())
        } catch (e: Exception) {
            LKLog.e(e) { "Unable to dump reference tables, you can try `adb shell settings put global hidden_api_policy 1`" }
        }
    }
}

private fun <T> LiveData<T>.hide(): LiveData<T> = this
private fun <T> MutableStateFlow<T>.hide(): StateFlow<T> = this
private fun <T> Flow<T>.hide(): Flow<T> = this


sealed class MediaSourceInfo {
    abstract val id: String
    abstract val trackIdentifier: String?

    data class Audio(
        override val id: String,
        override val trackIdentifier: String?,
        val audioLevel: Double?,
        val totalAudioEnergy: Double?,
        val totalSamplesDuration: Double?,
        val echoReturnLoss: Double?,
        val echoReturnLossEnhancement: Double?
    ) : MediaSourceInfo()

    data class Video(
        override val id: String,
        override val trackIdentifier: String?,
        val width: Int?,
        val height: Int?,
        val framesPerSecond: Double?
    ) : MediaSourceInfo()
}

sealed class CodecInfo {
    abstract val id: String
    abstract val mimeType: String?
    abstract val payloadType: Int?
    abstract val clockRate: Int?

    data class Audio(
        override val id: String,
        override val mimeType: String?,
        override val payloadType: Int?,
        override val clockRate: Int?,
        val channels: Int?,
        val sdpFmtpLine: String?
    ) : CodecInfo()

    data class Video(
        override val id: String,
        override val mimeType: String?,
        override val payloadType: Int?,
        override val clockRate: Int?,
        val sdpFmtpLine: String?
    ) : CodecInfo()
}

sealed class RemoteInboundRtp {
    abstract val id: String
    abstract val kind: String
    abstract val ssrc: Long?
    abstract val transportId: String?
    abstract val codecId: String?
    abstract val jitter: Double?
    abstract val roundTripTime: Double?
    abstract val packetsLost: Long?
    abstract val fractionLost: Double?
    abstract val timestamp: Double

    data class Audio(
        override val id: String,
        override val kind: String,
        override val ssrc: Long?,
        override val transportId: String?,
        override val codecId: String?,
        override val jitter: Double?,
        override val roundTripTime: Double?,
        override val packetsLost: Long?,
        override val fractionLost: Double?,
        override val timestamp: Double
    ) : RemoteInboundRtp()

    data class Video(
        override val id: String,
        override val kind: String,
        override val ssrc: Long?,
        override val transportId: String?,
        override val codecId: String?,
        override val jitter: Double?,
        override val roundTripTime: Double?,
        override val packetsLost: Long?,
        override val fractionLost: Double?,
        override val timestamp: Double
    ) : RemoteInboundRtp()
}

data class IceCandidate(
    val id: String,
    val candidateType: String?,
    val ip: String?,
    val port: Int?,
    val protocol: String?,
    val relayProtocol: String?,
    val priority: Long?
)

data class IceCandidatePair(
    val id: String,
    val localCandidate: IceCandidate?,
    val remoteCandidate: IceCandidate?,
    val state: String?,
    val nominated: Boolean?,
    val totalRoundTripTime: Double?,
    val currentRoundTripTime: Double?,
    val availableOutgoingBitrate: Double?,
    val requestsReceived: Long?,
    val requestsSent: Long?,
    val responsesReceived: Long?,
    val responsesSent: Long?,
    val timestamp: Double
)




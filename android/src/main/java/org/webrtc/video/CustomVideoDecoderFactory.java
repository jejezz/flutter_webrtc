package org.webrtc.video;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import androidx.annotation.Nullable;

import org.webrtc.EglBase;
import org.webrtc.Logging;
import org.webrtc.SoftwareVideoDecoderFactory;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoDecoder;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.WrappedVideoDecoderFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CustomVideoDecoderFactory implements VideoDecoderFactory {
    private static final String TAG = "CustomVideoDecoderFactory";
    private static final String H264_NAME = "H264";
    private static final String H264_MIME = "video/avc";
    private static final String PROFILE_LEVEL_ID = "profile-level-id";
    private static final String PACKETIZATION_MODE = "packetization-mode";

    /**
     * `packetization-mode=0` 변형을 붙일 프로파일.
     *
     * 동적 페이로드 타입이 한정돼 있어(코덱마다 rtx 가 하나씩 더 붙는다) 전부에
     * 붙이지 않는다. mode 0 으로 선언하고 오는 것은 SIP 게이트웨이 뒤의 단말이고
     * 그쪽은 Baseline 계열을 쓴다. High 계열을 mode 0 으로 보내 오는 상대는 없다.
     */
    private static final Set<String> MODE_ZERO_PROFILES =
            new HashSet<>(Arrays.asList("4200", "42e0"));

    /**
     * libwebrtc 가 파싱하는 최대 level_idc.
     *
     * H264Level enum 이 kLevel5_2 = 52 에서 끝난다. ParseProfileLevelId 의 switch
     * 에 6.0 이상이 없어 default 로 떨어지고 nullopt 를 돌려주는데, 그러면
     * H264IsSameProfile 이 프로파일을 비교하기도 전에 false 가 된다 — 레벨 하나
     * 때문에 그 항목이 협상에서 통째로 빠진다.
     *
     * 하드웨어가 6.2 를 디코딩한다면 5.2 도 디코딩한다. 상한을 두는 것이 값을
     * 버리는 것보다 정직하다.
     */
    private static final int MAX_PARSABLE_LEVEL_IDC = 52;

    /**
     * MediaCodec 의 AVC 프로파일 → SDP `profile-level-id` 의 앞 네 자리
     * (`profile_idc` + `profile_iop`).
     *
     * WebRTC 가 실제로 쓰는 8비트 4:2:0 프로파일만 담는다. High10·High422·High444
     * 는 넣지 않는다 — 협상에 올릴 이유가 없고, 상대가 그것을 고르면 디코더
     * 경로가 어떻게 될지 보장할 수 없다.
     */
    private static final Map<Integer, String> AVC_PROFILES = new HashMap<>();

    /** MediaCodec 의 AVC 레벨 → `level_idc`. */
    private static final Map<Integer, Integer> AVC_LEVELS = new HashMap<>();

    static {
        AVC_PROFILES.put(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, "42e0");
        AVC_PROFILES.put(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, "4200");
        AVC_PROFILES.put(MediaCodecInfo.CodecProfileLevel.AVCProfileMain, "4d00");
        AVC_PROFILES.put(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh, "640c");
        AVC_PROFILES.put(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, "6400");

        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel1, 10);
        // 1b 는 level_idc 11 에 constraint_set3 를 함께 쓰는 예외다. 11 로만 적는다.
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel1b, 11);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel11, 11);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel12, 12);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel13, 13);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel2, 20);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel21, 21);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel22, 22);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel3, 30);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel31, 31);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel32, 32);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel4, 40);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel41, 41);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel42, 42);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel5, 50);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel51, 51);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel52, 52);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel6, 60);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel61, 61);
        AVC_LEVELS.put(MediaCodecInfo.CodecProfileLevel.AVCLevel62, 62);
    }

    private SoftwareVideoDecoderFactory softwareVideoDecoderFactory = new SoftwareVideoDecoderFactory();
    private WrappedVideoDecoderFactory wrappedVideoDecoderFactory;
    private boolean forceSWCodec  = false;

    private List<String> forceSWCodecs = new ArrayList<>();

    public  CustomVideoDecoderFactory(EglBase.Context sharedContext) {
        this.wrappedVideoDecoderFactory = new WrappedVideoDecoderFactory(sharedContext);
    }

    public void setForceSWCodec(boolean forceSWCodec) {
        this.forceSWCodec = forceSWCodec;
    }

    public void setForceSWCodecList(List<String> forceSWCodecs) {
        this.forceSWCodecs = forceSWCodecs;
    }

    @Nullable
    @Override
    public VideoDecoder createDecoder(VideoCodecInfo videoCodecInfo) {
        if(forceSWCodec) {
            return softwareVideoDecoderFactory.createDecoder(videoCodecInfo);
        }
        if(!forceSWCodecs.isEmpty()) {
            if(forceSWCodecs.contains(videoCodecInfo.name)) {
                return softwareVideoDecoderFactory.createDecoder(videoCodecInfo);
            }
        }
        return wrappedVideoDecoderFactory.createDecoder(videoCodecInfo);
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        VideoCodecInfo[] codecs;
        if(forceSWCodec && forceSWCodecs.isEmpty()) {
            codecs = softwareVideoDecoderFactory.getSupportedCodecs();
        } else {
            codecs = wrappedVideoDecoderFactory.getSupportedCodecs();
        }

        return withHigherH264Level(
                withPacketizationModeZero(withReportedH264Profiles(codecs)));
    }

    /**
     * 같은 프로파일의 `packetization-mode=0` 변형을 덧붙인다.
     *
     * H264Utils 는 프로파일뿐 아니라 packetization-mode 도 고정한다 — 1 만
     * 내놓는다. 그런데 libwebrtc 의 H.264 depacketizer 는 **이 파라미터를 읽지
     * 않는다.** RTP 페이로드 첫 바이트의 NAL 타입(1~23 단일, 24 STAP-A, 28 FU-A)
     * 으로 판단하므로 mode 0 으로 보내 오는 스트림도 같은 코드로 풀린다.
     * libwebrtc 자신의 소프트웨어 경로(SupportedH264Codecs)도 mode 0 과 1 을 모두
     * 광고한다.
     *
     * 그래서 mode 1 만 광고하는 것은 받을 수 있는 것을 못 받는 것이다 —
     * 프로파일과 똑같은 구조의 누락이다. SIP 게이트웨이 뒤의 단말이 mode 0 으로
     * 선언하면 영상 m-line 이 통째로 거절된다.
     */
    private VideoCodecInfo[] withPacketizationModeZero(VideoCodecInfo[] codecs) {
        Set<String> present = new HashSet<>();
        for (VideoCodecInfo codec : codecs) {
            if (H264_NAME.equalsIgnoreCase(codec.name)) {
                present.add(profileModeKey(codec));
            }
        }

        List<VideoCodecInfo> result = new ArrayList<>(Arrays.asList(codecs));
        for (VideoCodecInfo codec : codecs) {
            if (!H264_NAME.equalsIgnoreCase(codec.name)) {
                continue;
            }
            String profile = profileOf(codec);
            if (profile == null || !MODE_ZERO_PROFILES.contains(profile)) {
                continue;
            }
            if (!present.add(profile + "|0")) {
                continue;   // 이미 mode 0 을 광고하는 프로파일이다
            }
            Map<String, String> params = new HashMap<>(codec.params);
            params.put(PACKETIZATION_MODE, "0");
            result.add(new VideoCodecInfo(codec.name, params, codec.scalabilityModes));
        }
        return result.toArray(new VideoCodecInfo[0]);
    }

    /** 프로파일 네 자리. 없거나 짧으면 null. */
    @Nullable
    private static String profileOf(VideoCodecInfo codec) {
        String profileLevelId = codec.params.get(PROFILE_LEVEL_ID);
        if (profileLevelId == null || profileLevelId.length() < 4) {
            return null;
        }
        return profileLevelId.substring(0, 4).toLowerCase(Locale.US);
    }

    /** 프로파일과 packetization-mode 를 합친 키. 규격상 mode 가 없으면 0 이다. */
    private static String profileModeKey(VideoCodecInfo codec) {
        String mode = codec.params.get(PACKETIZATION_MODE);
        return profileOf(codec) + "|" + (mode == null ? "0" : mode);
    }

    /**
     * MediaCodec 이 보고하는 H.264 프로파일을 광고 목록에 반영한다.
     *
     * libwebrtc 는 디코더를 **찾기만** 한다. MediaCodecList 를 열거해 video/avc
     * 디코더를 찾고 컬러 포맷과 이름까지는 보지만, CodecCapabilities.profileLevels
     * 를 읽지 않는다. SDP 로 나가는 값은 H264Utils 에 박힌 Constrained Baseline
     * 하나뿐이고, Constrained High 는 코덱 이름이 `OMX.qcom.`/`OMX.Exynos.` 로
     * 시작할 때만 붙는다 — 요즘 기기의 Codec2 이름(`c2.qti.avc.decoder`)은 그
     * 접두사에 걸리지 않아 늘 빠진다.
     *
     * 그래서 기기가 Baseline 이나 Main 을 디코딩할 수 있는데도 상대가 그렇게
     * 선언하면 프로파일 매칭에서 떨어지고, 영상 m-line 이 통째로 포트 0 으로
     * 거절된다. SIP 게이트웨이를 거쳐 오는 인터폰·공동현관기가 Baseline
     * (`42800d`)으로 내미는 경우가 그렇다. 실측으로는 소프트웨어 디코더 하나만
     * 해도 Baseline·Main·High 를 level 5.2 까지 보고하는데, 광고는 `42e029`
     * 하나였다.
     *
     * createDecoder 는 VideoCodecInfo 의 **이름만** 보고 디코더를 만든다 —
     * 프로파일 파라미터를 보지 않는다. 그래서 항목을 추가해도 디코더 생성 경로는
     * 그대로이고, 실제 프로파일은 비트스트림의 SPS 가 정한다.
     *
     * 기존 항목은 순서째 그대로 두고 뒤에만 덧붙인다. 선호 순위가 바뀌지 않아야
     * 지금까지 붙던 상대는 계속 같은 값으로 붙는다.
     *
     * 한계: video/avc 디코더 **전체의 합집합**을 쓴다. createDecoder 가 실제로
     * 고르는 디코더 하나를 여기서 알 방법이 없다 — findCodecForType 이 비공개다.
     * H.264 디코더들은 대개 같은 프로파일 집합을 보고하므로 실무적으로 차이가
     * 없다.
     */
    private VideoCodecInfo[] withReportedH264Profiles(VideoCodecInfo[] codecs) {
        VideoCodecInfo template = null;
        Set<String> present = new HashSet<>();
        for (VideoCodecInfo codec : codecs) {
            if (!H264_NAME.equalsIgnoreCase(codec.name)) {
                continue;
            }
            if (template == null) {
                template = codec;
            }
            String profileLevelId = codec.params.get(PROFILE_LEVEL_ID);
            if (profileLevelId != null && profileLevelId.length() >= 4) {
                present.add(profileLevelId.substring(0, 4).toLowerCase(Locale.US));
            }
        }

        // H.264 를 대는 디코더가 없으면 추가할 근거도 없다. 못 푸는 것을 광고하면
        // 협상은 붙고 화면만 검어진다 — 거절되는 지금보다 가리기 어렵다.
        if (template == null) {
            return codecs;
        }

        List<VideoCodecInfo> result = new ArrayList<>(Arrays.asList(codecs));
        for (Map.Entry<String, Integer> reported : reportedH264Profiles().entrySet()) {
            if (!present.add(reported.getKey())) {
                continue;   // 이미 광고하는 프로파일이다
            }
            // packetization-mode 와 level-asymmetry-allowed 는 기존 항목 그대로
            // 물려받는다. 프로파일만 다른 항목이어야 한다.
            Map<String, String> params = new HashMap<>(template.params);
            int levelIdc = Math.min(reported.getValue(), MAX_PARSABLE_LEVEL_IDC);
            params.put(PROFILE_LEVEL_ID,
                    reported.getKey() + String.format(Locale.US, "%02x", levelIdc));
            result.add(new VideoCodecInfo(template.name, params, template.scalabilityModes));
        }
        return result.toArray(new VideoCodecInfo[0]);
    }

    /**
     * video/avc 디코더들이 보고한 프로파일 → 그 프로파일의 최대 level_idc.
     *
     * 삽입 순서를 지키는 맵이라 {@link #AVC_PROFILES} 의 등록 순서와 무관하게
     * 디코더가 보고한 순서로 나간다.
     */
    private static Map<String, Integer> reportedH264Profiles() {
        Map<String, Integer> profiles = new LinkedHashMap<>();
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            for (MediaCodecInfo info : infos) {
                if (info.isEncoder() || !supportsH264(info)) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities capabilities =
                        info.getCapabilitiesForType(H264_MIME);
                for (MediaCodecInfo.CodecProfileLevel pair : capabilities.profileLevels) {
                    String profile = AVC_PROFILES.get(pair.profile);
                    Integer levelIdc = AVC_LEVELS.get(pair.level);
                    if (profile == null || levelIdc == null) {
                        continue;   // 우리가 협상에 올리지 않는 프로파일·레벨이다
                    }
                    Integer current = profiles.get(profile);
                    if (current == null || current < levelIdc) {
                        profiles.put(profile, levelIdc);
                    }
                }
            }
        } catch (RuntimeException e) {
            // 코덱 조회는 통화를 세우는 길목에서 돈다. 여기서 던지면 통화가
            // 시작조차 못 하므로, 실패하면 원래 목록으로 간다.
            Logging.w(TAG, "H.264 프로파일을 MediaCodec 에 묻지 못했다", e);
        }
        return profiles;
    }

    private static boolean supportsH264(MediaCodecInfo info) {
        for (String type : info.getSupportedTypes()) {
            if (H264_MIME.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    private VideoCodecInfo[] withHigherH264Level(VideoCodecInfo[] codecs) {
        VideoCodecInfo[] adjustedCodecs = new VideoCodecInfo[codecs.length];
        for (int i = 0; i < codecs.length; i++) {
            VideoCodecInfo codec = codecs[i];
            if (!"H264".equalsIgnoreCase(codec.name)) {
                adjustedCodecs[i] = codec;
                continue;
            }

            String profileLevelId = codec.params.get("profile-level-id");
            if (profileLevelId == null || profileLevelId.length() != 6) {
                adjustedCodecs[i] = codec;
                continue;
            }

            String profile = profileLevelId.substring(0, 4);
            String level = profileLevelId.substring(4);
            if (Integer.parseInt(level, 16) >= 0x29) {
                adjustedCodecs[i] = codec;
                continue;
            }

            Map<String, String> params = new HashMap<>(codec.params);
            params.put("profile-level-id", profile + "29");
            adjustedCodecs[i] = new VideoCodecInfo(codec.name, params, codec.scalabilityModes);
        }

        return adjustedCodecs;
    }
}

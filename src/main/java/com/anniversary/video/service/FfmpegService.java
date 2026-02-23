package com.anniversary.video.service;

import com.anniversary.video.domain.Order;
import com.anniversary.video.domain.OrderPhoto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FfmpegService {

    private final S3Service s3Service;
    private static final String WORK_BASE = "/tmp/anniversary/";

    /**
     * 메인 파이프라인: 인트로 생성 → S3 클립 다운로드 → 합치기 → 음악 → S3 업로드
     */
    public String mergeClipsWithMusic(Long orderId, List<OrderPhoto> photos, Order order) throws Exception {
        Path workDir = Paths.get(WORK_BASE + orderId);
        Files.createDirectories(workDir);
        log.info("FFmpeg 작업 시작 - orderId: {}, 클립 수: {}", orderId, photos.size());

        try {
            // 1. 인트로 클립 생성
            String introTitle = (order.getIntroTitle() != null && !order.getIntroTitle().isBlank())
                    ? order.getIntroTitle() : "소중한 순간들";
            Path introClip = createIntroClip(workDir, introTitle);

            // 2. S3에서 각 클립 다운로드
            List<Path> localClips = new ArrayList<>();
            localClips.add(introClip);
            for (OrderPhoto photo : photos) {
                if (photo.getClipS3Key() == null) continue;
                Path localClip = workDir.resolve("clip_" + photo.getSortOrder() + ".mp4");
                s3Service.downloadToLocal(photo.getClipS3Key(), localClip);
                localClips.add(localClip);
            }

            if (localClips.size() <= 1) {
                throw new RuntimeException("다운로드된 클립이 없습니다 - orderId: " + orderId);
            }

            // 3. concat 리스트 파일 생성
            Path concatFile = workDir.resolve("concat.txt");
            StringBuilder sb = new StringBuilder();
            for (Path clip : localClips) {
                sb.append("file '").append(clip.toAbsolutePath()).append("'\n");
            }
            Files.writeString(concatFile, sb.toString());
            log.info("concat.txt 생성 완료, 클립 {}개 (인트로 포함)", localClips.size());

            // 4. 클립 합치기
            Path mergedVideo = workDir.resolve("merged.mp4");
            runFfmpeg(
                    "-f", "concat", "-safe", "0",
                    "-i", concatFile.toString(),
                    "-c", "copy",
                    mergedVideo.toString()
            );

            // 5. BGM 준비
            String bgmTrack = (order.getBgmTrack() != null) ? order.getBgmTrack() : "bgm_01";
            Path bgmPath = prepareBgm(workDir, getTotalDuration(mergedVideo), bgmTrack);

            // 6. BGM 삽입 + 16:9 1080p 최종 인코딩
            Path finalVideo = workDir.resolve("final.mp4");
            runFfmpeg(
                    "-i", mergedVideo.toString(),
                    "-i", bgmPath.toString(),
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-vf", "scale=1920:1080:force_original_aspect_ratio=decrease," +
                           "pad=1920:1080:(ow-iw)/2:(oh-ih)/2:black," +
                           "fps=30",
                    "-c:v", "libx264", "-crf", "18", "-preset", "medium",
                    "-c:a", "aac", "-b:a", "192k",
                    "-shortest",
                    "-movflags", "+faststart",
                    finalVideo.toString()
            );

            // 7. S3 업로드
            String s3Key = "results/" + orderId + "/final.mp4";
            s3Service.uploadFile(finalVideo, s3Key, "video/mp4");

            log.info("✅ FFmpeg 완료 - orderId: {}, s3Key: {}", orderId, s3Key);
            return s3Key;

        } finally {
            cleanWorkDir(workDir);
        }
    }

    // ── BGM 메타 정보 (프론트 선택 UI용) ──────────────────────────────────
    public static final List<Map<String, String>> BGM_LIST = List.of(
        Map.of("id", "bgm_01", "name", "잔잔한 피아노",    "desc", "조용하고 감성적인 피아노 멜로디"),
        Map.of("id", "bgm_02", "name", "따뜻한 스트링스", "desc", "현악기가 만드는 감동적인 선율"),
        Map.of("id", "bgm_03", "name", "어쿠스틱 기타",   "desc", "자연스럽고 편안한 어쿠스틱 기타")
    );

    // ── 인트로 클립 생성 ────────────────────────────────────────────────────
    private Path createIntroClip(Path workDir, String introTitle) throws Exception {
        Path fontPath = prepareFont(workDir);
        Path introClip = workDir.resolve("intro.mp4");

        if (fontPath == null) {
            runFfmpeg(
                "-f", "lavfi",
                "-i", "color=c=black:size=1920x1080:rate=30:duration=4",
                "-c:v", "libx264", "-crf", "18", "-preset", "medium",
                "-an",
                introClip.toString()
            );
            return introClip;
        }

        String safeTitle = introTitle.replace("'", "\\'").replace(":", "\\:");
        String subText   = "평생 잊지 못할 기억들";
        String fadeAlpha = "if(lt(t\\,0.5)\\,0\\,if(lt(t\\,1.5)\\,(t-0.5)\\,if(lt(t\\,3.2)\\,1\\,if(lt(t\\,4)\\,(4-t)/0.8\\,0))))";
        String font      = fontPath.toAbsolutePath().toString();

        String vf = String.format(
            "drawtext=fontfile='%s':text='%s':fontcolor=white:fontsize=56" +
            ":x=(w-text_w)/2:y=(h-text_h)/2-70:alpha='%s'" +
            ",drawbox=x=(w-200)/2:y=(h)/2+10:w=200:h=1:color=0xC9A96E@1:t=fill" +
            ",drawtext=fontfile='%s':text='%s':fontcolor=0xC9A96E:fontsize=26" +
            ":x=(w-text_w)/2:y=(h-text_h)/2+50:alpha='%s'",
            font, safeTitle, fadeAlpha,
            font, subText,   fadeAlpha
        );

        runFfmpeg(
            "-f", "lavfi",
            "-i", "color=c=black:size=1920x1080:rate=30:duration=4",
            "-vf", vf,
            "-c:v", "libx264", "-crf", "18", "-preset", "medium",
            "-an",
            introClip.toString()
        );

        log.info("인트로 클립 생성 완료: {}", introClip);
        return introClip;
    }

    // ── 한국어 폰트 준비 ────────────────────────────────────────────────────
    private Path prepareFont(Path workDir) throws Exception {
        Path fontDest = workDir.resolve("font.otf");

        try {
            ClassPathResource fontRes = new ClassPathResource("fonts/NotoSansKR-Regular.ttf");
            if (fontRes.exists()) {
                Files.copy(fontRes.getInputStream(), fontDest, StandardCopyOption.REPLACE_EXISTING);
                log.info("내장 폰트 사용: fonts/NotoSansKR-Regular.ttf");
                return fontDest;
            }
        } catch (Exception ignored) {}

        String[] systemFonts = {
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/System/Library/Fonts/Supplemental/AppleGothic.ttf",
            "/Library/Fonts/Arial Unicode.ttf"
        };
        for (String path : systemFonts) {
            if (Files.exists(Paths.get(path))) {
                log.info("시스템 폰트 사용: {}", path);
                return Paths.get(path);
            }
        }

        log.warn("한국어 폰트를 찾을 수 없습니다. 인트로 텍스트 없이 생성합니다.");
        return null;
    }

    // ── BGM 파일 준비 ────────────────────────────────────────────────────────
    private Path prepareBgm(Path workDir, double durationSec, String bgmTrack) throws Exception {
        Path bgmPath = workDir.resolve("bgm.mp3");
        String[] candidates = { bgmTrack + ".mp3", "bgm_01.mp3" };
        for (String filename : candidates) {
            try {
                ClassPathResource res = new ClassPathResource("static/bgm/" + filename);
                if (res.exists()) {
                    Files.copy(res.getInputStream(), bgmPath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("BGM 로드: {}", filename);
                    return bgmPath;
                }
            } catch (Exception ignored) {}
        }
        log.warn("BGM 파일 없음 ({}), 무음 오디오 생성", bgmTrack);
        runFfmpeg(
                "-f", "lavfi",
                "-i", "anullsrc=r=44100:cl=stereo",
                "-t", String.valueOf((int) durationSec + 5),
                "-q:a", "9",
                bgmPath.toString()
        );
        return bgmPath;
    }

    // ── 영상 길이 조회 ────────────────────────────────────────────────────────
    private double getTotalDuration(Path videoFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "csv=p=0",
                    videoFile.toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Double.parseDouble(output);
        } catch (Exception e) {
            return 90.0;
        }
    }

    private void runFfmpeg(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        for (String arg : args) cmd.add(arg);

        log.info("FFmpeg: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder ffLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ffLog.append(line).append("\n");
                if (line.contains("time=") || line.contains("Error")) {
                    log.debug("FFmpeg: {}", line);
                }
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.error("FFmpeg 실패:\n{}", ffLog.substring(Math.max(0, ffLog.length() - 2000)));
            throw new RuntimeException("FFmpeg 실패 - exitCode: " + exitCode);
        }
    }

    private void cleanWorkDir(Path workDir) {
        try {
            Files.walk(workDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            log.info("임시 파일 정리 완료: {}", workDir);
        } catch (Exception e) {
            log.warn("임시 파일 정리 실패: {}", e.getMessage());
        }
    }
}

package com.anniversary.video.service;

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

@Service
@RequiredArgsConstructor
@Slf4j
public class FfmpegService {

    private final S3Service s3Service;
    private static final String WORK_BASE = "/tmp/anniversary/";

    /**
     * 메인 파이프라인: S3 클립 다운로드 → 합치기 → 음악 → S3 업로드
     */
    public String mergeClipsWithMusic(Long orderId, List<OrderPhoto> photos) throws Exception {
        Path workDir = Paths.get(WORK_BASE + orderId);
        Files.createDirectories(workDir);
        log.info("FFmpeg 작업 시작 - orderId: {}, 클립 수: {}", orderId, photos.size());

        try {
            // 1. S3에서 각 클립 다운로드
            List<Path> localClips = new ArrayList<>();
            for (OrderPhoto photo : photos) {
                if (photo.getClipS3Key() == null) continue;
                Path localClip = workDir.resolve("clip_" + photo.getSortOrder() + ".mp4");
                s3Service.downloadToLocal(photo.getClipS3Key(), localClip);
                localClips.add(localClip);
            }

            if (localClips.isEmpty()) {
                throw new RuntimeException("다운로드된 클립이 없습니다 - orderId: " + orderId);
            }

            // 2. concat 리스트 파일 생성
            Path concatFile = workDir.resolve("concat.txt");
            StringBuilder sb = new StringBuilder();
            for (Path clip : localClips) {
                sb.append("file '").append(clip.toAbsolutePath()).append("'\n");
            }
            Files.writeString(concatFile, sb.toString());
            log.info("concat.txt 생성 완료, 클립 {}개", localClips.size());

            // 3. 클립 합치기 (재인코딩 없이 빠른 concat)
            Path mergedVideo = workDir.resolve("merged.mp4");
            runFfmpeg(
                    "-f", "concat", "-safe", "0",
                    "-i", concatFile.toString(),
                    "-c", "copy",
                    mergedVideo.toString()
            );

            // 4. BGM 준비
            Path bgmPath = prepareBgm(workDir, getTotalDuration(mergedVideo));

            // 5. BGM 삽입 + 16:9 1080p 최종 인코딩
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

            // 6. S3 업로드
            String s3Key = "results/" + orderId + "/final.mp4";
            s3Service.uploadFile(finalVideo, s3Key, "video/mp4");

            log.info("✅ FFmpeg 완료 - orderId: {}, s3Key: {}", orderId, s3Key);
            return s3Key;

        } finally {
            cleanWorkDir(workDir);
        }
    }

    /**
     * BGM 파일 준비 (classpath 리소스 or 무음 생성)
     */
    private Path prepareBgm(Path workDir, double durationSec) throws Exception {
        Path bgmPath = workDir.resolve("bgm.mp3");
        try {
            ClassPathResource res = new ClassPathResource("bgm/emotional_bgm.mp3");
            if (res.exists()) {
                Files.copy(res.getInputStream(), bgmPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("BGM 파일 로드 완료");
                return bgmPath;
            }
        } catch (Exception e) {
            log.warn("BGM 파일 없음, 무음 오디오 생성: {}", e.getMessage());
        }
        // 무음 오디오 생성
        runFfmpeg(
                "-f", "lavfi",
                "-i", "anullsrc=r=44100:cl=stereo",
                "-t", String.valueOf((int) durationSec + 5),
                "-q:a", "9",
                bgmPath.toString()
        );
        return bgmPath;
    }

    /**
     * 영상 길이 조회 (ffprobe)
     */
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
            return 90.0; // 기본값 90초
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

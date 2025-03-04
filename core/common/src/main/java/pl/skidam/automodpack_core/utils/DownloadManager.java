/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_common.utils.CustomFileUtils;
import pl.skidam.automodpack_common.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_core.screen.ScreenManager;

import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack_common.GlobalVariables.*;

public class DownloadManager {
    private static final int MAX_DOWNLOADS_IN_PROGRESS = 5;
    private static final int BUFFER_SIZE = 16 * 1024;
    private final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(MAX_DOWNLOADS_IN_PROGRESS, new CustomThreadFactoryBuilder().setNameFormat("AutoModpackDownload-%d").build());
    private final Map<String, QueuedDownload> queuedDownloads = new ConcurrentHashMap<>();
    public final Map<String, DownloadData> downloadsInProgress = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private long bytesDownloaded = 0;
    private long bytesToDownload = 0;
    private int addedToQueue = 0;
    private int downloaded = 0;
    private final Semaphore semaphore = new Semaphore(0);

    public DownloadManager() {
        Optional<String> screen = new ScreenManager().getScreenString();
//        if (screen.isPresent() && screen.get().contains("downloadscreen")) {
//            new ScreenManager().download(this, "");
//        }
    }

    public DownloadManager(long bytesToDownload) {
        this.bytesToDownload = bytesToDownload;
//        Optional<String> screen = new ScreenManager().getScreenString();
//        if (screen.isPresent() && screen.get().contains("downloadscreen")) {
//            new ScreenManager().download(this, "");
//        }
    }


    public void download(Path file, String sha1, String url, Runnable successCallback, Runnable failureCallback) {
        if (!queuedDownloads.containsKey(url)) {
            retryCounts.put(url, 1);
            queuedDownloads.put(url, new QueuedDownload(file, sha1, successCallback, failureCallback));
            addedToQueue++;
            downloadNext();
        }
    }

    private void downloadNext() {
        if (downloadsInProgress.size() < MAX_DOWNLOADS_IN_PROGRESS && !queuedDownloads.isEmpty()) {
            String url = queuedDownloads.keySet().iterator().next();
            QueuedDownload queuedDownload = queuedDownloads.remove(url);

            if (queuedDownload == null) {
                return;
            }

            Runnable downloadTask = () -> {
                try {
                    downloadFile(url, queuedDownload);

                    String hash = CustomFileUtils.getHash(queuedDownload.file, "SHA-1");

                    if (!Objects.equals(hash, queuedDownload.sha1)) {

                        bytesDownloaded -= queuedDownload.file.toFile().length();

                        // Runs only when failure
                        if (retryCounts.get(url) <= 3) {
                            retryCounts.put(url, retryCounts.get(url) + 1); // Increment the retry count here
                            LOGGER.warn("Download failed, retrying: " + url);
                            queuedDownloads.put(url, queuedDownload);
                        } else {
                            LOGGER.error("Download failed after {} retries: {}", retryCounts.get(url), url);
                            queuedDownload.failureCallback.run();
                        }

                        CustomFileUtils.forceDelete(queuedDownload.file);

                    // Runs only when success
                    } else if (Files.exists(queuedDownload.file)) {

                        downloaded++;
                        queuedDownload.successCallback.run();
                    }
                } catch (SocketTimeoutException | InterruptedException ignored) {

                } catch (Exception e) {
                    queuedDownload.failureCallback.run();
                    e.printStackTrace();
                } finally {
                    // Runs every time
                    downloadsInProgress.remove(url);
                    retryCounts.remove(url);
                    semaphore.release();

                    downloadNext();
                }
            };

            CompletableFuture<Void> future = CompletableFuture.runAsync(downloadTask, DOWNLOAD_EXECUTOR);

            synchronized (downloadsInProgress) {
                downloadsInProgress.put(url, new DownloadData(future, queuedDownload.file));
            }
        }
    }

    private void downloadFile(String urlString, QueuedDownload queuedDownload) throws IOException, NoSuchAlgorithmException, InterruptedException {

        Path outFile = queuedDownload.file;

        if (Files.exists(outFile)) {
            if (Objects.equals(CustomFileUtils.getHash(outFile, "SHA-1"), queuedDownload.sha1)) {
                return;
            } else {
                CustomFileUtils.forceDelete(outFile);
            }
        }

        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }

        Files.createFile(outFile);

        URL url = new URL(urlString);
        URLConnection connection = url.openConnection();
        connection.setRequestProperty("Content-Type", "application/octet-stream; charset=UTF-8");
        connection.addRequestProperty("Accept-Encoding", "gzip");
        connection.addRequestProperty("Minecraft-Username", "");
        connection.addRequestProperty("User-Agent", "github/skidamek/automodpack/" + AM_VERSION);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(5000);

        try (OutputStream outputStream = new FileOutputStream(outFile.toFile());
             InputStream rawInputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
             InputStream inputStream = ("gzip".equals(connection.getHeaderField("Content-Encoding"))) ?
                     new GZIPInputStream(rawInputStream) : rawInputStream) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                bytesDownloaded += bytesRead;
                outputStream.write(buffer, 0, bytesRead);

                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Download got cancelled");
                }
            }
        }
    }

    public void joinAll() throws InterruptedException {
        semaphore.acquire(addedToQueue);
        semaphore.release(addedToQueue);
    }

    public long getTotalDownloadSpeed() {
        long totalBytesDownloaded = downloadsInProgress.values().stream()
                .mapToLong(data -> data.file.toFile().length())
                .sum();

        long totalDownloadTimeInSeconds = downloadsInProgress.values().stream()
                .mapToLong(data -> Duration.between(data.startTime, Instant.now()).getSeconds())
                .sum();

        return totalDownloadTimeInSeconds == 0 ? 0 : totalBytesDownloaded / totalDownloadTimeInSeconds;
    }

    public String getTotalDownloadSpeedInReadableFormat(long totalDownloadSpeed) {
        if (totalDownloadSpeed == 0) {
            return "-1";
        }

        // Use the formatSize() method to format the total download speed into a human-readable format
        return addUnitsPerSecond(totalDownloadSpeed);
    }

    public String getTotalETA(long totalDownloadSpeed) {
        long totalBytesRemaining = bytesToDownload - bytesDownloaded;

        return totalDownloadSpeed == 0 ? "0" : totalBytesRemaining / totalDownloadSpeed + "s";
    }

    public String addUnitsPerSecond(long size) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;

        while (size > 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        return size + units[unitIndex] + "/s";
    }

    public float getTotalPercentageOfFileSizeDownloaded() {
        return (float) bytesDownloaded / bytesToDownload * 100;
    }

    public String getStage() {
        // files downloaded / files downloaded + queued
        return downloaded + "/" + addedToQueue;
    }

    public boolean isClosed() {
        return DOWNLOAD_EXECUTOR.isShutdown();
    }

    public void cancelAllAndShutdown() {
        queuedDownloads.clear();
        downloadsInProgress.forEach((url, downloadData) -> {
            downloadData.future.cancel(true);
            CustomFileUtils.forceDelete(downloadData.file);
        });
        downloadsInProgress.clear();
        retryCounts.clear();
        downloaded = 0;
        addedToQueue = 0;

        DOWNLOAD_EXECUTOR.shutdownNow();
        try {
            if (!DOWNLOAD_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                DOWNLOAD_EXECUTOR.shutdownNow();
                if (!DOWNLOAD_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOGGER.error("DOWNLOAD EXECUTOR did not terminate");
                }
            }
        } catch (InterruptedException e) {
            DOWNLOAD_EXECUTOR.shutdownNow();
        }
    }

    public static class QueuedDownload {
        public Path file;
        public String sha1;
        Runnable successCallback;
        Runnable failureCallback;

        public QueuedDownload(Path file, String sha1, Runnable successCallback, Runnable failureCallback) {
            this.file = file;
            this.sha1 = sha1;
            this.successCallback = successCallback;
            this.failureCallback = failureCallback;
        }
    }

    public static class DownloadData {
        CompletableFuture<Void> future;
        Path file;
        final Instant startTime = Instant.now();

        DownloadData(CompletableFuture<Void> future, Path file) {
            this.future = future;
            this.file = file;
        }

        public String getFileName() {
            return file.getFileName().toString();
        }
    }
}

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

package pl.skidam.automodpack_core.client;

import pl.skidam.automodpack_common.config.Jsons;
import pl.skidam.automodpack_common.config.ConfigTools;
import pl.skidam.automodpack_common.utils.CustomFileUtils;
import pl.skidam.automodpack_common.utils.MmcPackMagic;
import pl.skidam.automodpack_common.utils.Url;
import pl.skidam.automodpack_core.ReLauncher;
import pl.skidam.automodpack_core.loader.LoaderManager;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.*;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

import static pl.skidam.automodpack_common.GlobalVariables.*;
import static pl.skidam.automodpack_common.config.ConfigTools.GSON;
import static pl.skidam.automodpack_common.utils.CustomFileUtils.mapAllFiles;

public class ModpackUpdater {
    public Changelogs changelogs = new Changelogs();
    public static DownloadManager downloadManager;
    public static FetchManager fetchManager;
    public static long totalBytesToDownload = 0;
    public static boolean fullDownload = false;
    private static Jsons.ModpackContentFields serverModpackContent;
    private static String unModifiedSMC;
    public Map<String, String> failedDownloads = new HashMap<>(); // <file, url>
    public static String getModpackName() {
        return serverModpackContent.modpackName;
    }

    public void startModpackUpdate(Jsons.ModpackContentFields serverModpackContent, String link, Path modpackDir) {
        if (link == null || link.isEmpty() || modpackDir.toString().isEmpty()) return;

        try {
            Path modpackContentFile = Paths.get(modpackDir + File.separator + "modpack-content.json");
            Path workingDirectory = Paths.get("./");

            if (serverModpackContent == null)  { // the server is down, or you don't have access to internet, but we still want to load selected modpack

                if (!Files.exists(modpackContentFile)) {
                    return;
                }

                Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);
                if (modpackContent == null) {
                    return;
                }

                LOGGER.warn("Server is down, or you don't have access to internet, but we still want to load selected modpack");

                CheckAndLoadModpack(modpackDir, modpackContentFile, workingDirectory);
                return;
            }

            serverModpackContent.link = link;
            ModpackUpdater.serverModpackContent = serverModpackContent;
            ModpackUpdater.unModifiedSMC = GSON.toJson(serverModpackContent);

            if (!Files.exists(modpackDir)) {
                Files.createDirectories(modpackDir);
            }

            if (Files.exists(modpackContentFile)) {

                // Rename modpack folder to modpack name if exists
                List<Path> modpackFiles = ModpackUtils.renameModpackDir(modpackContentFile, serverModpackContent, modpackDir);
                if (modpackFiles != null) {
                    modpackDir = modpackFiles.get(0);
                    modpackContentFile = modpackFiles.get(1);
                }

                if (Boolean.FALSE.equals(ModpackUtils.isUpdate(serverModpackContent, modpackDir))) {
                    // check if modpack is loaded now loaded

                    LOGGER.info("Modpack is up to date");

                    CheckAndLoadModpack(modpackDir, modpackContentFile, workingDirectory);
                    return;
                }
            } else if (!preload && new ScreenManager().getScreen().isPresent()) {
                fullDownload = true;
                new ScreenManager().danger(new ScreenManager().getScreen().get(), link, modpackDir, modpackContentFile);
                return;
            }

            LOGGER.warn("Modpack update found");

            new ScreenManager().download(downloadManager, ModpackUpdater.getModpackName());

            ModpackUpdaterMain(link, modpackDir, modpackContentFile);

        } catch (Exception e) {
            LOGGER.error("Error while initializing modpack updater");
            e.printStackTrace();
        }
    }

    public void CheckAndLoadModpack(Path modpackDir, Path modpackContentFile, Path workingDirectory) throws Exception {

        List<Path> filesBefore = mapAllFiles(workingDirectory, new ArrayList<>());

        finishModpackUpdate(modpackDir, modpackContentFile);

        List<Path> filesAfter = mapAllFiles(workingDirectory, new ArrayList<>());

        List<Path> addedFiles = new ArrayList<>();
        List<Path> deletedFiles = new ArrayList<>();

        for (Path file : filesAfter) {
            if (!filesBefore.contains(file)) {
                addedFiles.add(file);
            }
        }

        for (Path file : filesBefore) {
            if (!filesAfter.contains(file)) {
                deletedFiles.add(file);
            }
        }

        if (filesAfter.equals(filesBefore) || (addedFiles.isEmpty() && deletedFiles.isEmpty())) {
            LOGGER.info("Modpack is already loaded");
            return;
        } else {
            LOGGER.info("Modpack is not loaded");
        }

        LOGGER.info("Added files: {}", addedFiles);
        LOGGER.info("Deleted files: {}", deletedFiles);

        UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;

        new ReLauncher.Restart(modpackDir, updateType, changelogs);
    }

    public void ModpackUpdaterMain(String link, Path modpackDir, Path modpackContentFile) {

        long start = System.currentTimeMillis();

        try {
            // Rename modpack
            List<Path> modpackFiles = ModpackUtils.renameModpackDir(modpackContentFile, serverModpackContent, modpackDir);
            if (modpackFiles != null) {
                modpackDir = modpackFiles.get(0);
                modpackContentFile = modpackFiles.get(1);
            }

            if (quest) {
                String modsPathString = modsPath.toString().substring(1) + "/";
                LOGGER.info("Quest mode is enabled, changing /mods/ path to {}", modsPathString);
                for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : serverModpackContent.list) {
                    if (modpackContentFile.toString().startsWith("/mods/")) {
                        modpackContentField.file = modpackContentField.file.replaceFirst("/mods/", modsPathString);
                    }
                }
            }

            Iterator<Jsons.ModpackContentFields.ModpackContentItem> iterator = serverModpackContent.list.iterator();

            while (iterator.hasNext()) {
                Jsons.ModpackContentFields.ModpackContentItem modpackContentField = iterator.next();
                String file = modpackContentField.file;
                String serverSHA1 = modpackContentField.sha1;

                Path path = Paths.get(modpackDir + File.separator + file);

                if (Files.exists(path) && modpackContentField.editable) {
                    LOGGER.info("Skipping editable file: {}", file);
                    iterator.remove();
                    continue;
                }

                if (!Files.exists(path)) {
                    path = Paths.get("./" + file);
                }

                if (!Files.exists(path)) {
                    continue;
                }

                if (serverSHA1.equals(CustomFileUtils.getHash(path, "SHA-1"))) {
                    LOGGER.info("Skipping already downloaded file: {}", file);
                    iterator.remove();
                }
            }

            long startFetching = System.currentTimeMillis();

            fetchManager = new FetchManager();

            for (Jsons.ModpackContentFields.ModpackContentItem field : serverModpackContent.list) {

                totalBytesToDownload += Long.parseLong(field.size);

                String fileType = field.type;

                // Check if the file is mod, shaderpack or resourcepack is available to download from modrinth or curseforge
                if (fileType.equals("mod") || fileType.equals("shader") || fileType.equals("resourcepack")) {
                    fetchManager.fetch(field.link, field.sha1, field.murmur, field.size, fileType);
                }
            }

            fetchManager.joinAll();
            fetchManager.cancelAllAndShutdown();

            LOGGER.info("Finished fetching urls in {}ms", System.currentTimeMillis() - startFetching);

            int wholeQueue = serverModpackContent.list.size();

            LOGGER.info("In queue left {} files to download ({}kb)", wholeQueue, totalBytesToDownload / 1024);

            new ScreenManager().download(downloadManager, ModpackUpdater.getModpackName());

            if (wholeQueue > 0) {

                downloadManager = new DownloadManager(totalBytesToDownload);

                for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : serverModpackContent.list) {

                    String fileName = modpackContentField.file;
                    String serverSHA1 = modpackContentField.sha1;

                    Path downloadFile = Paths.get(modpackDir + File.separator + fileName);
                    String url;

                    String fileLink = modpackContentField.link;
                    if (fetchManager != null && fetchManager.fetchedData.containsKey(modpackContentField.sha1)) {
                        url = new URL(fetchManager.fetchedData.get(modpackContentField.sha1).getPlatformUrl()).toString();
                    } else if (modpackContentField.link.startsWith("/")) { // AutoModpack host
                        url = new URL(link + Url.encode(fileLink)).toString(); // We need to change things like [ ] to %5B %5D etc.
                    } else { // Other host
                        url = new URL(fileLink).toString(); // This link just must work, so we don't need to encode it
                    }

                    Runnable failureCallback = () -> {
                        LOGGER.error("Failed to download {} from {}", fileName, url);
                        failedDownloads.put(downloadFile.getFileName().toString(), url);
                    };

                    Runnable successCallback = () -> {
                        LOGGER.info("Successfully downloaded {} from {}", fileName, url);

                        String mainPageUrl = null;
                        if (fetchManager != null && fetchManager.fetchedData.get(modpackContentField.sha1) != null) {
                            mainPageUrl = fetchManager.fetchedData.get(modpackContentField.sha1).getMainPageUrl();
                        }

                        changelogs.changesAddedList.put(downloadFile.getFileName().toString(), mainPageUrl);
                    };

                    LOGGER.info("Downloading {} from {}", fileName, url);

                    downloadManager.download(downloadFile, serverSHA1, url, successCallback, failureCallback);
                }

                downloadManager.joinAll();
                downloadManager.cancelAllAndShutdown();

                LOGGER.info("Finished downloading files in {}ms", System.currentTimeMillis() - startFetching);
            }

            // Downloads completed
            Files.write(modpackContentFile, unModifiedSMC.getBytes());

            CustomFileUtils.deleteEmptyFiles(Paths.get("./"), serverModpackContent.list);

            finishModpackUpdate(modpackDir, modpackContentFile);

            if (!failedDownloads.isEmpty()) {
                StringBuilder failedFiles = new StringBuilder();
                for (Map.Entry<String, String> entry : failedDownloads.entrySet()) {
                    LOGGER.error("Failed to download: " + entry.getKey() + " from " + entry.getValue());
                    failedFiles.append(entry.getKey());
                }

                new ScreenManager().error("automodpack.error.files", "Failed to download: " + failedFiles, "automodpack.error.logs");;

                LOGGER.warn("Update *completed* with ERRORS! Took: {}ms", System.currentTimeMillis() - start);

                if (preload) {
                    new ReLauncher.Restart("Failed to complete update without errors!");
                }

                return;
            }

            String modpackName = modpackDir.getFileName().toString();
            ModpackUtils.addModpackToList(modpackName);

            LOGGER.info("Update completed! Took: {}ms", System.currentTimeMillis() - start);

            UpdateType updateType = fullDownload ? UpdateType.FULL : UpdateType.UPDATE;

            new ReLauncher.Restart(modpackDir, updateType, changelogs);

            LOGGER.info("Modpack is up-to-date! Took: {}ms", System.currentTimeMillis() - start);

        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("Modpack host of " + link + " is not responding", e);
        } catch (Exception e) {
            new ScreenManager().error("automodpack.error.critical", "\"" + e.getMessage() + "\"", "automodpack.error.logs");
            e.printStackTrace();
        }
    }

    private void finishModpackUpdate(Path modpackDir, Path modpackContentFile) throws Exception {
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        if (modpackContent == null) {
            LOGGER.error("Modpack content is null");
            return;
        }

        if (serverModpackContent != null) {

            // Change loader and minecraft version in launchers like prism, multimc.

            if (serverModpackContent.loader != null && serverModpackContent.loaderVersion != null) {
                if (serverModpackContent.loader.equals(LOADER)) { // Server may use different loader than client
                    MmcPackMagic.changeVersion(MmcPackMagic.modLoaderUIDs, serverModpackContent.loaderVersion); // Update loader version
                }
            }

            if (serverModpackContent.mcVersion != null) {
                MmcPackMagic.changeVersion(MmcPackMagic.mcVerUIDs, serverModpackContent.mcVersion); // Update minecraft version
            }
        }

        checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");

        // make a list of editable files to ignore them while copying
        List<String> editableFiles = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : modpackContent.list) {

            String fileName = Paths.get(modpackContentField.file).getFileName().toString();

            // Don't add to editable if just downloaded
            if (changelogs.changesAddedList.containsKey(fileName)) {
                continue;
            }

            if (modpackContentField.editable) {
                editableFiles.add(modpackContentField.file);
            }
        }

        // copy files to running directory
        // map running dir files
        ModpackUtils.copyModpackFilesFromModpackDirToRunDir(modpackDir, modpackContent, editableFiles);

        List<String> modpackFileNames = new ArrayList<>();
        for (Jsons.ModpackContentFields.ModpackContentItem modpackContentField : modpackContent.list) {
            String fileName = Paths.get(modpackContentField.file).getFileName().toString();
            modpackFileNames.add(fileName);
        }

        try (Stream<Path> stream = Files.walk(modpackDir)) {
            stream.filter(file -> !Files.isDirectory(file))
                    .filter(file -> !file.equals(modpackContentFile))
                    .forEach(file -> {

                    String fileName = file.getFileName().toString();

                    if (!modpackFileNames.contains(fileName)) {
                        Path fileInRunningDir = Paths.get("." + file.toString().replace(modpackDir.toString(), ""));
                        try {

                            if (Files.exists(fileInRunningDir) && CustomFileUtils.compareFileHashes(file, fileInRunningDir, "SHA-1")) {
                                LOGGER.info("Deleting {} and {}", file, fileInRunningDir);
                                CustomFileUtils.forceDelete(fileInRunningDir);
                            } else {
                                LOGGER.info("Deleting {}", file);
                            }


                        } catch (IOException | NoSuchAlgorithmException e) {
                            LOGGER.error("An error occurred while trying to compare file hashes", e);
                            e.printStackTrace();
                        }

                        CustomFileUtils.forceDelete(file);
                        changelogs.changesDeletedList.put(fileName, null);
                    }
            });
        }

        // There is a possibility that some files are in running directory, but not in modpack dir
        // Because they were already downloaded before
        // So copy files to modpack dir
        ModpackUtils.copyModpackFilesFromRunDirToModpackDir(modpackDir, modpackContent, editableFiles);

        ModpackUtils.copyModpackFilesFromModpackDirToRunDir(modpackDir, modpackContent, editableFiles);

        checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");
    }


    // removes mods from the main mods folder
    // that are having the same id as the ones in the modpack mods folder but different version/hash
    private static void checkAndRemoveDuplicateMods(String modpackModsFile) {
        Map<String, String> mainMods = getMods("./mods/");
        Map<String, String> modpackMods = getMods(modpackModsFile);

        if (mainMods == null || modpackMods == null) return;
        if (!hasDuplicateValues(mainMods)) return;

        for (Map.Entry<String, String> mainMod : mainMods.entrySet()) {
            String mainModFileName = mainMod.getKey();
            String mainModId = mainMod.getValue();

            if (mainModId == null || mainModFileName == null) {
                continue;
            }

            String modpackModFileName = modpackMods.get(mainModId);

            // Check if the main mod ID exists in the modpack mods and filenames are different
            if (modpackModFileName != null && !mainModFileName.equals(modpackModFileName)) {
                Path mainModFile = Paths.get("./mods/" + mainModFileName);
                LOGGER.info("Deleting {} from main mods folder...", mainModFile.getFileName());
                CustomFileUtils.forceDelete(mainModFile);
            }
        }
    }

    private static Map<String, String> getMods(String modsDir) {
        Map<String, String> defaultMods = new HashMap<>();
        Path defaultModsDir = Paths.get(modsDir);

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(defaultModsDir)) {
            for (Path defaultMod : directoryStream) {
                if (!Files.isRegularFile(defaultMod) || !defaultMod.getFileName().toString().endsWith(".jar")) {
                    continue;
                }
                defaultMods.put(defaultMod.getFileName().toString(), new LoaderManager().getModId(defaultMod, true));
            }
        } catch (IOException e) {
            return null;
        }

        return defaultMods;
    }

    private static boolean hasDuplicateValues(Map<String, String> map) {
        Set<String> values = new HashSet<>();
        for (String value : map.values()) {
            if (values.contains(value)) {
                return true;
            }
            values.add(value);
        }
        return false;
    }
}
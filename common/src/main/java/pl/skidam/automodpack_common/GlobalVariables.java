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

package pl.skidam.automodpack_common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pl.skidam.automodpack_common.config.Jsons;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GlobalVariables {
    public static final Logger LOGGER = LogManager.getLogger("AutoModpack");
    public static final String MOD_ID = "automodpack";
    public static Boolean preload;
    public static String MC_VERSION;
    public static String AM_VERSION;
    public static String LOADER_VERSION;
    public static String LOADER;
    public static final Path automodpackDir = Paths.get("./automodpack/");
    public static Path hostModpackDir = Paths.get(automodpackDir + File.separator + "host-modpack");
    public static Path hostModpackContentFile = Paths.get(hostModpackDir + File.separator + "modpack-content.json");
    public static final Path modpacksDir = Paths.get(automodpackDir + File.separator + "modpacks");
    public static final Path clientConfigFile = Paths.get(automodpackDir + File.separator + "automodpack-client.json");
    public static final Path serverConfigFile = Paths.get(automodpackDir + File.separator + "automodpack-server.json");
    public static final Set<String> keyWordsOfDisconnect = new HashSet<>(List.of("install", "update", "download", "handshake", "incompatible", "outdated", "client", "version"));
    public static Path modsPath;
    public static Path selectedModpackDir;
    public static String selectedModpackLink;
    public static Jsons.ServerConfigFields serverConfig;
    public static Jsons.ClientConfigFields clientConfig;
    public static boolean quest;
    public static boolean serverFullyStarted = false;
}

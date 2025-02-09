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

package pl.skidam.automodpack_common.utils;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Url {

    public static String encode(String decodedUrl) {
        try {
            boolean firstDash = false;
            if (decodedUrl.startsWith("/")) {
                firstDash = true;
                decodedUrl = decodedUrl.substring(1);
            }
            String encodedUrl = URLEncoder.encode(decodedUrl, StandardCharsets.UTF_8);
            if (firstDash) {
                encodedUrl = "/" + encodedUrl;
            }
            return encodedUrl;
        } catch (Exception e) {
            // Encoding error, return the original decoded part
            return decodedUrl;
        }
    }

    public static String decode(String encodedUrl) {
        // There we don't need to check if the first character is a dash
        try {
            return URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Decoding error, return the original encoded part
            return encodedUrl;
        }
    }

    public static String removeHttpPrefix(String inputUrl) {
        String regex = "^(https?://)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(inputUrl);

        if (matcher.find()) {
            return inputUrl.substring(matcher.end());
        } else {
            return inputUrl; // No match, return the original URL
        }
    }
}
package com.solar.launcher.youtube;

import com.solar.launcher.media.AuthorizedDirectDownload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds direct playable-audio URLs in description metadata returned by the official API.
 *
 * <p>This does not fetch, scrape, or follow any page. Every candidate must pass the separate
 * authorized direct-download provider's strict URL and file-format policy.</p>
 */
public final class CreatorDownloadLinkExtractor {
    private static final Pattern HTTP_URL =
            Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_DESCRIPTION_CHARS = 20_000;
    private static final int MAX_STORED_LINK_CHARS = 4096;
    private static final int MAX_LINKS = 6;

    public static final class Link {
        public final String url;
        public final String host;
        public final String displayName;

        Link(String url, String host, String displayName) {
            this.url = url;
            this.host = host;
            this.displayName = displayName;
        }
    }

    private CreatorDownloadLinkExtractor() {}

    public static List<Link> extract(String description) {
        List<Link> out = new ArrayList<Link>();
        if (description == null || description.length() == 0) return out;
        String bounded = description.length() <= MAX_DESCRIPTION_CHARS
                ? description : description.substring(0, MAX_DESCRIPTION_CHARS);
        Matcher matcher = HTTP_URL.matcher(bounded);
        Set<String> seen = new HashSet<String>();
        while (matcher.find() && out.size() < MAX_LINKS) {
            String candidate = trimTrailingProse(matcher.group());
            if (candidate.length() == 0 || seen.contains(candidate)) continue;
            try {
                AuthorizedDirectDownload.Source source =
                        AuthorizedDirectDownload.inspect(candidate);
                seen.add(candidate);
                out.add(new Link(source.url, source.host, source.displayName));
            } catch (Exception ignored) {
                // Web pages, YouTube/media-platform links, and unsupported formats are omitted.
            }
        }
        return out;
    }

    /** Compact bookmark form: validated direct URLs only, bounded for API-17 preferences. */
    static String compactForBookmark(String description) {
        StringBuilder out = new StringBuilder();
        for (Link link : extract(description)) {
            int separator = out.length() > 0 ? 1 : 0;
            if (out.length() + separator + link.url.length() > MAX_STORED_LINK_CHARS) break;
            if (separator > 0) out.append('\n');
            out.append(link.url);
        }
        return out.toString();
    }

    private static String trimTrailingProse(String value) {
        int end = value != null ? value.length() : 0;
        while (end > 0) {
            char ch = value.charAt(end - 1);
            if (ch == '.' || ch == ',' || ch == ';' || ch == '!'
                    || ch == ')' || ch == ']' || ch == '}') {
                end--;
            } else {
                break;
            }
        }
        return value != null ? value.substring(0, end) : "";
    }
}

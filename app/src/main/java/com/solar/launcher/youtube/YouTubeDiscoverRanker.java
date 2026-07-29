package com.solar.launcher.youtube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic local ranking for Solar Discover.
 *
 * This is intentionally understandable and independent of YouTube's Home
 * algorithm. It consumes only metadata already on the Y1 plus read-only
 * account signals explicitly fetched by Solar.
 */
public final class YouTubeDiscoverRanker {

    public static final String PREF_MIN_DURATION_SECONDS =
            "youtube_discover_min_duration_seconds";
    public static final String PREF_MAX_DURATION_SECONDS =
            "youtube_discover_max_duration_seconds";

    public enum Reason {
        MORE_LIKE,
        SUBSCRIBED_CHANNEL,
        LIKED_VIDEO,
        LOCAL_LIBRARY_ARTIST,
        LOCAL_LIBRARY_GENRE,
        RECENT_SEARCH,
        RESEARCH_LIST,
        POPULAR_REGION
    }

    public static final class Feedback {
        public final Set<String> blockedVideoIds;
        public final Map<String, Integer> boostedChannels;
        public final Map<String, Integer> reducedChannels;
        public final Map<String, Integer> boostedTerms;

        public Feedback(Set<String> blockedVideoIds,
                Map<String, Integer> boostedChannels,
                Map<String, Integer> reducedChannels,
                Map<String, Integer> boostedTerms) {
            this.blockedVideoIds = copySet(blockedVideoIds);
            this.boostedChannels = copyMap(boostedChannels);
            this.reducedChannels = copyMap(reducedChannels);
            this.boostedTerms = copyMap(boostedTerms);
        }

        public static Feedback empty() {
            return new Feedback(null, null, null, null);
        }
    }

    public static final class Signals {
        public final List<YouTubeVideo> research;
        public final List<YouTubeVideo> liked;
        public final List<String> subscribedChannels;
        public final List<String> recentSearches;
        public final List<String> localArtists;
        public final List<String> localGenres;
        public final Feedback feedback;
        public final int minDurationSeconds;
        public final int maxDurationSeconds;

        public Signals(List<YouTubeVideo> research, List<YouTubeVideo> liked,
                List<String> subscribedChannels, List<String> recentSearches,
                Feedback feedback, int minDurationSeconds, int maxDurationSeconds) {
            this(research, liked, subscribedChannels, recentSearches, null, null,
                    feedback, minDurationSeconds, maxDurationSeconds);
        }

        public Signals(List<YouTubeVideo> research, List<YouTubeVideo> liked,
                List<String> subscribedChannels, List<String> recentSearches,
                List<String> localArtists, List<String> localGenres,
                Feedback feedback, int minDurationSeconds, int maxDurationSeconds) {
            this.research = copyVideos(research);
            this.liked = copyVideos(liked);
            this.subscribedChannels = copyStrings(subscribedChannels);
            this.recentSearches = copyStrings(recentSearches);
            this.localArtists = copyStrings(localArtists);
            this.localGenres = copyStrings(localGenres);
            this.feedback = feedback != null ? feedback : Feedback.empty();
            this.minDurationSeconds = Math.max(0, minDurationSeconds);
            this.maxDurationSeconds = Math.max(0, maxDurationSeconds);
        }
    }

    public static final class Recommendation {
        public final YouTubeVideo video;
        public final Reason reason;
        public final String detail;
        public final int score;

        Recommendation(YouTubeVideo video, Reason reason, String detail, int score) {
            this.video = video;
            this.reason = reason;
            this.detail = detail != null ? detail : "";
            this.score = score;
        }
    }

    private static final Set<String> STOP_WORDS = new HashSet<String>();

    static {
        Collections.addAll(STOP_WORDS, "the", "and", "for", "with", "from",
                "official", "video", "audio", "music", "feat", "this", "that",
                "your", "you", "live", "mix");
    }

    private YouTubeDiscoverRanker() {}

    public static List<Recommendation> rank(List<YouTubeVideo> regionalPopular,
            Signals signals, int limit) {
        Signals safe = signals != null
                ? signals
                : new Signals(null, null, null, null, Feedback.empty(), 0, 0);
        int boundedLimit = Math.max(1, Math.min(100, limit));
        LinkedHashMap<String, Candidate> candidates =
                new LinkedHashMap<String, Candidate>();
        int order = 0;
        if (regionalPopular != null) {
            for (YouTubeVideo video : regionalPopular) {
                if (!usable(video) || candidates.containsKey(video.id)) continue;
                candidates.put(video.id, new Candidate(video, order++));
            }
        }
        Set<String> likedIds = new HashSet<String>();
        for (YouTubeVideo video : safe.liked) {
            if (!usable(video)) continue;
            likedIds.add(video.id);
            if (!candidates.containsKey(video.id)) {
                candidates.put(video.id, new Candidate(video, order++));
            }
        }

        Set<String> subscribed = normalizedSet(safe.subscribedChannels);
        Set<String> researchChannels = new HashSet<String>();
        Set<String> researchTerms = new HashSet<String>();
        for (YouTubeVideo item : safe.research) {
            String channel = normalizeKey(item != null ? item.author : "");
            if (channel.length() > 0) researchChannels.add(channel);
            researchTerms.addAll(terms(item != null ? item.title : ""));
        }

        List<Scored> scored = new ArrayList<Scored>();
        for (Candidate candidate : candidates.values()) {
            YouTubeVideo video = candidate.video;
            if (safe.feedback.blockedVideoIds.contains(video.id)) continue;
            int duration = parseDurationSeconds(video.duration);
            if (safe.minDurationSeconds > 0 && duration > 0
                    && duration < safe.minDurationSeconds) {
                continue;
            }
            if (safe.maxDurationSeconds > 0 && duration > safe.maxDurationSeconds) {
                continue;
            }

            String channel = normalizeKey(video.author);
            Set<String> titleTerms = terms(video.title);
            int score = Math.max(10, 100 - candidate.order * 2);
            Reason reason = Reason.POPULAR_REGION;
            String detail = "";
            int reasonWeight = 0;

            if (likedIds.contains(video.id)) {
                score += 90;
                reason = Reason.LIKED_VIDEO;
                reasonWeight = 90;
            }
            if (subscribed.contains(channel)) {
                score += 80;
                if (reasonWeight < 80) {
                    reason = Reason.SUBSCRIBED_CHANNEL;
                    detail = video.author;
                    reasonWeight = 80;
                }
            }
            if (researchChannels.contains(channel)) {
                score += 35;
                if (reasonWeight < 35) {
                    reason = Reason.RESEARCH_LIST;
                    detail = video.author;
                    reasonWeight = 35;
                }
            }
            int researchOverlap = overlap(titleTerms, researchTerms);
            if (researchOverlap > 0) {
                score += Math.min(30, researchOverlap * 10);
                if (reasonWeight < 30) {
                    reason = Reason.RESEARCH_LIST;
                    reasonWeight = 30;
                }
            }

            int localArtistWeight = 0;
            String localArtistDetail = "";
            for (String artist : safe.localArtists) {
                String artistKey = normalizeKey(artist);
                if (artistKey.length() == 0) continue;
                int weight = 0;
                if (channel.equals(artistKey)) {
                    weight = 55;
                } else {
                    int artistOverlap = overlap(titleTerms, terms(artist));
                    if (artistOverlap > 0) {
                        weight = Math.min(45, artistOverlap * 18);
                    }
                }
                if (weight > localArtistWeight) {
                    localArtistWeight = weight;
                    localArtistDetail = artist;
                }
            }
            if (localArtistWeight > 0) {
                score += localArtistWeight;
                if (reasonWeight < localArtistWeight) {
                    reason = Reason.LOCAL_LIBRARY_ARTIST;
                    detail = localArtistDetail;
                    reasonWeight = localArtistWeight;
                }
            }

            int localGenreWeight = 0;
            String localGenreDetail = "";
            for (String genre : safe.localGenres) {
                int genreOverlap = overlap(titleTerms, terms(genre));
                int weight = Math.min(24, genreOverlap * 12);
                if (weight > localGenreWeight) {
                    localGenreWeight = weight;
                    localGenreDetail = genre;
                }
            }
            if (localGenreWeight > 0) {
                score += localGenreWeight;
                if (reasonWeight < localGenreWeight) {
                    reason = Reason.LOCAL_LIBRARY_GENRE;
                    detail = localGenreDetail;
                    reasonWeight = localGenreWeight;
                }
            }

            for (String query : safe.recentSearches) {
                int queryOverlap = overlap(titleTerms, terms(query));
                if (queryOverlap > 0) {
                    int weight = Math.min(45, queryOverlap * 15);
                    score += weight;
                    if (reasonWeight < weight) {
                        reason = Reason.RECENT_SEARCH;
                        detail = query;
                        reasonWeight = weight;
                    }
                    break;
                }
            }

            int channelBoost = value(safe.feedback.boostedChannels, channel);
            int channelReduction = value(safe.feedback.reducedChannels, channel);
            int termBoost = 0;
            for (String term : titleTerms) {
                termBoost += value(safe.feedback.boostedTerms, term);
            }
            score += Math.min(120, channelBoost * 35 + termBoost * 12);
            score -= Math.min(240, channelReduction * 70);
            if (channelBoost > 0 || termBoost > 0) {
                reason = Reason.MORE_LIKE;
                detail = channelBoost > 0 ? video.author : "";
            }
            scored.add(new Scored(video, reason, detail, score, candidate.order));
        }

        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored left, Scored right) {
                if (left.score != right.score) return right.score - left.score;
                if (left.order != right.order) return left.order - right.order;
                return left.video.id.compareTo(right.video.id);
            }
        });

        List<Recommendation> out = new ArrayList<Recommendation>();
        Map<String, Integer> channelCounts = new HashMap<String, Integer>();
        Set<String> selectedIds = new HashSet<String>();
        // First pass keeps one channel from dominating a small wheel-first list.
        appendDiverse(scored, out, selectedIds, channelCounts, boundedLimit, 2);
        if (out.size() < boundedLimit) {
            appendDiverse(scored, out, selectedIds, channelCounts, boundedLimit,
                    Integer.MAX_VALUE);
        }
        return out;
    }

    static Set<String> terms(String value) {
        Set<String> out = new HashSet<String>();
        String clean = normalizeKey(value).replaceAll("[^a-z0-9]+", " ");
        String[] pieces = clean.split("\\s+");
        for (String piece : pieces) {
            if (piece.length() < 3 || STOP_WORDS.contains(piece)) continue;
            out.add(piece);
            if (out.size() >= 24) break;
        }
        return out;
    }

    static String normalizeKey(String value) {
        return value != null ? value.trim().toLowerCase(Locale.US) : "";
    }

    static int parseDurationSeconds(String value) {
        if (value == null || value.trim().length() == 0) return 0;
        String[] pieces = value.trim().split(":");
        if (pieces.length < 2 || pieces.length > 3) return 0;
        int total = 0;
        try {
            for (String piece : pieces) {
                int number = Integer.parseInt(piece);
                if (number < 0 || number > 9999) return 0;
                total = total * 60 + number;
            }
            return total;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void appendDiverse(List<Scored> scored, List<Recommendation> out,
            Set<String> selectedIds, Map<String, Integer> channelCounts, int limit,
            int perChannelLimit) {
        for (Scored item : scored) {
            if (out.size() >= limit) return;
            if (selectedIds.contains(item.video.id)) continue;
            String channel = normalizeKey(item.video.author);
            int count = value(channelCounts, channel);
            if (channel.length() > 0 && count >= perChannelLimit) continue;
            out.add(new Recommendation(item.video, item.reason, item.detail, item.score));
            selectedIds.add(item.video.id);
            if (channel.length() > 0) channelCounts.put(channel, count + 1);
        }
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int count = 0;
        for (String value : left) {
            if (right.contains(value)) count++;
        }
        return count;
    }

    private static boolean usable(YouTubeVideo video) {
        return video != null && video.id.length() > 0 && video.title.length() > 0;
    }

    private static Set<String> normalizedSet(List<String> values) {
        Set<String> out = new HashSet<String>();
        for (String value : values) {
            String clean = normalizeKey(value);
            if (clean.length() > 0) out.add(clean);
        }
        return out;
    }

    private static int value(Map<String, Integer> values, String key) {
        if (values == null || key == null) return 0;
        Integer result = values.get(key);
        return result != null ? result : 0;
    }

    private static Set<String> copySet(Set<String> values) {
        return values != null ? new HashSet<String>(values) : new HashSet<String>();
    }

    private static Map<String, Integer> copyMap(Map<String, Integer> values) {
        return values != null
                ? new HashMap<String, Integer>(values)
                : new HashMap<String, Integer>();
    }

    private static List<YouTubeVideo> copyVideos(List<YouTubeVideo> values) {
        return values != null
                ? new ArrayList<YouTubeVideo>(values)
                : new ArrayList<YouTubeVideo>();
    }

    private static List<String> copyStrings(List<String> values) {
        return values != null
                ? new ArrayList<String>(values)
                : new ArrayList<String>();
    }

    private static final class Candidate {
        final YouTubeVideo video;
        final int order;

        Candidate(YouTubeVideo video, int order) {
            this.video = video;
            this.order = order;
        }
    }

    private static final class Scored {
        final YouTubeVideo video;
        final Reason reason;
        final String detail;
        final int score;
        final int order;

        Scored(YouTubeVideo video, Reason reason, String detail, int score, int order) {
            this.video = video;
            this.reason = reason;
            this.detail = detail;
            this.score = score;
            this.order = order;
        }
    }
}

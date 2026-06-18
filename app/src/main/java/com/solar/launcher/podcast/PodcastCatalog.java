package com.solar.launcher.podcast;

/** ponytail: curated search terms + direct RSS feeds for offline-friendly browse on Y1. */
public final class PodcastCatalog {
    public static final class Category {
        public final int labelResId;
        public final String query;

        Category(int labelResId, String query) {
            this.labelResId = labelResId;
            this.query = query;
        }
    }

    public static final class Genre {
        public final int labelResId;
        public final int genreId;
        public final String searchTerm;

        Genre(int labelResId, int genreId, String searchTerm) {
            this.labelResId = labelResId;
            this.genreId = genreId;
            this.searchTerm = searchTerm;
        }
    }

    public static final class Country {
        public final int labelResId;
        public final String code;

        Country(int labelResId, String code) {
            this.labelResId = labelResId;
            this.code = code;
        }
    }

    public static final Category[] CATEGORIES = {
            new Category(com.solar.launcher.R.string.podcast_cat_technology, "technology"),
            new Category(com.solar.launcher.R.string.podcast_cat_news, "news"),
            new Category(com.solar.launcher.R.string.podcast_cat_music, "music"),
            new Category(com.solar.launcher.R.string.podcast_cat_science, "science"),
            new Category(com.solar.launcher.R.string.podcast_cat_history, "history"),
            new Category(com.solar.launcher.R.string.podcast_cat_comedy, "comedy"),
    };

    public static final Genre[] GENRES = {
            new Genre(com.solar.launcher.R.string.podcast_genre_technology, 1317, "technology"),
            new Genre(com.solar.launcher.R.string.podcast_genre_news, 1312, "news"),
            new Genre(com.solar.launcher.R.string.podcast_genre_comedy, 1303, "comedy"),
            new Genre(com.solar.launcher.R.string.podcast_genre_science, 1314, "science"),
            new Genre(com.solar.launcher.R.string.podcast_genre_history, 1308, "history"),
            new Genre(com.solar.launcher.R.string.podcast_genre_true_crime, 1318, "true crime"),
            new Genre(com.solar.launcher.R.string.podcast_genre_sports, 1316, "sports"),
            new Genre(com.solar.launcher.R.string.podcast_genre_society, 1315, "society"),
            new Genre(com.solar.launcher.R.string.podcast_genre_business, 1302, "business"),
            new Genre(com.solar.launcher.R.string.podcast_genre_education, 1304, "education"),
    };

    public static final Country[] COUNTRIES = {
            new Country(com.solar.launcher.R.string.podcast_country_us, "US"),
            new Country(com.solar.launcher.R.string.podcast_country_gb, "GB"),
            new Country(com.solar.launcher.R.string.podcast_country_kr, "KR"),
            new Country(com.solar.launcher.R.string.podcast_country_jp, "JP"),
            new Country(com.solar.launcher.R.string.podcast_country_de, "DE"),
            new Country(com.solar.launcher.R.string.podcast_country_fr, "FR"),
            new Country(com.solar.launcher.R.string.podcast_country_ca, "CA"),
            new Country(com.solar.launcher.R.string.podcast_country_au, "AU"),
            new Country(com.solar.launcher.R.string.podcast_country_in, "IN"),
            new Country(com.solar.launcher.R.string.podcast_country_br, "BR"),
            new Country(com.solar.launcher.R.string.podcast_country_mx, "MX"),
            new Country(com.solar.launcher.R.string.podcast_country_es, "ES"),
            new Country(com.solar.launcher.R.string.podcast_country_it, "IT"),
            new Country(com.solar.launcher.R.string.podcast_country_nl, "NL"),
            new Country(com.solar.launcher.R.string.podcast_country_se, "SE"),
    };

    /** Direct RSS feeds (no iTunes lookup) — geographic mix, not UK-only. */
    public static final OpenRssClient.Podcast[] FEATURED = {
            new OpenRssClient.Podcast("NPR Up First", "NPR",
                    "https://feeds.npr.org/510289/podcast.xml", null),
            new OpenRssClient.Podcast("BBC Global News", "BBC World Service",
                    "https://podcasts.files.bbci.co.uk/p02nq0gn.rss", null),
            new OpenRssClient.Podcast("Le journal de 18h", "France Inter",
                    "https://radiofrance-podcast.net/podcast09/rss_13957.xml", null),
            new OpenRssClient.Podcast("The Daily", "The New York Times",
                    "https://feeds.simplecast.com/54nAGcIl", null),
            new OpenRssClient.Podcast("99% Invisible", "Radiotopia",
                    "https://feeds.99percentinvisible.org/99percentinvisible", null),
    };

    private PodcastCatalog() {}
}

package com.fongmi.android.tv.search;

import com.github.catvod.utils.Trans;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps aggregate search results relevant when sources finish in an arbitrary
 * order.  The score is deliberately title-first: source order must never
 * decide which work appears at the head of a search opened from a home card.
 */
public final class SearchResultRanker {
    private static final Pattern SEASON = Pattern.compile("第([零〇一二两三四五六七八九十百0-9]+)[季部]|(?i)(?<![a-z0-9])(?:season\\s*|s)([0-9]+)(?![0-9a-z])");
    private static final Pattern YEAR = Pattern.compile("(?<![0-9])(?:18|19|20|21)[0-9]{2}(?![0-9])");
    private static final Pattern QUALITY = Pattern.compile("(?i)(?:[248]k|(?:480|576|720|1080|1440|2160|4320)[pi])(?![a-z0-9])");
    private static final Pattern DECORATED_YEAR = Pattern.compile("(?<![\\p{L}\\p{N}])((?:18|19|20|21)[0-9]{2})(?![\\p{L}\\p{N}])");

    private SearchResultRanker() {
    }

    public static int score(String title, String year, String query, String expectedYear) {
        return target(query, expectedYear).score(title, year);
    }

    /** Normalize the clicked card once, rather than inside every sort comparison. */
    public static Target target(String title, String year) {
        return new Target(normalizeTitle(title), normalizeYear(year));
    }

    /** Manual queries rank every source result; they do not exclude other works. */
    public static Query query(String title) {
        return new Query(normalizeTitle(title));
    }

    public static final class Query {
        private static final int UNRELATED = 200_000;
        private static final int OVERLAP_LIMIT = 256;
        private final String title;

        private Query(String title) {
            this.title = title;
        }

        /** Lower scores are more relevant; no source or year breaks equal-title ties. */
        public int score(String candidateTitle) {
            String original = normalizeTitle(candidateTitle);
            if (title.isEmpty() || original.isEmpty()) return UNRELATED;
            if (original.equals(title)) return 0;
            // Strip decorations before parsing an appended number: 4K and an
            // isolated 2024 are not installments, and "片名2 4K" is part 2.
            String candidate = normalizeTitle(withoutDecorations(candidateTitle, title));
            if (candidate.equals(title)) return 0;
            int installment = addedInstallment(candidate, candidateTitle);
            if (installment > 0) return 10_000 + installment;
            // The query can itself name a quality token, e.g. "1080p". Its
            // original full match remains relevant even after decoration cleanup.
            int canonicalFull = rank(candidate, title);
            int full = Math.min(canonicalFull, rank(original, title));
            if (full < 3) return full * 10_000 + 20_000
                    + Math.min(9_999, (canonicalFull == full ? candidate : original).length() - title.length());
            int overlap = longestOverlap(candidate, title);
            // One shared character in a long title is too weak to distinguish
            // an otherwise unrelated result. Short queries still work directly.
            if (overlap < Math.min(2, title.length())) return UNRELATED;
            return 50_000 + (OVERLAP_LIMIT - overlap) * 256
                    + Math.min(255, Math.abs(candidate.length() - title.length()));
        }

        private int addedInstallment(String candidate, String rawCandidate) {
            int best = Integer.MAX_VALUE;
            if (!Character.isDigit(title.charAt(title.length() - 1))) {
                for (int offset = candidate.indexOf(title); offset >= 0; offset = candidate.indexOf(title, offset + 1)) {
                    if (Character.isDigit(title.charAt(0)) && offset > 0 && Character.isDigit(candidate.charAt(offset - 1))) continue;
                    int start = offset + title.length(), end = start;
                    while (end < candidate.length() && Character.isDigit(candidate.charAt(end))) end++;
                    if (end == start) continue;
                    int number = seasonNumber(candidate.substring(start, end));
                    // A fused four-digit year is not a plausible installment number.
                    if (number > 0 && number <= 999) best = Math.min(best, number);
                }
            } else {
                // Numeric work names (1899, 3%) may still declare an explicit
                // second season. Bare added digits remain ambiguous (2 vs 20).
                String source = Normalizer.normalize(rawCandidate, Normalizer.Form.NFKC);
                Matcher seasons = SEASON.matcher(source);
                while (seasons.find()) {
                    String prefix = normalizeTitle(source.substring(0, seasons.start()));
                    if (rank(prefix, title) >= 3) continue;
                    int number = seasonNumber(seasons.group(1) != null ? seasons.group(1) : seasons.group(2));
                    if (number > 0 && number <= 999) best = Math.min(best, number);
                }
            }
            return best == Integer.MAX_VALUE ? -1 : best;
        }

        private static int longestOverlap(String candidate, String query) {
            int candidateLength = Math.min(OVERLAP_LIMIT, candidate.length());
            int queryLength = Math.min(OVERLAP_LIMIT, query.length());
            int[] lengths = new int[queryLength + 1];
            int longest = 0;
            // Bounded longest common substring, not an unbounded edit-distance
            // sort. This runs once per resource, never inside the comparator.
            for (int i = 0; i < candidateLength; i++) {
                for (int j = queryLength; j > 0; j--) {
                    lengths[j] = candidate.charAt(i) == query.charAt(j - 1) ? lengths[j - 1] + 1 : 0;
                    longest = Math.max(longest, lengths[j]);
                }
            }
            return longest;
        }
    }

    public static final class Target {
        private final String title, year;

        private Target(String title, String year) {
            this.title = title;
            this.year = year;
        }

        public int score(String candidateTitle, String candidateYear) {
            int titleRank = rankCandidate(candidateTitle, title);
            if (titleRank >= 3 || year.isEmpty()) return titleRank * 10;
            String actualYear = normalizeYear(candidateYear);
            if (actualYear.isEmpty()) actualYear = decoratedYear(candidateTitle, title);
            return titleRank * 10 + (actualYear.isEmpty() ? 1 : actualYear.equals(year) ? 0 : 2);
        }

        /** Unknown years are still usable; explicitly different works must not replace a home card. */
        public boolean accepts(int score) {
            return score < 30 && score % 10 != 2;
        }
    }

    public static int titleRank(String title, String query) {
        return rankCandidate(title, normalizeTitle(query));
    }

    private static int rankCandidate(String value, String target) {
        int result = rank(normalizeTitle(value), target);
        if (result < 3) return result;
        String source = withoutQuality(value);
        result = rank(normalizeTitle(source), target);
        if (result < 3) return result;
        return rank(normalizeTitle(withoutDecorations(value, target)), target);
    }

    private static String withoutDecorations(String value, String target) {
        String source = withoutQuality(value);
        Matcher years = DECORATED_YEAR.matcher(source);
        StringBuffer stripped = new StringBuffer();
        while (years.find()) years.appendReplacement(stripped, Matcher.quoteReplacement(
                isYearDecoration(source, years, target) ? "" : years.group()));
        years.appendTail(stripped);
        return stripped.toString();
    }

    private static String withoutQuality(String value) {
        return QUALITY.matcher(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)).replaceAll("");
    }

    private static String decoratedYear(String value, String target) {
        String source = withoutQuality(value);
        Matcher years = DECORATED_YEAR.matcher(source);
        while (years.find()) if (isYearDecoration(source, years, target)) return years.group(1);
        return "";
    }

    private static boolean isYearDecoration(String source, Matcher year, String target) {
        // A numeric work name or a year within the clicked title is not metadata.
        if (target.contains(year.group(1)) || normalizeTitle(source).equals(year.group(1))) return false;
        String before = source.substring(0, year.start()).trim();
        String after = source.substring(year.end()).trim();
        return before.isEmpty() || after.isEmpty()
                || before.endsWith("(") && after.startsWith(")")
                || before.endsWith("[") && after.startsWith("]")
                || before.endsWith("【") && after.startsWith("】");
    }

    private static int rank(String candidate, String target) {
        if (candidate.isEmpty() || target.isEmpty()) return 3;
        if (candidate.equals(target)) return 0;
        // A shorter prefix (e.g. first season) does not identify the requested sequel.
        for (int offset = candidate.indexOf(target); offset >= 0; offset = candidate.indexOf(target, offset + 1)) {
            int end = offset + target.length();
            // An added installment is different even when the target is the
            // unnumbered original. Edition/year tokens are handled separately,
            // before punctuation removal can fuse e.g. "沙丘2 4K" into "沙丘24k".
            boolean extendedNumber = end < candidate.length() && Character.isDigit(candidate.charAt(end));
            boolean prefixedNumber = Character.isDigit(target.charAt(0))
                    && offset > 0 && Character.isDigit(candidate.charAt(offset - 1));
            if (!extendedNumber && !prefixedNumber) return offset == 0 ? 1 : 2;
        }
        return 3;
    }

    static String normalizeTitle(String value) {
        if (value == null || value.isEmpty()) return "";
        String source;
        try {
            // Matching is locale-independent; unlike the network query helper,
            // this must also normalize traditional titles on a simplified-
            // Chinese device (where Trans.t2s() intentionally passes through).
            // The existing conversion table maps 餘 to 馀; both written forms
            // must identify the same title as the commonly used simplified 余.
            source = Trans.t2s(false, Normalizer.normalize(value, Normalizer.Form.NFKC)).replace('馀', '余');
        } catch (RuntimeException ignored) {
            source = value;
        }
        Matcher seasons = SEASON.matcher(source);
        StringBuffer normalized = new StringBuffer();
        while (seasons.find()) {
            String number = seasons.group(1) != null ? seasons.group(1) : seasons.group(2);
            int parsed = seasonNumber(number);
            seasons.appendReplacement(normalized, Matcher.quoteReplacement(parsed > 0 ? String.valueOf(parsed) : seasons.group()));
        }
        seasons.appendTail(normalized);
        source = normalized.toString();
        StringBuilder result = new StringBuilder(source.length());
        source.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static String normalizeYear(String value) {
        if (value == null || value.isEmpty()) return "";
        Matcher match = YEAR.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC));
        return match.find() ? match.group() : "";
    }

    private static int seasonNumber(String value) {
        if (value.matches("[0-9]+")) {
            try { return Integer.parseInt(value); }
            catch (NumberFormatException ignored) { return -1; }
        }
        int total = 0, digit = 0;
        for (char character : value.toCharArray()) {
            if (character == '十' || character == '百') {
                total += (digit == 0 ? 1 : digit) * (character == '十' ? 10 : 100);
                digit = 0;
            } else if (character == '两') digit = 2;
            else {
                int index = "零一二三四五六七八九".indexOf(character);
                if (character == '〇') index = 0;
                if (index < 0) return -1;
                digit = index;
            }
        }
        return total + digit;
    }
}

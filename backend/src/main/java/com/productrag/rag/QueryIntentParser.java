package com.productrag.rag;

import com.productrag.product.filter.FilterField;
import com.productrag.product.filter.FilterOperator;
import com.productrag.product.filter.ProductFilter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Deterministic, rule-based interpretation of exact conditions in a question (price/stock
 * comparisons, SKU, category, counts, "cheapest"...). The output is a set of validated
 * {@link ProductFilter}s - never SQL. When a condition is detected but cannot be parsed reliably,
 * the intent is marked ambiguous so the caller can ask for clarification instead of guessing.
 */
@Component
public class QueryIntentParser {

    private static final String AMOUNT =
            "((?:₹|rs\\.?|inr|\\$|usd|€|eur)?\\s*\\d[\\d,]*(?:\\.\\d+)?"
                    + "(?:\\s*(?:k\\b|thousand\\b|lakhs?\\b|lacs?\\b))?"
                    + "(?:\\s*(?:rupees?\\b|rs\\b\\.?|inr\\b|dollars?\\b|usd\\b|euros?\\b|eur\\b))?)";

    private static final Map<String, FilterOperator> OPERATORS = new LinkedHashMap<>();

    static {
        for (String op : List.of("less than", "lower than", "cheaper than", "fewer than", "under", "below",
                "beneath", "<")) {
            OPERATORS.put(op, FilterOperator.LT);
        }
        for (String op : List.of("up to", "upto", "at most", "maximum of", "max", "no more than", "not more than",
                "within", "<=")) {
            OPERATORS.put(op, FilterOperator.LTE);
        }
        for (String op : List.of("more than", "greater than", "higher than", "costlier than", "more expensive than",
                "over", "above", ">")) {
            OPERATORS.put(op, FilterOperator.GT);
        }
        for (String op : List.of("at least", "minimum of", "min", "no less than", "not less than", ">=")) {
            OPERATORS.put(op, FilterOperator.GTE);
        }
    }

    private static final Pattern COMPARATOR = Pattern.compile("(?<![\\w])("
            + OPERATORS.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(op -> op.matches("[a-z ]+") ? op.replace(" ", "\\s+") : Pattern.quote(op))
            .collect(Collectors.joining("|"))
            + ")\\s*" + AMOUNT);

    private static final Pattern BETWEEN = Pattern.compile(
            "\\b(?:between|from)\\s+" + AMOUNT + "\\s*(?:and|to|-|–)\\s*" + AMOUNT);

    private static final Pattern WORD_NUMBER_COMPARISON = Pattern.compile(
            "\\b(?:less than|under|below|over|above|more than|greater than|cheaper than|at least|at most|up to|within)"
                    + "\\s+(?:a\\s+|one\\s+)?(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|"
                    + "fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|lakh|grand|"
                    + "couple|few)\\b");

    /** A number followed by one of these is a specification (e.g. "30 hours"), not a price or stock value. */
    private static final Pattern UNIT_AFTER_NUMBER = Pattern.compile(
            "^\\s*(?:hours?|hrs?|h|mah|wh|w|watts?|kw|gb|tb|mb|mm|cm|meters?|metres?|inch(?:es)?|kg|g|grams?|lbs?|"
                    + "years?|yrs?|months?|days?|weeks?|minutes?|mins?|seconds?|secs?|db|hz|khz|mhz|ghz|mp|"
                    + "megapixels?|percent|v|volts?|litres?|liters?|ml|people|persons?|users?|devices?|ports?|"
                    + "cores?|pages?|feet|ft|rpm|dpi|fps|keys?|channels?)\\b|^\\s*[%\"×]");

    private static final Pattern PRICE_WORDS = Pattern.compile(
            "\\b(?:price[sd]?|pricing|cost(?:s|ing)?|cheap(?:er|est)?|expensive|costlier|budget|afford(?:able)?|"
                    + "rupees?|inr|rs|usd|dollars?|eur|euros?|spend|pay)\\b|₹|\\$|€");
    private static final Pattern STOCK_WORDS = Pattern.compile(
            "\\b(?:stock|stocks|in-stock|inventory|units?|quantity|quantities|qty|pieces|pcs|fewer)\\b");
    private static final Pattern CURRENCY_MARK = Pattern.compile("₹|\\$|€|\\b(?:rs|inr|rupees?|usd|dollars?|eur|euros?)\\b");

    private static final Pattern OUT_OF_STOCK = Pattern.compile("\\bout\\s+of\\s+stock\\b");
    private static final Pattern IN_STOCK = Pattern.compile("\\bin[\\s-]stock\\b");
    private static final Pattern EXPLICIT_SKU = Pattern.compile(
            "\\bsku\\s*(?:no\\.?|number|code|id)?\\s*[:#]?\\s*([a-z0-9._-]*\\d[a-z0-9._-]*)");
    private static final Pattern SKU_LIKE = Pattern.compile("(?<![\\w-])[a-z0-9]+(?:[-_.][a-z0-9]+)+(?![\\w-])");
    private static final Pattern COUNT = Pattern.compile("\\b(?:how many|number of|count|total)\\b");

    private static final Map<SortHint, Pattern> SORT_HINTS = Map.of(
            SortHint.PRICE_ASC, Pattern.compile(
                    "\\b(?:cheapest|least expensive|lowest[\\s-]priced|lowest price|most affordable)\\b"),
            SortHint.PRICE_DESC, Pattern.compile(
                    "\\b(?:most expensive|priciest|costliest|highest[\\s-]priced|highest price)\\b"),
            SortHint.STOCK_DESC, Pattern.compile(
                    "\\b(?:most stock|highest stock|largest stock|most units|best stocked|most in stock)\\b"),
            SortHint.STOCK_ASC, Pattern.compile(
                    "\\b(?:lowest stock|least stock|fewest units|low on stock|lowest on stock|running low)\\b"));

    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(Arrays.asList((
            "a an the of and or to in on at by for from with within into about as is are was were be been being "
                    + "do does did have has had there that this these those it its which what whats who whom how "
                    + "many much show list find give get me us we you i our your my please can could would should "
                    + "tell any all some product products item items cost costs costing priced price prices pricing "
                    + "less than under below over above more greater lower higher between up upto most least max "
                    + "min maximum minimum stock stocks units unit quantity qty available availability catalog "
                    + "catalogue active currently current currency rupee rupees inr rs usd dollar dollars eur euro "
                    + "euros sku skus count number total category categories sell sells selling offer offers carry "
                    + "store shop cheap cheaper cheapest expensive priciest costliest exactly only just also than "
                    + "inventory pieces pcs whose where").split(" ")));

    public QueryIntent parse(String question, Collection<String> knownCategories, String defaultCurrency) {
        return parse(question, knownCategories, List.of(), defaultCurrency);
    }

    public QueryIntent parse(String question, Collection<String> knownCategories, Collection<String> knownBrands,
                             String defaultCurrency) {
        String lower = question.toLowerCase(Locale.ROOT);
        StringBuilder remaining = new StringBuilder(lower);
        List<ProductFilter> filters = new ArrayList<>();
        String ambiguity = null;

        // 1. Explicit SKU ("SKU WH-001")
        String sku = null;
        Matcher skuMatcher = EXPLICIT_SKU.matcher(lower);
        if (skuMatcher.find()) {
            sku = skuMatcher.group(1).replaceAll("[._-]+$", "").toUpperCase(Locale.ROOT);
            blank(remaining, skuMatcher.start(), skuMatcher.end());
        }
        List<String> skuCandidates = new ArrayList<>();
        Matcher skuLike = SKU_LIKE.matcher(lower);
        while (skuLike.find()) {
            String candidate = skuLike.group();
            if (candidate.matches(".*\\d.*") && candidate.matches(".*[a-z].*")) {
                String normalized = candidate.toUpperCase(Locale.ROOT);
                if (!normalized.equals(sku) && !skuCandidates.contains(normalized)) {
                    skuCandidates.add(normalized);
                }
            }
        }

        boolean priceFilter = false;
        boolean stockFilter = false;

        // 2. Ranges ("between 500 and 1000")
        Matcher between = BETWEEN.matcher(remaining.toString());
        while (between.find()) {
            if (isFollowedByUnit(lower, between.end(1)) || isFollowedByUnit(lower, between.end(2))) {
                continue;
            }
            FilterField field = decideField(lower, between.start(), between.end(), between.group(1) + between.group(2));
            BigDecimal low = parseAmount(between.group(1));
            BigDecimal high = parseAmount(between.group(2));
            if (field == null || low == null || high == null || low.compareTo(high) > 0) {
                ambiguity = ambiguity != null ? ambiguity : ambiguousRange(between.group());
                continue;
            }
            List<ProductFilter> range = toFilters(field, low, high);
            if (range == null) {
                ambiguity = ambiguity != null ? ambiguity : "Stock quantities must be whole numbers.";
                continue;
            }
            filters.addAll(range);
            priceFilter |= field == FilterField.PRICE;
            stockFilter |= field == FilterField.STOCK_QUANTITY;
            blank(remaining, between.start(), between.end());
        }

        // 3. Comparisons ("under ₹1,000", "stock greater than 10")
        Matcher comparison = COMPARATOR.matcher(remaining.toString());
        while (comparison.find()) {
            if (isFollowedByUnit(lower, comparison.end())) {
                continue;
            }
            FilterOperator operator = OPERATORS.get(comparison.group(1).replaceAll("\\s+", " "));
            FilterField field = decideField(lower, comparison.start(), comparison.end(), comparison.group(2));
            BigDecimal amount = parseAmount(comparison.group(2));
            if (field == null || amount == null) {
                ambiguity = ambiguity != null ? ambiguity
                        : "I couldn't tell whether \"" + comparison.group().trim()
                        + "\" refers to the price or the stock quantity. Please rephrase, for example "
                        + "\"price under ₹1000\" or \"stock greater than 10\".";
                continue;
            }
            ProductFilter filter = toFilter(field, operator, amount);
            if (filter == null) {
                ambiguity = ambiguity != null ? ambiguity : "Stock quantities must be whole numbers.";
                continue;
            }
            filters.add(filter);
            priceFilter |= field == FilterField.PRICE;
            stockFilter |= field == FilterField.STOCK_QUANTITY;
            blank(remaining, comparison.start(), comparison.end());
        }

        // 4. Amounts written in words ("under a thousand rupees") cannot be parsed reliably.
        Matcher wordNumber = WORD_NUMBER_COMPARISON.matcher(remaining.toString());
        if (ambiguity == null && wordNumber.find()
                && (PRICE_WORDS.matcher(lower).find() || STOCK_WORDS.matcher(lower).find())) {
            ambiguity = "Please write the amount in \"" + wordNumber.group().trim()
                    + "\" as a number (for example \"under ₹1000\") so I can apply an exact filter.";
        }

        // 5. Stock availability
        if (!stockFilter) {
            Matcher out = OUT_OF_STOCK.matcher(remaining.toString());
            if (out.find()) {
                filters.add(ProductFilter.stock(FilterOperator.EQ, 0));
                blank(remaining, out.start(), out.end());
            } else {
                Matcher in = IN_STOCK.matcher(remaining.toString());
                if (in.find()) {
                    filters.add(ProductFilter.stock(FilterOperator.GT, 0));
                    blank(remaining, in.start(), in.end());
                }
            }
        }

        // 6. Category ("in Electronics", "electronics products", "category: Audio")
        if (knownCategories != null) {
            List<String> categories = knownCategories.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
            for (String category : categories) {
                Matcher matcher = categoryPattern(category).matcher(remaining.toString());
                if (matcher.find()) {
                    filters.add(ProductFilter.category(category));
                    blank(remaining, matcher.start(), matcher.end());
                    break;
                }
            }
        }

        // 7. Brand: only when exactly one known brand is named ("Samsung or Apple?" compares, so no filter)
        if (knownBrands != null) {
            List<Matcher> mentioned = knownBrands.stream()
                    .filter(b -> b != null && !b.isBlank())
                    .distinct()
                    .map(b -> brandPattern(b).matcher(remaining.toString()))
                    .filter(Matcher::find)
                    .toList();
            if (mentioned.size() == 1) {
                Matcher matcher = mentioned.getFirst();
                filters.add(ProductFilter.brand(matcher.group()));
                blank(remaining, matcher.start(), matcher.end());
            }
        }

        // 8. Sorting hints
        SortHint sortHint = null;
        for (Map.Entry<SortHint, Pattern> entry : SORT_HINTS.entrySet()) {
            Matcher matcher = entry.getValue().matcher(remaining.toString());
            if (matcher.find()) {
                sortHint = entry.getKey();
                blank(remaining, matcher.start(), matcher.end());
                break;
            }
        }

        if (priceFilter) {
            filters.add(ProductFilter.currency(detectCurrency(lower, defaultCurrency)));
        }

        boolean countQuestion = COUNT.matcher(lower).find();
        String semanticText = Arrays.stream(remaining.toString().split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 2)
                .filter(token -> !STOP_WORDS.contains(token))
                .filter(token -> !token.matches("\\d+"))
                .collect(Collectors.joining(" "));

        return new QueryIntent(List.copyOf(filters), sku, List.copyOf(skuCandidates), countQuestion, sortHint,
                semanticText, ambiguity);
    }

    private static Pattern categoryPattern(String category) {
        String cat = Pattern.quote(category.toLowerCase(Locale.ROOT));
        return Pattern.compile(
                "\\b(?:in|from|within|under)\\s+(?:the\\s+)?" + cat + "(?:\\s+category)?\\b"
                        + "|\\b" + cat + "\\s+(?:category|products|items|section)\\b"
                        + "|\\bcategory\\s*(?:is|=|:|named|called)?\\s*" + cat + "\\b"
                        + "|\\bhow\\s+many\\s+(?:active\\s+)?" + cat + "\\b");
    }

    private static Pattern brandPattern(String brand) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(brand.trim().toLowerCase(Locale.ROOT))
                + "(?![\\p{L}\\p{N}])");
    }

    private static boolean isFollowedByUnit(String lower, int end) {
        return end <= lower.length() && UNIT_AFTER_NUMBER.matcher(lower.substring(end)).find();
    }

    private static FilterField decideField(String lower, int start, int end, String amountText) {
        if (CURRENCY_MARK.matcher(amountText).find()) {
            return FilterField.PRICE;
        }
        int stockDistance = nearestDistance(STOCK_WORDS, lower, start, end);
        int priceDistance = nearestDistance(PRICE_WORDS, lower, start, end);
        if (stockDistance == Integer.MAX_VALUE && priceDistance == Integer.MAX_VALUE) {
            return null;
        }
        return stockDistance < priceDistance ? FilterField.STOCK_QUANTITY : FilterField.PRICE;
    }

    private static int nearestDistance(Pattern pattern, String text, int start, int end) {
        Matcher matcher = pattern.matcher(text);
        int best = Integer.MAX_VALUE;
        while (matcher.find()) {
            int distance;
            if (matcher.end() <= start) {
                distance = start - matcher.end();
            } else if (matcher.start() >= end) {
                distance = matcher.start() - end;
            } else {
                distance = 0;
            }
            best = Math.min(best, distance);
        }
        return best;
    }

    static BigDecimal parseAmount(String text) {
        Matcher matcher = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(k|thousand|lakhs?|lacs?)?").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String digits = matcher.group(1).replace(",", "");
        BigDecimal value;
        try {
            value = new BigDecimal(digits);
        } catch (NumberFormatException ex) {
            return null;
        }
        String multiplier = matcher.group(2);
        if (multiplier != null) {
            value = value.multiply(multiplier.startsWith("la")
                    ? BigDecimal.valueOf(100_000) : BigDecimal.valueOf(1_000));
        }
        if (value.compareTo(new BigDecimal("1000000000000")) > 0) {
            return null;
        }
        return value;
    }

    private static ProductFilter toFilter(FilterField field, FilterOperator operator, BigDecimal amount) {
        if (field == FilterField.PRICE) {
            return ProductFilter.price(operator, amount.setScale(2, RoundingMode.HALF_UP));
        }
        if (amount.stripTrailingZeros().scale() > 0 || amount.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return null;
        }
        return ProductFilter.stock(operator, amount.intValueExact());
    }

    private static List<ProductFilter> toFilters(FilterField field, BigDecimal low, BigDecimal high) {
        ProductFilter lowFilter = toFilter(field, FilterOperator.GTE, low);
        ProductFilter highFilter = toFilter(field, FilterOperator.LTE, high);
        return lowFilter == null || highFilter == null ? null : List.of(lowFilter, highFilter);
    }

    private static String detectCurrency(String lower, String defaultCurrency) {
        if (lower.contains("₹") || lower.matches(".*\\b(?:rs|inr|rupees?)\\b.*")) {
            return "INR";
        }
        if (lower.contains("$") || lower.matches(".*\\b(?:usd|dollars?)\\b.*")) {
            return "USD";
        }
        if (lower.contains("€") || lower.matches(".*\\b(?:eur|euros?)\\b.*")) {
            return "EUR";
        }
        return defaultCurrency;
    }

    private static String ambiguousRange(String text) {
        return "I couldn't reliably interpret the range \"" + text.trim()
                + "\". Please state whether it is a price or stock range, for example \"price between ₹500 and ₹1000\".";
    }

    private static void blank(StringBuilder text, int start, int end) {
        for (int i = start; i < end && i < text.length(); i++) {
            text.setCharAt(i, ' ');
        }
    }
}

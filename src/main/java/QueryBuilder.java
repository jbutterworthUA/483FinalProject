
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * QueryBuilder
 *
 * Converts a Jeopardy clue (and optional category) into a weighted
 * Lucene BooleanQuery that searches both the "title" and "content" fields.
 *
 * -----------------------------------------------------------------------
 * Algorithm
 * -----------------------------------------------------------------------
 *
 * Naïvely searching the full clue string gives poor results because:
 * - Function words ("this", "who", "at") dominate term statistics.
 * - Common verbs and adjectives ("won", "consecutive") appear on many pages.
 * - The discriminative signal sits in proper nouns and specific nouns.
 *
 * We use a lightweight POS-inspired heuristic based on capitalisation
 * (no external NLP library needed) combined with Lucene's EnglishAnalyzer:
 *
 * 1. Tokenise the clue into words, preserving original capitalisation.
 * 2. Run each word through EnglishAnalyzer (lowercase + stem + stopwords).
 * If the analyser produces a token, it is content-bearing.
 * 3. Capitalised tokens that survive analysis are treated as proper nouns
 * and receive a higher BoostQuery multiplier (3.0×).
 * 4. Remaining content tokens (lower-cased in original) get 1.0× weight.
 * 5. Category tokens (after analysis) are added at 1.5× weight – they
 * often name the entity class ("NEWSPAPERS", "U.S. PRESIDENTS") and
 * usefully narrow the search even when no proper noun is in the clue.
 * 6. Each surviving term generates two TermQuery clauses – one on "title"
 * (boosted 3× extra) and one on "content" (base weight) – joined in a
 * BooleanQuery with SHOULD (OR semantics).
 *
 * Why OR (SHOULD) rather than AND (MUST)?
 * Conjunctive queries require every term to appear in the document.
 * Paraphrases between clues and Wikipedia text mean that enforcing all
 * terms hurts recall significantly. BM25 with OR semantics naturally
 * promotes pages matching many terms without disqualifying pages that
 * miss one or two.
 *
 * Why boost the title field?
 * Jeopardy answers are Wikipedia titles by design. A page whose title
 * IS the answer will outscore a page that merely mentions the answer
 * in passing, which is exactly the behaviour we want.
 */
public class QueryBuilder {

    private static final float TITLE_FIELD_BOOST = 3.0f;
    private static final float PROPER_NOUN_BOOST = 3.0f;
    private static final float COMMON_TERM_BOOST = 1.0f;
    private static final float CATEGORY_TERM_BOOST = 1.5f;

    private static final Set<String> EXTRA_STOPWORDS = new HashSet<>(Arrays.asList(
            "also", "known", "referred", "called", "became", "however",
            "although", "another", "often", "used", "made", "may",
            "first", "second", "third", "new", "later", "early", "late",
            "named", "whose", "would", "could", "said", "including"));

    private final EnglishAnalyzer analyzer;

    public QueryBuilder() {
        this.analyzer = new EnglishAnalyzer();
    }

    /**
     * Build a weighted BooleanQuery for the given clue and category.
     *
     * @param clue     raw Jeopardy clue string
     * @param category Jeopardy category string (pass "" to omit)
     * @return a Lucene Query ready to hand to IndexSearcher
     */
    public Query buildQuery(String clue, String category) throws IOException {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        // ---- Extract Quotes --------------------------------------------
        java.util.regex.Matcher quoteMatcher = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(clue);
        while (quoteMatcher.find()) {
            String phrase = quoteMatcher.group(1);
            String[] quoteWords = phrase.split("\\s+");
            PhraseQuery.Builder pqBuilderContent = new PhraseQuery.Builder();
            pqBuilderContent.setSlop(2);

            int position = 0;
            int validTokens = 0;
            for (String qw : quoteWords) {
                String stemmed = analyzeSingle(qw);
                if (stemmed != null) {
                    pqBuilderContent.add(new Term("content", stemmed), position);
                    validTokens++;
                }
                // FIX: Always advance position, even for stopwords, to match index gaps!
                position++;
            }
            // Heavily boost if we found a multi-word quote
            if (validTokens > 1) {
                builder.add(new BoostQuery(pqBuilderContent.build(), 5.0f), BooleanClause.Occur.SHOULD);
            }

        }

        // ---- Clue terms ------------------------------------------------
        String[] words = clue.split("\\s+");
        List<String> analyzedWords = new ArrayList<>();
        List<Boolean> isCapitalized = new ArrayList<>();

        for (int wdIdx = 0; wdIdx < words.length; wdIdx++) {
            String word = words[wdIdx];
            if (word.isEmpty())
                continue;

            // FIX: Restored "&& wdIdx > 0" to prevent the first word of the sentence
            // (like "The", "This", "Originally") from hijacking the proper noun boost.
            boolean capitalized = Character.isUpperCase(word.charAt(0))
                    && !word.equals(word.toUpperCase()) // ignore ALL-CAPS noise
                    && wdIdx > 0;

            String stemmed = analyzeSingle(word);
            if (stemmed == null)
                continue; // stopword or empty after stemming
            if (EXTRA_STOPWORDS.contains(stemmed))
                continue;
            if (stemmed.length() <= 2)
                continue;

            analyzedWords.add(stemmed);
            isCapitalized.add(capitalized);

            float termBoost = capitalized ? PROPER_NOUN_BOOST : COMMON_TERM_BOOST;

            // FIX: Removed the Title clause entirely. Clues describe the content,
            // they do not dictate the Wikipedia page title.

            // Content clause (base term boost)
            Query contentQ = new BoostQuery(
                    new TermQuery(new Term("content", stemmed)),
                    termBoost);
            builder.add(contentQ, BooleanClause.Occur.SHOULD);
        }

        // Add bigrams from surviving clue words to capture "name" entities
        for (int i = 0; i < analyzedWords.size() - 1; i++) {
            String w1 = analyzedWords.get(i);
            String w2 = analyzedWords.get(i + 1);
            boolean bothCap = isCapitalized.get(i) && isCapitalized.get(i + 1);

            float phraseBoost = bothCap ? 5.0f : 2.0f;

            PhraseQuery.Builder contentPqBuilder = new PhraseQuery.Builder();
            contentPqBuilder.add(new Term("content", w1), 0);
            contentPqBuilder.add(new Term("content", w2), 1);

            // FIX: Add slop here too. Surviving clue words might have had
            // stopwords between them in the original text.
            contentPqBuilder.setSlop(3);

            builder.add(new BoostQuery(contentPqBuilder.build(), phraseBoost), BooleanClause.Occur.SHOULD);
        }
        
        // ---- Category terms --------------------------------------------
        if (category != null && !category.isBlank()) {
            for (String word : category.split("\\s+")) {
                String stemmed = analyzeSingle(word);
                if (stemmed == null || stemmed.length() <= 2)
                    continue;
                if (EXTRA_STOPWORDS.contains(stemmed))
                    continue;

                // FIX: Removed Category Title clause. Categories are thematic
                // and almost never appear in the actual Wikipedia page title.
                Query catContentQ = new BoostQuery(
                        new TermQuery(new Term("content", stemmed)),
                        CATEGORY_TERM_BOOST);
                builder.add(catContentQ, BooleanClause.Occur.SHOULD);
            }
        }

        return builder.build();
    }

    /**
     * Run a single word through EnglishAnalyzer and return the stemmed token,
     * or null if the analyser filtered it out (stopword, too short, etc.).
     */
    private String analyzeSingle(String word) throws IOException {
        try (TokenStream ts = analyzer.tokenStream("_", new StringReader(word))) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            if (ts.incrementToken()) {
                String token = attr.toString();
                ts.end();
                return token.isEmpty() ? null : token;
            }
            ts.end();
            return null;
        }
    }
}

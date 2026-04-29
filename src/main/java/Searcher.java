
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.search.similarities.BM25Similarity;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * Searcher
 *
 * Wraps a Lucene IndexSearcher and exposes two public methods:
 * - search(clue, category, topK) → ranked list of (title, score) pairs
 * - predict(clue, category) → single best-matching title
 *
 * Uses BM25Similarity (Lucene's default since v6), which is a state-of-the-art
 * probabilistic retrieval model. Default BM25 parameters (k1=1.2, b=0.75)
 * work well out of the box; they can be tuned via the constructor overload.
 */
public class Searcher implements Closeable {

    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final QueryBuilder queryBuilder;

    /**
     * constructor used to open an existing Lucene index with default BM25 parameters. 
     * used after tuning
     *
     * @param indexDir the path to the lucene index
     */
    public Searcher(Path indexDir) throws IOException {
        // DEFAULT k = 1.2f, b = 0.75f;
        // 1st  FIX: Lowered 'b' from 0.75f to 0.20f to stop penalizing long articles
        // 2nd  FIX  Lowered 'b' again from 0.20f to 0.0f and lowered k1 to 0.8f
        this(indexDir, 0.8f, 0.0f);
    }

    /**
     * constructor to open an existing Lucene index with custom BM25 k1 and b parameters. 
     * used for tuning
     *
     * @param k1 term-frequency saturation (typical range 0.5 – 2.0)
     * @param b  length normalisation (0 = none, 1 = full)
     */
    public Searcher(Path indexDir, float k1, float b) throws IOException {
        NIOFSDirectory dir = new NIOFSDirectory(indexDir);
        this.reader = DirectoryReader.open(dir);
        this.searcher = new IndexSearcher(reader);
        this.searcher.setSimilarity(new BM25Similarity(k1, b));
        this.queryBuilder = new QueryBuilder();
    }

    /**
     * Return the top-k (title, score) pairs for the given clue.
     *
     * @param clue the question clue
     * @param category the catgeory of the question
     * @param topK the number of result to return
     */
    public List<SearchResult> search(String clue, String category, int topK) throws IOException {
        Query q = this.queryBuilder.buildQuery(clue, category);
        TopDocs hits = this.searcher.search(q, topK);

        List<SearchResult> results = new ArrayList<>();
        for (ScoreDoc sd : hits.scoreDocs) {
            String title = this.searcher.storedFields().document(sd.doc).get("title_raw");
            results.add(new SearchResult(title, sd.score));
        }
        return results;
    }

    /** 
     * Return the single best-matching Wikipedia title
     *
     * @param clue the clue for a question
     * @param category the category for a question
     */
    public String predict(String clue, String category) throws IOException {
        List<SearchResult> hits = search(clue, category, 1);
        return hits.isEmpty() ? "" : hits.get(0).title();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    /**
     * Simple result record
     *
     * @param title the title of a doc
     * @param score the score of a doc
     */
    public record SearchResult(String title, float score) {}

}

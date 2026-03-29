package edu.ir;

import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;

/**
 * WikiIndexer
 *
 * Reads the 80 Wikipedia flat files and builds a Lucene index on disk.
 * Each Wikipedia page becomes one Lucene Document with the fields:
 *
 *   title_raw  – stored, not analysed  → returned as the answer string
 *   title      – analysed (stemmed)    → boosted field for matching
 *   content    – analysed (stemmed)    → body text field
 *
 * -----------------------------------------------------------------------
 * Pre-processing decisions
 * -----------------------------------------------------------------------
 *
 * 1. Page splitting
 *    Pages are delimited by "[[Title]]" at the start of a line.
 *    We scan each file for these markers and slice the text between them.
 *    Redirect pages (#REDIRECT ...) are skipped; they contain no content.
 *
 * 2. Wikitext cleaning
 *    We apply a series of regex substitutions (fastest approach for 280k pages):
 *      - {{template|...}}  → removed entirely
 *      - [[link|display]]  → keep display text only
 *      - [[link]]          → keep link text
 *      - [http://... text] → keep anchor text
 *      - ==Headings==      → keep text, strip = signs
 *      - <ref>...</ref>    → removed (citations add noise)
 *      - all other HTML    → stripped
 *      - Table rows (|/!)  → stripped
 *      - HTML entities     → decoded (&amp; → &, etc.)
 *      - Punctuation runs  → collapsed to a single space
 *
 * 3. Analyser
 *    Lucene's EnglishAnalyzer applies:
 *      - Standard tokenisation (handles Unicode, contractions)
 *      - Lowercase filter
 *      - English possessive filter ('s removal)
 *      - Standard English stopword list (a, an, the, is, at, …)
 *      - Porter stemmer
 *    This is the same pipeline recommended in the Lucene documentation
 *    for English-language retrieval tasks.
 *
 * 4. Title field boost
 *    Because Jeopardy answers ARE Wikipedia titles, we store the title
 *    in a dedicated analysed field and apply a higher boost at query time
 *    (see Searcher.java).  Lucene 9 uses per-query BoostQuery rather than
 *    per-field index-time boosts.
 */
public class WikiIndexer {

    // Patterns compiled once at class load time
    private static final Pattern RE_PAGE_START =
            Pattern.compile("^\\[\\[(.+?)\\]\\]\\s*$", Pattern.MULTILINE);
    private static final Pattern RE_REDIRECT =
            Pattern.compile("(?i)^\\s*#REDIRECT");
    private static final Pattern RE_TEMPLATE =
            Pattern.compile("\\{\\{[^}]*\\}\\}", Pattern.DOTALL);
    private static final Pattern RE_REF_TAG =
            Pattern.compile("<ref[^>]*>.*?</ref>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_LINK_PIPED =
            Pattern.compile("\\[\\[[^|\\]]+\\|([^\\]]+)\\]\\]");
    private static final Pattern RE_LINK_PLAIN =
            Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern RE_EXT_LINK =
            Pattern.compile("\\[https?://\\S+\\s*([^\\]]*)\\]");
    private static final Pattern RE_HEADING =
            Pattern.compile("={2,6}([^=]+)={2,6}");
    private static final Pattern RE_HTML_TAG =
            Pattern.compile("<[^>]+>");
    private static final Pattern RE_TABLE_ROW =
            Pattern.compile("^\\s*[|!].*$", Pattern.MULTILINE);
    private static final Pattern RE_PUNCT_RUNS =
            Pattern.compile("[^\\w\\s]{3,}");
    private static final Pattern RE_WHITESPACE =
            Pattern.compile("\\s{2,}");

    // -----------------------------------------------------------------------

    public static void buildIndex(Path wikiDir, Path indexDir) throws IOException {
        long startMs = System.currentTimeMillis();

        try (NIOFSDirectory dir = new NIOFSDirectory(indexDir);
             EnglishAnalyzer analyzer = new EnglishAnalyzer();
             IndexWriter writer = new IndexWriter(dir,
                     new IndexWriterConfig(analyzer)
                             .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                             .setRAMBufferSizeMB(256))) {

            File[] wikiFiles = wikiDir.toFile().listFiles();
            if (wikiFiles == null || wikiFiles.length == 0) {
                throw new IOException("No files found in: " + wikiDir);
            }
            java.util.Arrays.sort(wikiFiles);

            int totalPages = 0;
            for (File f : wikiFiles) {
                if (f.getName().startsWith(".")) continue;
                System.out.printf("  Parsing %-40s ", f.getName());
                int filePages = indexFile(f, writer);
                totalPages += filePages;
                System.out.printf("%,d pages%n", filePages);
            }

            System.out.println("\nMerging segments …");
            writer.forceMerge(1);   // single segment → faster queries

            long elapsed = (System.currentTimeMillis() - startMs) / 1000;
            System.out.printf("%nDone. Indexed %,d pages in %ds → %s%n",
                    totalPages, elapsed, indexDir);
        }
    }

    private static int indexFile(File f, IndexWriter writer) throws IOException {
        String raw = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        Matcher m = RE_PAGE_START.matcher(raw);

        // Collect all (position, title) pairs
        java.util.List<long[]>  positions = new java.util.ArrayList<>();
        java.util.List<String>  titles    = new java.util.ArrayList<>();
        while (m.find()) {
            positions.add(new long[]{m.start(), m.end()});
            titles.add(m.group(1).trim());
        }

        int count = 0;
        for (int i = 0; i < titles.size(); i++) {
            int bodyStart = (int) positions.get(i)[1];
            int bodyEnd   = (i + 1 < positions.size())
                    ? (int) positions.get(i + 1)[0]
                    : raw.length();

            String title = titles.get(i);
            String body  = raw.substring(bodyStart, bodyEnd);

            // Skip redirect stubs
            if (RE_REDIRECT.matcher(body).find()) continue;

            String cleanContent = cleanWikitext(body);
            addDocument(writer, title, cleanContent);
            count++;
        }
        return count;
    }

    private static void addDocument(IndexWriter writer, String rawTitle, String content)
            throws IOException {
        Document doc = new Document();

        // Stored, not analysed – returned verbatim as the answer
        doc.add(new StringField("title_raw", rawTitle, Field.Store.YES));

        // Analysed title field – queried with a boost at search time
        doc.add(new TextField("title", rawTitle, Field.Store.NO));

        // Analysed body field
        doc.add(new TextField("content", content, Field.Store.NO));

        writer.addDocument(doc);
    }

    // -----------------------------------------------------------------------
    // Wikitext cleaning
    // -----------------------------------------------------------------------

    static String cleanWikitext(String text) {
        // Decode a handful of common HTML entities first
        text = text.replace("&amp;",  "&")
                   .replace("&lt;",   "<")
                   .replace("&gt;",   ">")
                   .replace("&quot;", "\"")
                   .replace("&nbsp;", " ")
                   .replace("&#160;", " ");

        text = RE_TEMPLATE.matcher(text).replaceAll(" ");
        text = RE_REF_TAG.matcher(text).replaceAll(" ");
        text = RE_LINK_PIPED.matcher(text).replaceAll("$1");
        text = RE_LINK_PLAIN.matcher(text).replaceAll("$1");
        text = RE_EXT_LINK.matcher(text).replaceAll("$1");
        text = RE_HEADING.matcher(text).replaceAll(" $1 ");
        text = RE_HTML_TAG.matcher(text).replaceAll(" ");
        text = RE_TABLE_ROW.matcher(text).replaceAll(" ");
        text = RE_PUNCT_RUNS.matcher(text).replaceAll(" ");
        text = RE_WHITESPACE.matcher(text).replaceAll(" ");
        return text.trim();
    }
}

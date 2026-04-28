
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;

/**
 * WikiIndexer
 *
 * Reads the 80 Wikipedia flat files and builds a Lucene index on disk.
 * Each Wikipedia page becomes one Lucene Document with the fields:
 *
 * title_raw – stored, not analysed → returned as the answer string
 * title – analysed (stemmed) → boosted field for matching
 * content – analysed (stemmed) → body text field
 *
 * -----------------------------------------------------------------------
 * Pre-processing decisions
 * -----------------------------------------------------------------------
 *
 * 1. Page splitting
 * Pages are delimited by "[[Title]]" at the start of a line.
 * We scan each file for these markers and slice the text between them.
 * Redirect pages (#REDIRECT ...) are skipped; they contain no content.
 *
 * 2. Wikitext cleaning
 * We apply a series of regex substitutions (fastest approach for 280k pages):
 * 
 * 3. Analyser
 * Lucene's EnglishAnalyzer applies:
 * - Standard tokenisation (handles Unicode, contractions)
 * - Lowercase filter
 * - English possessive filter ('s removal)
 * - Standard English stopword list (a, an, the, is, at, …)
 * - Porter stemmer
 * This is the same pipeline recommended in the Lucene documentation
 * for English-language retrieval tasks.
 *
 */
public class WikiIndexer {

    // groups specified with (), entire match in group 0, groups increment from left to right through the pattern
    // DOTALL includes new lines in patterns (. = any char except \n) 
  
    // identify page start -> [[start]]
    private static final Pattern RE_PAGE_START = Pattern.compile("^\\[\\[(.+?)\\]\\]\\s*$", Pattern.MULTILINE);

    // identify redirects at start of bodies -> #REDIRECT [[United States]]
    private static final Pattern RE_REDIRECT = Pattern.compile("(?i)^\\s*#REDIRECT");

    // indetify 'info boxes' (templates) -> {{template}}
    private static final Pattern RE_TEMPLATE = Pattern.compile("\\{\\{[^}]*\\}\\}");

    // indetify html <ref> tags -> <ref>...</ref> (little superscript numbers)
    // private static final Pattern RE_REF_TAG = Pattern.compile("<ref[^>]*>.*?</ref>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_REF_BRACKET = Pattern.compile("\\[ref\\].*?\\[/ref\\]", Pattern.DOTALL);

    // indetify piped links -> [[Albert Einstein|Einstein]] clickable 'Einstein' that takes user to [[Albert Einstein]]) 
    private static final Pattern RE_LINK_PIPED = Pattern.compile("\\[\\[[^|\\]]+\\|([^\\]]+)\\]\\]");

    // indetify links where display text is link -> [[Albert Einstein]]
    private static final Pattern RE_LINK_PLAIN = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    // indetify external links -> [https://www.example.com some description text]
    private static final Pattern RE_EXT_LINK = Pattern.compile("\\[https?://\\S+\\s*([^\\]]*)\\]");

    // indetify headings(2-6) -> == Early life ==
    private static final Pattern RE_HEADING = Pattern.compile("={2,6}([^=]+)={2,6}");

    // identify all HTML tags -> <b>, <span>, <table> etc... 
    private static final Pattern RE_HTML_TAG = Pattern.compile("<[^>]+>");

    // identify tables ->
    // {| class="wikitable"
    // ! Header 1 !! Header 2
    // |-
    // | Cell 1 || Cell 2
    // |}
    private static final Pattern RE_TABLE_ROW = Pattern.compile("^\\s*[|!].*$", Pattern.MULTILINE);

    // indetify punctuation runs -> "~~~", "---" etc...
    private static final Pattern RE_PUNCT_RUNS = Pattern.compile("[^\\w\\s]{3,}");

    // identify example file shows lots of [tpl] ... [tpl] instead of other regex
    private static final Pattern RE_TPL_TAG = Pattern.compile("\\[tpl\\](.*?)\\[/tpl\\]", Pattern.DOTALL);

    // indetify runs of whitespace (2 or longer) -> "   "
    private static final Pattern RE_WHITESPACE = Pattern.compile("\\s{2,}");


    /**
     * entry point for building the wiki index. creates a file system dir for the index itelf, 
     * sets the language settings, and initializes an index writer.
     *
     * the index writer is important. its the enttity which organizes and builds the index
     * from the corpus
     *
     * @param wikiDir the path to the wiki data set
     * @param indexDir the path where to store the index
     */
    public static void buildIndex(Path wikiDir, Path indexDir) throws IOException {
        long startMs = System.currentTimeMillis();

        // initialize an IndexWriterConfig and instantiate an analyzer and set other settings
        IndexWriterConfig config = new IndexWriterConfig(new EnglishAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        config.setRAMBufferSizeMB(256);

        // a try-with-resources
        try (
            // initalize the file system directory to store the index
            NIOFSDirectory dir = new NIOFSDirectory(indexDir);

            // initialize the writer
            IndexWriter writer = new IndexWriter(dir, config);
        ) {
            // convert Path to File object and expand all its contents into a list
            File[] wikiFiles = wikiDir.toFile().listFiles();
            if (wikiFiles == null || wikiFiles.length == 0) {
               throw new IOException("No files found in: " + wikiDir);
            }

            // sort files to ensure order on various OS 
            Arrays.sort(wikiFiles);

            // iterate over every file in wikiDir
            int totalPages = 0;
            for (File f : wikiFiles) {
                // skip hidden files
                if (f.getName().startsWith(".")) { continue; }
                System.out.printf("  Parsing %-40s ", f.getName());

                // do the actual indexing
                int filePages = indexFile(f, writer);

                // increment total wiki pages that have been indexed
                totalPages += filePages;
                System.out.printf("%,d pages%n", filePages);
            }

            System.out.println("\nMerging segments …");

            // merge all segments created during indexing into one large segment
            writer.forceMerge(1); // single segment → faster queries

            long elapsed = (System.currentTimeMillis() - startMs) / 1000;
            System.out.printf("%nDone. Indexed %,d pages in %ds → %s%n", totalPages, elapsed, indexDir);
        }
    }

    /**
     * indexes each file that contains thousands of wikipedia pages. Splits the pages contained
     * in the file and sends them one by one to be added to the index
     *
     * @param f the current file being indexed
     * @param writer the writer used to perfom the indexing
     * @return the number of wiki pages in the file
     */
    private static int indexFile(File f, IndexWriter writer) throws IOException {
        // read entire file into one sring
        String raw = Files.readString(f.toPath(), StandardCharsets.UTF_8);

        // initialize a matcher to search the file size string for titles
        Matcher m = RE_PAGE_START.matcher(raw);

        // Collect all (position, title) pairs
        List<int[]> positions = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (m.find()) {
            // store absolute file positions of the start and end of each title
            positions.add(new int[] { m.start(), m.end() });
 
            // regex specifices storing element in group 1
            titles.add(m.group(1).trim());
        }

        int count = 0;
        for (int i = 0; i < titles.size(); i++) {
            // find content after ]]
            int bodyStart = positions.get(i)[1];

            // check if last page or get position where next [[ starts 
            int bodyEnd = (i + 1 < positions.size()) ? positions.get(i + 1)[0] : raw.length();

            String title = titles.get(i);
            String body = raw.substring(bodyStart, bodyEnd);

            /*
             * NOTE: THIS LINE ITSELF KNOCKS AWAY ~100,000 PAGES
             */
            //skip titles that have a body simply redirecting to another title
            if (RE_REDIRECT.matcher(body).find()) { continue; }

            String cleanContent = cleanWikitext(body);
            addDocument(writer, title, cleanContent);
            count++;  
        }

        return count;
    }

    /**
     * adds a document (single wiki page) to the index writer. 
     *
     * indexed but not analyzed -> not ran through the english analyzer
     *
     * @param writer the index writer
     * @param rawTitle wiki page title
     * @param content the body of the wiki page
     */
    private static void addDocument(IndexWriter writer, String rawTitle, String content) throws IOException {
        // initialize a lucene document object
        Document doc = new Document();

        // add an indexed but not analyzed field with the title 
        doc.add(new StringField("title_raw", rawTitle, Field.Store.YES));

        // add an indexed and analyzed field with the title
        doc.add(new TextField("title", rawTitle, Field.Store.NO));

        // add an indexed and analyzed field with the wiki body
        doc.add(new TextField("content", content, Field.Store.NO));

        writer.addDocument(doc);
        // System.out.println("Added document: " + rawTitle);
    }

    /**
     * clean up the body text of a wiki page using the regex patterns specified at the top
     * of this class
     *
     * @param text the body of a wiki page
     * @return the cleaned body of a wiki page
     */
    private static String cleanWikitext(String text) {
        String og_text = text;

        // Decode a handful of common HTML entities first
        text = text.replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&nbsp;", " ")
                   .replace("&#160;", " ");

        // $1 is the group 1 element in the matcher
        text = RE_TEMPLATE.matcher(text).replaceAll(" ");
        // checkCleanUsed(og_text, text, RE_TEMPLATE);

        // text = RE_REF_TAG.matcher(text).replaceAll(" ");
        text = RE_REF_BRACKET.matcher(text).replaceAll(" ");
        // checkCleanUsed(og_text, text, RE_REF_BRACKET);

        text = RE_LINK_PIPED.matcher(text).replaceAll("$1");
        // checkCleanUsed(og_text, text, RE_LINK_PIPED);

        text = RE_LINK_PLAIN.matcher(text).replaceAll("$1");
        // checkCleanUsed(og_text, text, RE_LINK_PLAIN);

        text = RE_EXT_LINK.matcher(text).replaceAll("$1");
        // checkCleanUsed(og_text, text, RE_EXT_LINK);

        text = RE_HEADING.matcher(text).replaceAll(" $1 ");
        // checkCleanUsed(og_text, text, RE_HEADING);

        text = RE_HTML_TAG.matcher(text).replaceAll(" ");
        // checkCleanUsed(og_text, text, RE_HTML_TAG);

        text = RE_TABLE_ROW.matcher(text).replaceAll(" ");
        // checkCleanUsed(og_text, text, RE_TABLE_ROW);

        text = RE_TPL_TAG.matcher(text).replaceAll("$1");
        // checkCleanUsed(og_text, text, RE_TPL_TAG);

        text = RE_PUNCT_RUNS.matcher(text).replaceAll(" ");
        // checkCleanUsed(og_text, text, RE_PUNCT_RUNS);

        text = RE_WHITESPACE.matcher(text).replaceAll(" ");
        // checkCleanUsed(og_text, text, RE_WHITESPACE);

        return text.trim();
    }

    /**
     * just a simple check to log what regex get used on the example file
     *
     * @param og_text text before regex cleaning
     * @param clean_text text after cleaning
     * @pattern the regex patter used
     */
    private static void checkCleanUsed(String og_text, String clean_text, Pattern pattern) {

        switch (pattern.toString()) {
          case "^\\[\\[(.+?)\\]\\]\\s*$":
             System.out.println("RE_PAGE_START used");

          case "(?i)^\\s*#REDIRECT":
             System.out.println("RE_REDIRECT used");

          case "\\{\\{[^}]*\\}\\}":
             System.out.println("RE_TEMPLATE used");

          case "\\[ref\\].*?\\[/ref\\]":
             System.out.println("RE_REF_BRACKET used");

          case "\\[\\[[^|\\]]+\\|([^\\]]+)\\]\\]":
             System.out.println("RE_LINK_PIPED used");

          case "\\[\\[([^\\]]+)\\]\\]":
             System.out.println("RE_LINK_PLAIN used");

          case "\\[https?://\\S+\\s*([^\\]]*)\\]":
             System.out.println("RE_EXT_LINK used");

          case "={2,6}([^=]+)={2,6}":
             System.out.println("RE_HEADING  used");

          case "<[^>]+>":
             System.out.println("RE_HTML_TAG  used");

          case "[^\\w\\s]{3,}":
             System.out.println("RE_PUNCT_RUNS  used");

          case "^\\s*[|!].*$":
             System.out.println("RE_TABLE_ROW used");

          case "\\[tpl\\](.*?)\\[/tpl\\]":
             System.out.println("RE_TPL_TAG  used");

          case "\\s{2,}":
             System.out.println("RE_WHITESPACE  used");

          default:
             System.err.println("Unknown regex: " + pattern);
        }
    }
}


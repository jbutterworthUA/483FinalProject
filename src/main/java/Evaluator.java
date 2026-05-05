import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Evaluator
 *
 * Reads the 100-question file, runs each question through the Searcher,
 * and reports Top-k accuracy and MRR. Now includes JSONL export capability
 * for LLM Re-ranking pipelines.
 */
public class Evaluator {

    private static final int TOP_K = 10;

    /**
     * record one-liner creates a class with fields and getters/setters
     *
     * @param category the question category
     * @param clue     the question clue
     * @param answer   the question answer
     */
    public record Question(String category, String clue, String answer) {
    }

    /**
     * reads question file into a list of Question objects. questsion file is
     * formated such that
     * quetsions are composed in three lines (each question seperaated by a blank
     * line)
     *
     * @param filepath the path to the questions file
     */
    public static List<Question> parseQuestions(Path filepath) throws IOException {
        List<String> lines = Files.readAllLines(filepath, StandardCharsets.UTF_8);
        List<Question> questions = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            if (lines.get(i).isBlank()) {
                i++;
                continue;
            }

            String category = lines.get(i).strip();

            // ternary protect againsst bounds if question is missing data
            String clue = (i + 1 < lines.size()) ? lines.get(i + 1).strip() : "";
            String answer = (i + 2 < lines.size()) ? lines.get(i + 2).strip() : "";

            questions.add(new Question(category, clue, answer));
            i += 3;
        }
        return questions;
    }

    /**
     * clean a title by lowering its case, removing more than one white space within
     * the
     * title, and removing "the"
     *
     * @param t string to be cleaned
     */
    private static String normalizeTitle(String t) {
        t = t.toLowerCase().strip().replaceAll("\\s+", " ");
        if (t.startsWith("the ")) {
            t = t.substring(4);
        }
        return t;
    }

    /**
     * does a fuzzy match between acceptable gold answers and predicted
     *
     * NOTE could potentially tighten this matching. right now it could bring in a
     * bunch
     * of false positives
     *
     * @param predicted the result of search
     * @param gold      the known correct answer
     * @return true if there is a match, false otherwise
     */
    private static boolean titleMatch(String predicted, String gold) {
        String p = normalizeTitle(predicted);
        String[] acceptableAnswers = gold.split("\\|");

        for (String rawGoldAnswer : acceptableAnswers) {
            String g = normalizeTitle(rawGoldAnswer);
            if (p.equals(g) || p.contains(g) || g.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * fixes back slashes that escape characters in JSON and Java
     *
     * @param s string to fix
     * @return a cleaned string
     */
    private static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * evaluate each question in the 100 question data set provided. Get the
     * searchers results for each questions clue,
     * check if the gold asnwer is within the search results, print out the
     * evaluators results. also computes an
     * error analysis and prints that info if specified in command call
     *
     * @param questions     list of questions parsed from question file
     * @param searcher      a searcher class instance
     * @param useCategory   a flag set on commaand invocation
     * @param errorAnalysis a flag set on command invocation
     * @param exportPath    a flag set on command invocation
     */
    public static void evaluate(List<Question> questions, Searcher searcher, boolean useCategory, boolean errorAnalysis,
            Path exportPath)
            throws IOException {

        if (exportPath != null) {
            Files.createDirectory(exportPath.getParent());
            Files.deleteIfExists(exportPath);
        }

        int n = questions.size();
        int top1 = 0, top5 = 0, top10 = 0;
        double totalRR = 0.0;

        // array to store data about non-perfect results
        List<List<String>> failures = new ArrayList<>();

        System.out.println("\nResult key:");
        System.out.println("   ✓ -> predicted matches gold");
        System.out.println("   ~ -> gold answer in hit list");
        System.out.println("   ✗ -> gold answer not in hit list\n");
        System.out.printf("%n%-4s  %-5s  %-7s  %-35s  %s%n", "#", "Rank", "Result", "Gold answer",
                "Predicted (rank-1)");
        System.out.println("-".repeat(90));

        // calculate the reciprocal rank for each question
        for (int i = 0; i < n; i++) {
            Question q = questions.get(i);
            String catArg = useCategory ? q.category() : "";

            // get list top k SearchResult objects from search
            List<Searcher.SearchResult> hits = searcher.search(q.clue(), catArg, TOP_K);

            // check fuzzy match
            int rank = 0;
            for (int r = 0; r < hits.size(); r++) {
                if (titleMatch(hits.get(r).title(), q.answer())) {
                    // rank is on 0-based indexing so have to +1 to get real rank
                    rank = r + 1;
                    break;
                }
            }

            // increment each catagory based on rank result
            if (rank > 0) {
                if (rank == 1) {
                    top1++;
                }
                if (rank <= 5) {
                    top5++;
                }
                if (rank <= TOP_K) {
                    top10++;
                }
            }

            // calculate reciprocol rank
            double rr = rank > 0 ? 1.0 / rank : 0.0;
            totalRR += rr;

            // char to print in results
            String mark = rank == 1 ? "✓" : (rank > 1 ? "~" : "✗");

            //
            String top1t = hits.isEmpty() ? "(no results)" : hits.get(0).title();

            System.out.printf("%-4d  %-5s  %-7s  %-35s  %s%n",
                    i + 1, rank > 0 ? rank : "-", mark, truncate(q.answer(), 35), truncate(top1t, 45));

            // if not a perfect result, we want to store data for the error analysis
            if (rank != 1) {
                ArrayList<String> failure = new ArrayList<>();
                failure.addAll(Arrays.asList(String.valueOf(i + 1), q.category(), q.clue(), q.answer(), top1t));

                // top 5 search results
                if (hits.size() >= 5) {
                    String top5_hits = "";
                    for (Searcher.SearchResult res : hits.subList(0, 5)) {
                        if (hits.indexOf(res) == 4) {
                            top5_hits += res.title();
                        } else {
                            top5_hits += res.title() + ", ";
                        }
                    }
                    failure.add(top5_hits);
                } else {
                    failure.add("");
                }

                failures.add(failure);
            }

            // Export to JSONL for Python script
            // JSONL = JSON lines -> one JSON object per line
            if (exportPath != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"id\":").append(i + 1)
                        .append(",\"category\":\"").append(escapeJson(q.category())).append("\"")
                        .append(",\"clue\":\"").append(escapeJson(q.clue())).append("\"")
                        .append(",\"gold\":\"").append(escapeJson(q.answer())).append("\"")
                        .append(",\"candidates\":[");
                for (int c = 0; c < hits.size(); c++) {
                    sb.append("\"").append(escapeJson(hits.get(c).title())).append("\"");
                    if (c < hits.size() - 1)
                        sb.append(",");
                }
                sb.append("]}\n");
                Files.writeString(exportPath, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }

        // mean reciprocal rank - average rr across all questions
        double mrr = totalRR / n;

        System.out.println("=".repeat(90));
        System.out.printf("  Top-1  accuracy : %.1f%%  (%d/%d)%n", 100.0 * top1 / n, top1, n);
        System.out.printf("  Top-5  accuracy : %.1f%%  (%d/%d)%n", 100.0 * top5 / n, top5, n);
        System.out.printf("  Top-10 accuracy : %.1f%%  (%d/%d)%n", 100.0 * top10 / n, top10, n);
        System.out.printf("  MRR             : %.4f%n", mrr);
        System.out.println("=".repeat(90));

        if (errorAnalysis) {
            System.out.printf("%n--- Error analysis: %d failures ---%n%n", failures.size());
            for (List<String> f : failures) {
                System.out.printf("Q%s: [%s] %s%n", f.get(0), f.get(1), f.get(2));
                System.out.printf("      Gold : %s%n", f.get(3));
                System.out.printf("      Top-5: %s%n%n", f.get(5));
            }
        }
    }

    /**
     * used to tune the system. doesn't produce any output to the console, just
     * returns MRR
     *
     * @param questions   list of questions parsed from question file
     * @param searcher    a searcher class instance
     * @param useCategory a flag set on commaand invocation
     * @return MRR value
     */
    public static double evaluateSilent(List<Question> questions, Searcher searcher, boolean useCategory)
            throws IOException {
        int n = questions.size();
        double totalRR = 0.0;

        for (Question q : questions) {
            String catArg = useCategory ? q.category() : "";
            List<Searcher.SearchResult> hits = searcher.search(q.clue(), catArg, TOP_K);

            int rank = 0;
            for (int r = 0; r < hits.size(); r++) {
                if (titleMatch(hits.get(r).title(), q.answer())) {
                    rank = r + 1;
                    break;
                }
            }

            if (rank > 0) {
                totalRR += 1.0 / rank;
            }
        }
        return totalRR / n;
    }

    /**
     * shorten string for more comprehensive output
     *
     * @param s   a string to shorten
     * @param max the max length the string can be
     * @return a shortened string
     */
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

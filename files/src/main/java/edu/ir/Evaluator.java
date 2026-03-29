package edu.ir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Evaluator
 *
 * Reads the 100-question file, runs each question through the Searcher,
 * and reports:
 *   - Top-1  accuracy  (rank-1 hit matches gold answer)
 *   - Top-5  accuracy  (gold answer within top 5)
 *   - Top-10 accuracy  (gold answer within top 10)
 *   - Mean Reciprocal Rank (MRR)
 *   - Per-question breakdown for error analysis
 *
 * Question file format (4 lines per question, blank-line separated):
 *   CATEGORY
 *   CLUE
 *   ANSWER
 *   <blank line>
 */
public class Evaluator {

    private static final int TOP_K = 10;

    // -----------------------------------------------------------------------
    // Question parsing
    // -----------------------------------------------------------------------

    public record Question(String category, String clue, String answer) {}

    public static List<Question> parseQuestions(Path filepath) throws IOException {
        List<String> lines = Files.readAllLines(filepath, StandardCharsets.UTF_8);
        List<Question> questions = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            // skip blank lines
            while (i < lines.size() && lines.get(i).isBlank()) i++;
            if (i >= lines.size()) break;

            String category = lines.get(i).strip();
            String clue     = (i + 1 < lines.size()) ? lines.get(i + 1).strip() : "";
            String answer   = (i + 2 < lines.size()) ? lines.get(i + 2).strip() : "";
            questions.add(new Question(category, clue, answer));
            i += 3;
        }
        return questions;
    }

    // -----------------------------------------------------------------------
    // Title matching (flexible, case-insensitive)
    // -----------------------------------------------------------------------

    private static String normalizeTitle(String t) {
        t = t.toLowerCase().strip().replaceAll("\\s+", " ");
        if (t.startsWith("the ")) t = t.substring(4);
        return t;
    }

    private static boolean titleMatch(String predicted, String gold) {
        String p = normalizeTitle(predicted);
        String g = normalizeTitle(gold);
        return p.equals(g) || p.contains(g) || g.contains(p);
    }

    // -----------------------------------------------------------------------
    // Main evaluation loop
    // -----------------------------------------------------------------------

    public static void evaluate(List<Question> questions, Searcher searcher,
                                boolean useCategory, boolean errorAnalysis)
            throws IOException {

        int n = questions.size();
        int top1 = 0, top5 = 0, top10 = 0;
        double totalRR = 0.0;
        List<String[]> failures = new ArrayList<>();

        System.out.printf("%n%-4s  %-5s  %-7s  %-35s  %s%n",
                "#", "Rank", "Result", "Gold answer", "Predicted (rank-1)");
        System.out.println("-".repeat(90));

        for (int i = 0; i < n; i++) {
            Question q = questions.get(i);
            String catArg = useCategory ? q.category() : "";

            List<Searcher.SearchResult> hits =
                    searcher.search(q.clue(), catArg, TOP_K);

            // Find rank of correct answer (1-based; 0 if not in top-k)
            int rank = 0;
            for (int r = 0; r < hits.size(); r++) {
                if (titleMatch(hits.get(r).title(), q.answer())) {
                    rank = r + 1;
                    break;
                }
            }

            if (rank == 1)  { top1++;  top5++;  top10++; }
            else if (rank <= 5)  { top5++;  top10++; }
            else if (rank <= 10) { top10++; }

            double rr = rank > 0 ? 1.0 / rank : 0.0;
            totalRR += rr;

            String mark  = rank == 1 ? "✓" : (rank > 1 ? "~" : "✗");
            String top1t = hits.isEmpty() ? "(no results)" : hits.get(0).title();

            System.out.printf("%-4d  %-5s  %-7s  %-35s  %s%n",
                    i + 1,
                    rank > 0 ? rank : "-",
                    mark,
                    truncate(q.answer(), 35),
                    truncate(top1t, 45));

            if (rank != 1) {
                failures.add(new String[]{
                    String.valueOf(i + 1), q.category(), q.clue(),
                    q.answer(), top1t,
                    hits.size() >= 5
                        ? hits.subList(0, 5).stream()
                               .map(Searcher.SearchResult::title)
                               .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b)
                        : ""
                });
            }
        }

        double mrr = totalRR / n;

        System.out.println("=".repeat(90));
        System.out.printf("  Top-1  accuracy : %.1f%%  (%d/%d)%n", 100.0*top1/n,  top1,  n);
        System.out.printf("  Top-5  accuracy : %.1f%%  (%d/%d)%n", 100.0*top5/n,  top5,  n);
        System.out.printf("  Top-10 accuracy : %.1f%%  (%d/%d)%n", 100.0*top10/n, top10, n);
        System.out.printf("  MRR             : %.4f%n", mrr);
        System.out.println("=".repeat(90));

        if (errorAnalysis) {
            System.out.printf("%n--- Error analysis: first %d failures ---%n%n",
                    Math.min(10, failures.size()));
            for (String[] f : failures.subList(0, Math.min(10, failures.size()))) {
                System.out.printf("Q%s: [%s] %s%n", f[0], f[1], f[2]);
                System.out.printf("      Gold : %s%n", f[3]);
                System.out.printf("      Top-1: %s%n", f[4]);
                System.out.printf("      Top-5: %s%n%n", f[5]);
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

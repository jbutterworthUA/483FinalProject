package edu.ir;

import java.nio.file.*;
import java.util.List;

/**
 * Main
 *
 * Command-line entry point. Four subcommands:
 *
 * index --wiki_dir <dir> --index_dir <dir>
 * evaluate --questions <file> --index_dir <dir> [--no_category] [--errors] [--export <file>]
 * search --index_dir <dir> --clue "<clue>" [--category "<cat>"] [--top_k N]
 * tune --questions <file> --index_dir <dir> [--no_category]
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        switch (command) {
            case "index" -> runIndex(args);
            case "evaluate" -> runEvaluate(args);
            case "search" -> runSearch(args);
            case "tune" -> runTune(args);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        }
    }

    // -----------------------------------------------------------------------
    // index subcommand
    // -----------------------------------------------------------------------
    private static void runIndex(String[] args) throws Exception {
        Path wikiDir = Path.of(getArg(args, "--wiki_dir", "wiki_files"));
        Path indexDir = Path.of(getArg(args, "--index_dir", "wiki_index"));

        System.out.println("Building Lucene index");
        System.out.println("  Wiki files : " + wikiDir);
        System.out.println("  Index dir  : " + indexDir);
        System.out.println();

        Files.createDirectories(indexDir);
        WikiIndexer.buildIndex(wikiDir, indexDir);
    }

    // -----------------------------------------------------------------------
    // evaluate subcommand
    // -----------------------------------------------------------------------
    private static void runEvaluate(String[] args) throws Exception {
        Path questionsFile = Path.of(getArg(args, "--questions", "questions.txt"));
        Path indexDir = Path.of(getArg(args, "--index_dir", "wiki_index"));
        boolean useCategory = !hasFlag(args, "--no_category");
        boolean errorAnalysis = hasFlag(args, "--errors");
        
        Path exportPath = null;
        if (hasFlag(args, "--export")) {
            exportPath = Path.of(getArg(args, "--export", "results.jsonl"));
        }

        System.out.println("Evaluating Jeopardy QA system");
        System.out.println("  Index dir     : " + indexDir);
        System.out.println("  Questions file: " + questionsFile);
        System.out.println("  Use category  : " + useCategory);
        if (exportPath != null) {
            System.out.println("  Exporting to  : " + exportPath);
        }
        System.out.println();

        List<Evaluator.Question> questions = Evaluator.parseQuestions(questionsFile);
        System.out.println("Loaded " + questions.size() + " questions.");

        try (Searcher searcher = new Searcher(indexDir)) {
            Evaluator.evaluate(questions, searcher, useCategory, errorAnalysis, exportPath);
        }
    }

    // -----------------------------------------------------------------------
    // search subcommand
    // -----------------------------------------------------------------------
    private static void runSearch(String[] args) throws Exception {
        Path indexDir = Path.of(getArg(args, "--index_dir", "wiki_index"));
        String clue = getArg(args, "--clue", "");
        String category = getArg(args, "--category", "");
        int topK = Integer.parseInt(getArg(args, "--top_k", "10"));

        if (clue.isBlank()) {
            java.util.Scanner sc = new java.util.Scanner(System.in);
            try (Searcher searcher = new Searcher(indexDir)) {
                while (true) {
                    System.out.print("Category (or blank): ");
                    category = sc.nextLine().strip();
                    System.out.print("Clue: ");
                    clue = sc.nextLine().strip();
                    if (clue.isBlank())
                        break;
                    printHits(searcher.search(clue, category, topK));
                    System.out.println();

                }
            }
            sc.close();
        } else {
            try (Searcher searcher = new Searcher(indexDir)) {
                printHits(searcher.search(clue, category, topK));
            }
        }
    }

    // -----------------------------------------------------------------------
    // tune subcommand
    // -----------------------------------------------------------------------
    private static void runTune(String[] args) throws Exception {
        Path questionsFile = Path.of(getArg(args, "--questions", "questions.txt"));
        Path indexDir = Path.of(getArg(args, "--index_dir", "wiki_index"));
        boolean useCategory = !hasFlag(args, "--no_category");

        System.out.println("Starting Grid Search for k1 (b is fixed at 0.20)...");
        System.out.println("  Index dir     : " + indexDir);
        System.out.println("  Questions file: " + questionsFile);
        System.out.println();

        List<Evaluator.Question> questions = Evaluator.parseQuestions(questionsFile);
        
        double bestMrr = 0.0;
        float bestK1 = 0.0f;

        System.out.printf("  %-10s | %-10s%n", "k1 value", "MRR");
        System.out.println("  " + "-".repeat(23));

        for (float k1 = 0.5f; k1 <= 2.5f; k1 += 0.1f) {
            try (Searcher searcher = new Searcher(indexDir, k1, 0.20f)) {
                double mrr = Evaluator.evaluateSilent(questions, searcher, useCategory);
                System.out.printf("  %-10.1f | %-10.4f%n", k1, mrr);

                if (mrr > bestMrr) {
                    bestMrr = mrr;
                    bestK1 = k1;
                }
            }
        }
        
        System.out.println("  " + "=".repeat(23));
        System.out.printf("  OPTIMAL k1 : %.1f%n", bestK1);
        System.out.printf("  PEAK MRR   : %.4f%n", bestMrr);
        System.out.println("  " + "=".repeat(23));
    }

    // -----------------------------------------------------------------------
    // Print Hits Helper
    // -----------------------------------------------------------------------
    private static void printHits(List<Searcher.SearchResult> hits) {
        System.out.printf("%n  %-4s  %-10s  %s%n", "Rank", "Score", "Title");
        System.out.println("  " + "-".repeat(60));
        for (int i = 0; i < hits.size(); i++) {
            System.out.printf("  %-4d  %-10.4f  %s%n",
                    i + 1, hits.get(i).score(), hits.get(i).title());
        }
    }

    // -----------------------------------------------------------------------
    // Arg helpers
    // -----------------------------------------------------------------------
    private static String getArg(String[] args, String flag, String defaultVal) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag))
                return args[i + 1];
        }
        return defaultVal;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args)
            if (a.equals(flag))
                return true;
        return false;
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -jar jeopardy-qa.jar <command> [options]

                Commands:
                  index
                    --wiki_dir  <dir>   Directory containing the 80 Wikipedia files
                    --index_dir <dir>   Where to write the Lucene index  [default: wiki_index]

                  evaluate
                    --questions  <file>  Path to the 100-question file
                    --index_dir  <dir>   Lucene index directory            [default: wiki_index]
                    --no_category        Disable category signal in queries
                    --errors             Print detailed failure analysis
                    --export     <file>  Export top 10 results to JSONL for LLM re-ranking

                  search
                    --index_dir  <dir>   Lucene index directory
                    --clue       <str>   Single clue to search
                    --category   <str>   Category string (optional)
                    --top_k      <int>   Number of results to return       [default: 10]
                  
                  tune
                    --questions  <file>  Path to the 100-question file
                    --index_dir  <dir>   Lucene index directory            [default: wiki_index]
                    --no_category        Disable category signal in queries
                """);
    }
}
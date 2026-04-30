# Jeopardy QA System — Lucene + LLM Implementation

## Project Structure

```
483FinalProject/
├── pom.xml
├── wiki-subset-20140602/
├── wiki_questions.txt
├── README.md
├── example/
│   └── wiki-example.txt
├── scripts/
│   └── rerank.py
└── src/main/java/
    ├── Main.java          CLI entry point (index / evaluate / search)
    ├── WikiIndexer.java   Parse Wikipedia corpus → Lucene index
    ├── QueryBuilder.java  Convert Jeopardy clue → weighted Lucene query
    ├── Searcher.java      IndexSearcher wrapper (BM25)
    └── Evaluator.java     Batch evaluation: accuracy + MRR
```

## Requirements

- Python 3.14 (+requirements.txt)
- Java 21+
- Maven 3.6+
- RAM usage during indexing depends on JVM heap settings; 256 MB RAM buffer; disk index is ~230 MB

## Build

**ran from the root of the project**

1. **Build Maven project**

    ```bash
    mvn package -q
    # produces: target/jeopardy-qa-1.jar
    ```

2. **Create Python virtual environment**

    ```bash
    python3 -m venv .venv
    # produces .venv/
    ```

    **Activate .venv**

    - MacOS/Linux:

        ```bash
        source .venv/bin/activate
        ```

    - Windows:

        ```bash
        .venv\Scripts\activate
        ```

   **Install dependencies**

      ```bash
      pip install -r requirements.txt
      ```

5. **Set environment variables**

    - create a .env file in root directory
    - use .env.example as a template

## Run

**ran from the root of the project**

1. **Index the Wikipedia corpus (one-time, ~5 min)**

```bash
java -jar target/jeopardy-qa-1.jar index
# produces wiki_index/ 
```

2. **Evaluate on the 100 Jeopardy questions**

```bash
java -jar target/jeopardy-qa-1.jar evaluate --errors
# produces results/results.jsonl
```

3. **Run the LLM reranking script (~5 min)**

```bash
python3 scripts/rerank.py
# produces results/reranked_results.jsonl
```

## Other interactive commands

**ran from the root of the project**

#### Interactive single-question demo

```bash
java -jar target/jeopardy-qa-1.jar search
# then type category and clue at the prompts.
```

#### Single clue from command line

```bash
java -jar target/jeopardy-qa-1.jar search \
     --category "ATHLETES" \
     --clue "This woman who won consecutive heptathlons at the Olympics went to UCLA on a basketball scholarship" \
```

---

## Implementation Report

#### **Indexing**

**IR system: Apache Lucene 10.4 with BM25F**

Lucene is the industry-standard IR library used in Elasticsearch, Solr, and
many production search systems.  BM25 (Best Match 25) is the default scoring
model since Lucene 6 and outperforms classical TF-IDF by:
- Applying a term-frequency saturation curve (controlled by k1) that prevents
  a term appearing 1000 times from dominating a term appearing 10 times.
- Normalising document length (controlled by b) so short pages are not
  unfairly penalised.

**Page splitting**

Each of the 80 flat files contains thousands of pages separated by
`[[PageTitle]]` at the start of a line (regex: `^\[\[(.+?)\]\]\s*$`).
We scan for all markers, then slice the raw text between consecutive markers
to form page bodies.  **Redirect pages (first line matches `#REDIRECT`) are
skipped — they hold no unique content and add ~40% noisy documents.**

Each page becomes one Lucene `Document` with three fields:

| Field | Type | Analysed | Stored | Purpose |
|---|---|---|---|---|
| `title_raw` | StringField | No | Yes | Return verbatim as the answer |
| `title`     | TextField   | Yes | No | Boosted match on title |
| `content`   | TextField   | Yes | No | Match on page body |

#### **Wikitext cleaning (Wikipedia-specific issues)**

Raw Wikipedia text contains several categories of markup noise:

| Issue | Example | Solution |
|---|---|---|
| Template calls | `{{Infobox person\|...}}` | Remove entirely (regex, DOTALL) |
| Wikitext tags | `[tpl]...[/tpl]` | Remove entirely |
| Citation tags | `<ref>Smith 2001</ref>` | Remove entirely |
| Piped links | `[[Albert Einstein\|Einstein]]` | Keep display text (`$1`) |
| Plain links | `[[Abraham Lincoln]]` | Keep link text |
| External links | `[https://... anchor]` | Keep anchor text |
| Section headers | `== Early life ==` | Strip `=` signs, keep text |
| HTML tags | `<br/>`, `<table>` | Strip entirely |
| Table rows | Lines starting with `\|` or `!` | Strip entirely (wikitext tables) |
| HTML entities | `&amp;` `&nbsp;` | Decode to plain characters |
| Punctuation runs | `---`, `~~~~`, `===` | Collapse to single space |

The cleaning is a single sequential regex pass (~10 patterns) which processes
140,000 pages fast enough for a one-time index build.

#### **Analyser: EnglishAnalyzer**

Lucene's built-in `EnglishAnalyzer` applies this token filter chain:

1. **Standard tokeniser** — Unicode-aware, handles contractions, hyphens
2. **Lowercase filter** — "Wikipedia" → "wikipedia"
3. **English possessive filter** — strips `'s`
4. **Stop filter** — removes ~33 common English function words
5. **Porter stemmer** — "winning", "winner", "won" → "win"

Stemming is the most impactful single pre-processing step for recall on this
task.  Jeopardy clues routinely use different verb forms and tenses than the
Wikipedia text that describes the same event.

Lemmatisation (returning dictionary base forms) would be slightly more precise
but requires part-of-speech disambiguation and is significantly slower.  For
a corpus of this size, Porter stemming gives a better speed–accuracy trade-off.

#### Query building (QueryBuilder.java)

A raw Jeopardy clue is a full sentence, not a keyword query.  Submitting the
whole sentence to Lucene hurts precision because:
- Function words are not fully removed by the stopword list (e.g., "won",
  "went", "large" survive but carry little discriminative power).
- All surviving tokens get equal weight, but proper nouns (UCLA, Olympics)
  are far more informative than common nouns.

**Algorithm:**

1. Split the clue on whitespace.
2. For each word, run it through `EnglishAnalyzer` to get the stemmed form.
   If the analyser returns nothing (stopword), skip the token.
3. Tokens that start with a capital letter in the original clue are treated
   as proper nouns and receive `PROPER_NOUN_BOOST = 3.0×`.
4. All remaining content tokens receive `1.0×`.
5. Each token generates two `TermQuery` clauses — one on `title` (additionally
   boosted `3.0×` via `BoostQuery`) and one on `content`.  Both clauses use
   `BooleanClause.Occur.SHOULD` (OR semantics).
6. Category tokens are added with `CATEGORY_TERM_BOOST = 1.5×`.

**Why capitalisation as a proper-noun proxy?**

POS tagging with a full NLTK/CoreNLP pipeline would be more accurate but
requires Python or a Java NLP library dependency.  Capitalisation is a
reliable proxy for English: in a Jeopardy clue, nearly all capitalised tokens
that are not at the start of the sentence are proper nouns (names, places,
organisations).  For the first word, we skip the boost since sentence-initial
capitalisation is grammatical, not semantic.

**Why OR (SHOULD) rather than AND (MUST)?**

Conjunctive queries require every term to appear in the result document.
Jeopardy clues and Wikipedia text often use synonyms or paraphrases for the
same concept.  OR with BM25 naturally promotes documents that match *more*
query terms while not disqualifying documents that miss one or two rare terms.

**Why boost the title field?**

Jeopardy answers are Wikipedia titles by construction.  A document whose
title IS the answer should score higher than one that merely *mentions* the
answer in its body.  The `3.0×` title field boost is applied via `BoostQuery`
at search time.

#### **Evaluation metrics**

| Metric | Formula | What it measures |
|---|---|---|
| Top-1 accuracy | fraction where rank = 1 | System's first guess is correct |
| Top-5 accuracy | fraction where rank ≤ 5 | Correct answer in top 5 |
| Top-10 accuracy | fraction where rank ≤ 10 | Correct answer in top 10 |
| MRR | mean(1/rank); rank=∞ if not found | Rewards answers found early |



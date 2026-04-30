
"""
short script in an attempt to make improvements to our implementation of the Jeopardy QA System by leveraging
an LLMs reasoning capabilities 

@authors Garret W., John I., Jason B., Logan A.
"""


import json
import re
import time
import os

from dotenv import load_dotenv
from concurrent.futures import ThreadPoolExecutor, as_completed
from google import genai


load_dotenv()

# LLM info
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY") 
MODEL_NAME = "gemini-2.5-flash" 

MAX_WORKERS = 4
MAX_RETRIES = 6  
BACKOFF_BASE = 2 


# initialize 
client = genai.Client(api_key=GEMINI_API_KEY)

def title_match(predicted, gold):
    """same criteria for title mathcing in IR system


    :param str predicted: IR predicted answer
    :param str gold: expected answer
    :return: true if fuzzy match, false otherwise
    """
    p = predicted.lower().strip()
    if p.startswith("the "):
        p = p[4:]

    acceptable = gold.split("|")
    for g in acceptable:
        g = g.lower().strip()
        if g.startswith("the "):
            g = g[4:]
        if p == g or g in p or p in g:
            return True

    return False


def query_llm(prompt):
    """prompt the LLM and extract its answer from its response

    :param str prompt: the prompt sent to the LLM
    """
    for attempt in range(MAX_RETRIES):
        try:
            response = client.models.generate_content(model=MODEL_NAME, contents=prompt)

            if response and response.text:
                text = response.text.strip()

                # Extract the final answer from the Chain-of-Thought reasoning
                if "FINAL ANSWER:" in text:
                    text = text.split("FINAL ANSWER:")[-1].strip()

                # Strip out any lingering numbers or markdown formatting
                text = re.sub(r"^\d+\.\s*", "", text)
                text = text.replace("*", "").strip()
                return text
            else:
                return ""

        except Exception as e:
            if attempt == MAX_RETRIES - 1:
                print(f"API Error: {e}")
                raise e

            # Wait and retry if we hit rate limits (429)
            time.sleep(BACKOFF_BASE**attempt)

    return None


def worker(item):
    """worker function that unpacks an object with question data

    :param dict item: a dictionary constructed from JSON of question data
    """
    clue = item["clue"]
    cat = item["category"]
    gold = item["gold"]
    candidates = item["candidates"]

    if not candidates:
        return None

    # go through the candiate list and check if any fuzzy match the gold answer
    # if so, return it rank, otherwise 0
    rank_before = next((i + 1 for i, c in enumerate(candidates) if title_match(c, gold)), 0)
    rr_before = 1.0 / rank_before if rank_before else 0

    # Format as bullet points instead of numbers to reduce position bias
    options = "\n".join(f"- {c}" for c in candidates)

    # CHAIN-OF-THOUGHT PROMPT
    prompt = f"""
             You are a Jeopardy champion analyzing a clue.
             Category: {cat}
             Clue: {clue}

             A basic search engine has proposed these 10 Wikipedia articles as potential answers:
             {options}

             First, briefly think step-by-step about the clue and evaluate the candidates.
             Then, on the very last line, provide your final choice formatted exactly like this:
             FINAL ANSWER: Exact Title Here
             """

    # LLM Request
    try:
        llm_choice = query_llm(prompt)
    except Exception:
        llm_choice = candidates[0]

    # Robust matching: ensure LLM choice maps exactly back to a candidate
    if llm_choice and llm_choice not in candidates:
        matches = [c for c in candidates if llm_choice.lower() in c.lower() or c.lower() in llm_choice.lower()]
        llm_choice = matches[0] if matches else candidates[0]
    elif not llm_choice:
        llm_choice = candidates[0]

    # Re-rank: Put the LLM choice at Rank 1, shift everything else down
    new_ranked = [llm_choice] + [c for c in candidates if c != llm_choice]

    rank_after = next((i + 1 for i, c in enumerate(new_ranked) if title_match(c, gold)), 0)
    rr_after = 1.0 / rank_after if rank_after else 0
    top1 = int(rank_after == 1)

    # FIX 4: The 15 Requests Per Minute speed limit enforcer
    # (4.5 seconds ensures we stay under the API limit)
    time.sleep(4.5)

    return {
        "id": item["id"],
        "rank_before": rank_before,
        "rank_after": rank_after,
        "rr_before": rr_before,
        "rr_after": rr_after,
        "top1": top1,
        "choice": llm_choice,
    }


def potential_rerank(item):
    """determines if a question is eligible to be considered for reranking. that is, the candidate
    list of hits must contain an element that fuzzy matches the gold answer and the gold answer is
    not the first element in the candiate list

    :param item: a python object created from JSON data
    :return: true if candidate list contains answer, false otherwise
    """
    gold = item["gold"].lower()
    candidates = [candidate.lower() for candidate in item["candidates"]]

    if not question_correct(item):
        for candiate in candidates[1:]:
            if gold == candiate or gold in candiate or candiate in gold:
                return True

    return False


def question_correct(item):
    """determines if the gold answer matches the first item in candiates list, indicatng a correct
    result

    :retur: True if question is correct, False otherwise
    """
    gold = item["gold"].lower()
    candidates_r1 = item["candidates"][0].lower()
    
    return gold == candidates_r1 or gold in candidates_r1 or candidates_r1 in gold


def main():
    """parallel main function that manages MAX_WORKERS who each send an API request to 
    Gemini
    """
    print("Loading Lucene predictions...")
    with open("results/results.jsonl", "r", encoding="utf-8") as f:
        # loads reads in json objects as python objects
        data = [json.loads(line) for line in f]

    rerank_eligible = [i for i in data if potential_rerank(i)]
    correct = [i for i in data if question_correct(i)] 
    not_rerank_eligible = [i for i in data if i not in rerank_eligible and i not in correct]

    results = []

    # take care of results that dont need to to be reevaluated
    for item in correct:
        results.append(
            {
                "id": item["id"],
                "rank_before": 1,
                "rank_after": 1,
                "rr_before": 1,
                "rr_after": 1,
                "top1": 1,
            }
        )
    for item in not_rerank_eligible:
        results.append(
            {
                "id": item["id"],
                "rank_before": 0,
                "rank_after": 0,
                "rr_before": 0,
                "rr_after": 0,
                "top1": 0,
            }
        )

    print(f"Begin LLM Re-ranking of {len(rerank_eligible)} query candidate answer sets (model: " + MODEL_NAME + ")...\n")
    print(f"{'Q#':<5} | {'Old Rank':<10} | {'New Rank':<10} | {'LLM Choice'}")
    print("-" * 75)

    # ThreadPoolExecutor manages a pool of worker threads
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        # goes through each rerank eligible question and submits it with a worker as a task to the executor
        futures = [executor.submit(worker, i) for i in rerank_eligible]

        # threads that have completed are accesible via as_completed(futures)
        # blocks on non-completed threads
        for future in as_completed(futures):
            # the return from the worker
            res = future.result()
            if res:
                results.append(res)
                print(
                    f"{res['id']:<5} | {res['rank_before']:<10} | {res['rank_after']:<10} | {res['choice']}"
                )

    n = len(results)
    print("\n" + "=" * 50)
    print("RE-RANKING COMPLETE")
    print("=" * 50)
    print(f"Baseline Lucene MRR : {sum(r['rr_before'] for r in results)/n:.4f}")
    print(f"New LLM MRR         : {sum(r['rr_after'] for r in results)/n:.4f}")
    print(f"New Top-1 Accuracy  : {sum(r['top1'] for r in results)/n*100:.1f}%")


if __name__ == "__main__":
    main()


"""
gemini_review.py: sends a PR diff or project files to Gemini 2.5 Pro for review.

Modes:
  Diff review (default): reads pr_diff.txt, reviews a PR or local changes.
  Full audit (REVIEW_FULL=1): reads pr_diff.txt as concatenated source files
    and produces a holistic project audit instead of a change-focused review.

Environment variables:
  GEMINI_API_KEY:  Google AI Studio API key
  PR_TITLE:        Pull request title or review label
  PR_BODY:         Pull request description (may be empty)
  REVIEW_FULL:     Set to "1" to activate full project audit mode
"""

import json
import os
import sys
import urllib.error
import urllib.request

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
PR_TITLE = os.environ.get("PR_TITLE", "(no title)")
PR_BODY = os.environ.get("PR_BODY", "")
REVIEW_FULL = os.environ.get("REVIEW_FULL", "") == "1"
MODEL = "gemini-2.5-pro"

# Truncate very large diffs so we stay well within the context window and
# avoid unexpectedly large token bills. 80 000 chars covers ~20 000 tokens.
# more than enough for any realistic PR.
MAX_DIFF_CHARS = 80_000

# ---------------------------------------------------------------------------
# System prompt: encodes this project's rules and conventions
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """

You are an expert code reviewer. Review the pull request diff provided and
identify real problems that matter. Focus on:

- Correctness bugs and logic errors
- Security issues (injection, secrets exposure, auth/authz gaps, unsafe deserialization)
- Breaking changes to APIs, schemas, or public interfaces
- Performance regressions (N+1 queries, unbounded work, accidental O(n^2))
- Error handling gaps that would cause silent data loss or crashes
- Concurrency/race conditions

## What NOT to flag

- Style preferences or formatting (assume a formatter handles this)
- Missing comments/docs on internal helpers
- Naming preferences when existing conventions are consistent
- Alternative approaches when the chosen one is reasonable and working

Do not invent issues to seem thorough. If the change is good, say so.

## Response format

Reply in markdown using exactly this structure:

### Summary
One or two sentences on the overall quality of the change.

### Issues
For each issue found:
**[CRITICAL|HIGH|MEDIUM|LOW]** `path/to/file`: brief description and why it matters.

If there are no issues, write: "No issues found."

### Suggestions
Optional non-blocking improvements. Omit this section entirely if there are none.
""".strip()


# ---------------------------------------------------------------------------
# Full audit system prompt
# ---------------------------------------------------------------------------

FULL_AUDIT_PROMPT = """

You are auditing a codebase for a holistic quality review. The relevant
source files are provided below. Look for systemic issues, not line-by-line
nits.

## What to assess

**1. Security posture** — injection risks, secrets in code, missing auth
checks, unsafe handling of untrusted input.

**2. Correctness risks** — race conditions, error paths that silently lose
data, resource leaks, broken invariants between modules.

**3. Structural health** — duplicated logic drifting apart, dead code,
circular dependencies, config sprawl.

**4. Operational readiness** — missing shutdown handling, absent health
checks, logging gaps that would blind debugging.

Prioritize findings by real-world impact. Do not pad the report; if an area
is fine, say so briefly.

## Response format

Reply in markdown:

### Summary
Overall health of the codebase in a few sentences.

### Findings
For each finding:
**[CRITICAL|HIGH|MEDIUM|LOW]** area/file: description and impact.

### Positive notes
Things done notably well, if any.
""".strip()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    if not GEMINI_API_KEY:
        print("ERROR: GEMINI_API_KEY environment variable is not set.", file=sys.stderr)
        sys.exit(1)

    # Read the content written by the workflow step or review.sh
    try:
        with open("pr_diff.txt", "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
    except FileNotFoundError:
        print("ERROR: pr_diff.txt not found.", file=sys.stderr)
        sys.exit(1)

    if not content.strip():
        label = "Project Audit" if REVIEW_FULL else "Code Review"
        write_review(f"## 🤖 Gemini {label}\n\nNothing to review, no content provided.")
        return

    # Truncate if needed and note it in the content
    if len(content) > MAX_DIFF_CHARS:
        content = content[:MAX_DIFF_CHARS] + "\n\n[content truncated, too large to review in full]"

    if REVIEW_FULL:
        # Full audit mode: content is concatenated source files, not a diff.
        # Use the holistic audit prompt and a plain user message.
        system_prompt = FULL_AUDIT_PROMPT
        user_message = content
        output_label = "Gemini Project Audit"
    else:
        # Diff review mode: content is a git diff for a specific PR or local change.
        system_prompt = SYSTEM_PROMPT
        user_message = f"""## PR: {PR_TITLE}

{PR_BODY.strip() or '(no description provided)'}

## Diff

```diff
{content}
```
"""
        output_label = "Gemini Code Review"

    payload = {
        "contents": [
            {
                "role": "user",
                "parts": [{"text": user_message}],
            }
        ],
        "systemInstruction": {
            "parts": [{"text": system_prompt}]
        },
        "generationConfig": {
            # Low temperature for consistent, focused reviews
            "temperature": 0.2,
            # 2.5 Pro uses thinking tokens that count against maxOutputTokens.
            # 4096 was too small: the model exhausted its budget on reasoning
            # before producing any response text (finishReason=MAX_TOKENS with
            # empty parts). 16384 gives ~8k for thinking and ~8k for the reply.
            "maxOutputTokens": 16384,
            # Cap thinking tokens so the response always gets sufficient budget.
            # Without this, 2.5 Pro can spend the entire output budget on reasoning.
            "thinkingConfig": {
                "thinkingBudget": 8192,
            },
        },
    }

    url = (
        f"https://generativelanguage.googleapis.com/v1beta/models/"
        f"{MODEL}:generateContent?key={GEMINI_API_KEY}"
    )

    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    # Retry on transient errors (503 overload, 429 rate limit) with exponential backoff.
    # Gemini 2.5 Pro can be briefly unavailable during demand spikes.
    MAX_RETRIES = 4
    result = None
    for attempt in range(MAX_RETRIES):
        try:
            with urllib.request.urlopen(req, timeout=180) as response:
                result = json.loads(response.read())
            break  # success: exit retry loop
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            if e.code in (429, 503) and attempt < MAX_RETRIES - 1:
                wait = 10 * (2 ** attempt)  # 10s, 20s, 40s
                print(f"Gemini returned {e.code} (attempt {attempt + 1}/{MAX_RETRIES}), retrying in {wait}s...", file=sys.stderr)
                import time; time.sleep(wait)
                # Rebuild the request object; it can't be reused after an error
                req = urllib.request.Request(
                    url,
                    data=json.dumps(payload).encode("utf-8"),
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
            elif e.code in (429, 503):
                # All retries exhausted for a transient error: write a soft
                # failure comment so the PR check passes and merging isn't blocked.
                print(f"Gemini unavailable after {MAX_RETRIES} attempts ({e.code}). Writing soft-failure comment.", file=sys.stderr)
                write_review(
                    f"## 🤖 Gemini Code Review\n\n"
                    f"⚠️ Gemini is temporarily unavailable (HTTP {e.code}). "
                    f"Re-run the workflow once the service recovers.\n\n"
                    f"---\n*Attempted {MAX_RETRIES} times using [{MODEL}](https://ai.google.dev/)*"
                )
                return
            else:
                print(f"ERROR: Gemini API returned {e.code}: {body}", file=sys.stderr)
                sys.exit(1)
    if result is None:
        print("ERROR: All retries exhausted.", file=sys.stderr)
        sys.exit(1)

    # Gemini 2.5 Pro returns thinking tokens as extra parts before the real response.
    # Find the first non-thought part that contains text.
    candidate = result["candidates"][0]
    finish_reason = candidate.get("finishReason", "UNKNOWN")

    # "content" may be absent (MAX_TOKENS, SAFETY, RECITATION) or present but
    # without "parts" (empty/filtered response). Handle both cases gracefully.
    parts = candidate.get("content", {}).get("parts", [])
    review_text = next(
        (p["text"] for p in parts if "text" in p and not p.get("thought")),
        None,
    )
    if not review_text:
        print(f"WARNING: Gemini returned no usable text (finishReason={finish_reason}): {json.dumps(result)}", file=sys.stderr)
        write_review(
            f"## 🤖 Gemini Code Review\n\n"
            f"⚠️ Gemini returned an empty response (finishReason={finish_reason}). "
            f"This can happen when the diff is too large or triggers a safety filter. "
            f"Re-run the workflow or check the action logs for details.\n\n"
            f"---\n*Reviewed by [{MODEL}](https://ai.google.dev/)*"
        )
        return
    write_review(f"## 🤖 {output_label}\n\n{review_text}\n\n---\n*Reviewed by [{MODEL}](https://ai.google.dev/)*")
    print(f"Review written to gemini_review.md")


def write_review(content: str):
    with open("gemini_review.md", "w", encoding="utf-8") as f:
        f.write(content)


if __name__ == "__main__":
    main()
